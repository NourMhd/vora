package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.provider.mapper.SiteMongoMapper;
import com.fusionsolar.monitoring.provider.solis.SolisMonitoringProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Backfill Solis pour les buckets MONTH/YEAR.
 *
 * Règle importante:
 * - MONTH = production journalière kWh.
 * - DAY = courbe puissance kW.
 * - Les KPI journaliers utilisent MONTH, jamais DAY.
 * - Pour le mois courant, on sauvegarde uniquement les jours terminés jusqu'à hier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "providers.solis", name = "enabled", havingValue = "true")
public class SolisBatchHistoryBackfillService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");

    private final SiteRepository siteRepository;
    private final SiteHistoryRepository siteHistoryRepository;
    private final SiteMongoMapper siteMongoMapper;
    private final SolisMonitoringProvider solisMonitoringProvider;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${app.solis-batch-backfill.enabled:true}")
    private boolean enabled;

    @Value("${app.solis-batch-backfill.batch-size:1}")
    private int batchSize;

    @Value("${app.solis-batch-backfill.max-calls-per-run:2}")
    private int maxCallsPerRun;

    @Value("${app.solis-batch-backfill.max-lookback-months:${app.history-backfill.max-lookback-months:120}}")
    private int maxLookbackMonths;

    @Value("${app.solis-batch-backfill.empty-history-retry-ms:${app.history-backfill.empty-history-retry-ms:86400000}}")
    private long emptyHistoryRetryMs;

    @Value("${app.solis-batch-backfill.current-month-refresh-ms:${app.history-backfill.current-month-refresh-ms:21600000}}")
    private long currentMonthRefreshMs;

    @Value("${app.solis-batch-backfill.incomplete-month-refresh-ms:${app.history-backfill.incomplete-month-refresh-ms:21600000}}")
    private long incompleteMonthRefreshMs;

    @Value("${app.solis-batch-backfill.invalid-value-month-refresh-ms:${app.history-backfill.invalid-value-month-refresh-ms:21600000}}")
    private long invalidValueMonthRefreshMs;

    @Value("${app.solis-batch-backfill.month-finalization-grace-hours:${app.history-backfill.month-finalization-grace-hours:6}}")
    private int monthFinalizationGraceHours;

    @Value("${app.solis-batch-backfill.current-year-refresh-ms:${app.history-backfill.current-year-refresh-ms:21600000}}")
    private long currentYearRefreshMs;

    @Value("${app.history-backfill.solis-year-backfill-enabled:false}")
    private boolean solisYearBackfillEnabled;

    @Scheduled(
            initialDelayString = "${app.solis-batch-backfill.initial-delay-ms:180000}",
            fixedDelayString = "${app.solis-batch-backfill.fixed-delay-ms:30000}"
    )
    public void backfillSolisHistoriesInBatches() {
        if (!enabled) {
            return;
        }

        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            int calls = runOnePass();
            if (calls > 0) {
                log.info("Solis batch history pass finished: {} provider call(s)", calls);
            }
        } catch (Exception e) {
            log.warn("Solis batch history pass failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private int runOnePass() {
        List<SiteDocument> sites = siteRepository.findAllByBrandOrderBySiteNameAsc(Brand.SOLIS)
                .stream()
                .filter(SiteSyncPolicy::isVisibleSite)
                .sorted(Comparator.comparing(
                        site -> site.getSiteName() == null ? "" : site.getSiteName(),
                        String.CASE_INSENSITIVE_ORDER
                ))
                .toList();

        if (sites.isEmpty()) {
            return 0;
        }

        int callsDone = 0;
        for (HistoryCandidate candidate : candidates()) {
            if (callsDone >= Math.max(1, maxCallsPerRun)) {
                break;
            }

            List<SiteDocument> dueSites = dueSites(sites, candidate)
                    .stream()
                    // Solis reste en single-station pour éviter les réponses globales / non scopées.
                    .limit(Math.max(1, Math.min(1, batchSize)))
                    .toList();

            if (dueSites.isEmpty()) {
                continue;
            }

            executeTask(candidate, dueSites);
            callsDone++;
        }

        return callsDone;
    }

    private List<HistoryCandidate> candidates() {
        LocalDate today = LocalDate.now(TUNIS_ZONE);
        YearMonth currentMonth = YearMonth.from(today);
        int currentYear = today.getYear();
        YearMonth firstMonth = currentMonth.minusMonths(Math.max(1, maxLookbackMonths) - 1L);

        List<HistoryCandidate> candidates = new ArrayList<>();

        // Priorité absolue: MONTH, car c'est utilisé par KPI jour, comparaison, alertes et performance.
        candidates.add(new HistoryCandidate(
                HistoryGranularity.MONTH,
                "month",
                currentMonth.toString(),
                true
        ));

        YearMonth monthCursor = currentMonth.minusMonths(1);
        while (!monthCursor.isBefore(firstMonth)) {
            candidates.add(new HistoryCandidate(
                    HistoryGranularity.MONTH,
                    "month",
                    monthCursor.toString(),
                    false
            ));
            monthCursor = monthCursor.minusMonths(1);
        }

        // YEAR Solis est optionnel. Désactivé par défaut pour ne pas consommer les appels avant MONTH.
        if (solisYearBackfillEnabled) {
            for (int year = currentYear; year >= firstMonth.getYear(); year--) {
                String bucket = String.valueOf(year);
                candidates.add(new HistoryCandidate(
                        HistoryGranularity.YEAR,
                        "year",
                        bucket,
                        year == currentYear
                ));
            }
        }

        return candidates;
    }

    private List<SiteDocument> dueSites(List<SiteDocument> sites, HistoryCandidate candidate) {
        Map<String, SiteHistoryDocument> existingBySiteCode = siteHistoryRepository
                .findAllByBrandAndGranularityAndBucket(
                        Brand.SOLIS,
                        candidate.granularity(),
                        candidate.bucket()
                )
                .stream()
                .filter(document -> document.getSiteCode() != null)
                .collect(Collectors.toMap(
                        SiteHistoryDocument::getSiteCode,
                        Function.identity(),
                        (left, right) -> left
                ));

        LocalDate today = LocalDate.now(TUNIS_ZONE);

        return sites.stream()
                .filter(site -> isBucketAfterSiteStart(site, candidate, today))
                .filter(site -> isDocumentDue(site, existingBySiteCode.get(site.getSiteCode()), candidate))
                .toList();
    }

    private boolean isBucketAfterSiteStart(
            SiteDocument site,
            HistoryCandidate candidate,
            LocalDate referenceDate
    ) {
        if (candidate.granularity() == HistoryGranularity.MONTH) {
            return HistoryBackfillCompletenessSupport.isMonthBucketAfterSiteStart(
                    site,
                    candidate.bucket(),
                    referenceDate,
                    maxLookbackMonths,
                    TUNIS_ZONE
            );
        }

        LocalDate firstDate = HistoryBackfillCompletenessSupport.firstHistoryDate(
                site,
                referenceDate,
                maxLookbackMonths,
                TUNIS_ZONE
        );

        LocalDate bucketEnd = bucketEndDate(candidate);
        return bucketEnd == null || !bucketEnd.isBefore(firstDate);
    }

    private boolean isDocumentDue(
            SiteDocument site,
            SiteHistoryDocument document,
            HistoryCandidate candidate
    ) {
        if (candidate.granularity() == HistoryGranularity.MONTH) {
            return isMonthBucketDueWithLog(site, document, candidate.bucket());
        }

        if (document == null) {
            return true;
        }

        boolean hasPoints = document.getPoints() != null && !document.getPoints().isEmpty();

        if (hasPoints) {
            if (!candidate.current()) {
                return false;
            }

            if (document.getLastSyncAt() == null) {
                return true;
            }

            long refreshMs = refreshIntervalMs(candidate.granularity());
            return document.getLastSyncAt().isBefore(Instant.now().minusMillis(refreshMs));
        }

        if (document.getLastSyncAt() == null) {
            return true;
        }

        Instant retryAfter = document.getLastSyncAt()
                .plusMillis(Math.max(60_000L, emptyHistoryRetryMs));

        return !Instant.now().isBefore(retryAfter);
    }

    private boolean isMonthBucketDueWithLog(
            SiteDocument site,
            SiteHistoryDocument document,
            String bucket
    ) {
        LocalDate today = LocalDate.now(TUNIS_ZONE);

        HistoryBackfillCompletenessSupport.MonthCompleteness completeness =
                HistoryBackfillCompletenessSupport.inspectMonth(
                        site,
                        document,
                        bucket,
                        today,
                        maxLookbackMonths,
                        TUNIS_ZONE
                );

        boolean due = HistoryBackfillCompletenessSupport.isMonthHistoryDue(
                site,
                document,
                bucket,
                today,
                maxLookbackMonths,
                emptyHistoryRetryMs,
                incompleteMonthRefreshMs,
                invalidValueMonthRefreshMs,
                currentMonthRefreshMs,
                monthFinalizationGraceHours,
                TUNIS_ZONE
        );

        if (due) {
            log.info(
                    "Solis MONTH bucket due site={} bucket={} status={} collected={}/{} present={} missingDays={} zeroOrNullDays={} missingOrInvalid={} expectedRange={}..{} lastSyncAt={} reason={}",
                    site.getSiteCode(),
                    bucket,
                    HistoryBackfillCompletenessSupport.monthStatusLabel(completeness),
                    completeness.collectedDays(),
                    completeness.expectedDays(),
                    completeness.presentDays(),
                    completeness.missingDays(),
                    completeness.invalidValueDays(),
                    completeness.missingOrInvalidDays(),
                    completeness.expectedStart(),
                    completeness.expectedEnd(),
                    document != null ? document.getLastSyncAt() : null,
                    monthDueReason(document, completeness, bucket)
            );
        } else if (completeness.expectedDays() > 0) {
            log.debug(
                    "Solis MONTH bucket OK site={} bucket={} collected={}/{} present={} missingDays={} zeroOrNullDays={} expectedRange={}..{} lastSyncAt={}",
                    site.getSiteCode(),
                    bucket,
                    completeness.collectedDays(),
                    completeness.expectedDays(),
                    completeness.presentDays(),
                    completeness.missingDays(),
                    completeness.invalidValueDays(),
                    completeness.expectedStart(),
                    completeness.expectedEnd(),
                    document != null ? document.getLastSyncAt() : null
            );
        }

        return due;
    }

    private String monthDueReason(
            SiteHistoryDocument document,
            HistoryBackfillCompletenessSupport.MonthCompleteness completeness,
            String bucket
    ) {
        if (document == null) {
            return "MISSING_BUCKET";
        }

        if (document.getPoints() == null || document.getPoints().isEmpty()) {
            return "EMPTY_BUCKET";
        }

        if (completeness.missingDays() > 0 && completeness.invalidValueDays() > 0) {
            return "MISSING_DAYS_AND_ZERO_OR_NULL_VALUES";
        }

        if (completeness.missingDays() > 0) {
            return "MISSING_DAYS";
        }

        if (completeness.invalidValueDays() > 0) {
            return "ZERO_OR_NULL_VALUE_DAYS";
        }

        if (YearMonth.now(TUNIS_ZONE).toString().equals(bucket)) {
            return "CURRENT_MONTH_REFRESH";
        }

        return "COLLECTED_TOO_EARLY_OR_STALE";
    }

    private long refreshIntervalMs(HistoryGranularity granularity) {
        return switch (granularity) {
            case MONTH -> Math.max(60_000L, currentMonthRefreshMs);
            case YEAR -> Math.max(60_000L, currentYearRefreshMs);
            default -> 86_400_000L;
        };
    }

    private LocalDate bucketEndDate(HistoryCandidate candidate) {
        try {
            return switch (candidate.granularity()) {
                case MONTH -> YearMonth.parse(candidate.bucket()).atEndOfMonth();
                case YEAR -> LocalDate.of(Integer.parseInt(candidate.bucket()), 12, 31);
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private void executeTask(HistoryCandidate candidate, List<SiteDocument> sites) {
        List<String> siteCodes = sites.stream()
                .map(SiteDocument::getSiteCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .limit(1)
                .toList();

        if (siteCodes.isEmpty()) {
            return;
        }

        Map<String, List<HistoryPointDto>> pointsBySite =
                solisMonitoringProvider.getBatchHistory(
                        siteCodes,
                        candidate.type(),
                        candidate.bucket()
                );

        for (SiteDocument site : sites) {
            List<HistoryPointDto> loadedPoints =
                    pointsBySite.getOrDefault(site.getSiteCode(), List.of());

            saveHistory(
                    site,
                    candidate.granularity(),
                    candidate.bucket(),
                    loadedPoints
            );
        }

        log.info(
                "Solis batch history loaded type={} bucket={} requestedSites={} returnedSites={}",
                candidate.type(),
                candidate.bucket(),
                sites.size(),
                pointsBySite.size()
        );
    }

    private void saveHistory(
            SiteDocument site,
            HistoryGranularity granularity,
            String bucket,
            List<HistoryPointDto> loadedPoints
    ) {
        Instant now = Instant.now();

        SiteHistoryDocument existing = siteHistoryRepository
                .findByBrandAndSiteCodeAndGranularityAndBucket(
                        Brand.SOLIS,
                        site.getSiteCode(),
                        granularity,
                        bucket
                )
                .orElse(null);

        List<HistoryPointDto> mergedPoints =
                HistoryBackfillCompletenessSupport.mergeHistoryPoints(
                        existing != null ? existing.getPoints() : List.of(),
                        loadedPoints,
                        granularity,
                        TUNIS_ZONE
                );

        List<HistoryPointDto> pointsToSave =
                clampMonthPoints(site, granularity, bucket, mergedPoints);

        SiteHistoryDocument document = siteMongoMapper.toSiteHistoryDocument(
                Brand.SOLIS,
                site.getSiteCode(),
                granularity,
                bucket,
                pointsToSave,
                existing,
                now
        );

        document.setProviderAccountKey(site.getProviderAccountKey());
        document.setProviderAccountGroup(site.getProviderAccountGroup());

        if (loadedPoints != null && !loadedPoints.isEmpty()) {
            HistoryCollectionStateSupport.markSuccessful(document, now);
        }
        siteHistoryRepository.save(document);

        logMonthSaveStatus(
                site,
                granularity,
                bucket,
                loadedPoints,
                pointsToSave,
                document
        );
    }

    private List<HistoryPointDto> clampMonthPoints(
            SiteDocument site,
            HistoryGranularity granularity,
            String bucket,
            List<HistoryPointDto> points
    ) {
        List<HistoryPointDto> safe = points != null ? points : List.of();

        if (granularity != HistoryGranularity.MONTH) {
            return safe;
        }

        return HistoryBackfillCompletenessSupport.clampMonthPointsToExpectedWindow(
                site,
                bucket,
                safe,
                LocalDate.now(TUNIS_ZONE),
                maxLookbackMonths,
                TUNIS_ZONE
        );
    }

    private void logMonthSaveStatus(
            SiteDocument site,
            HistoryGranularity granularity,
            String bucket,
            List<HistoryPointDto> loadedPoints,
            List<HistoryPointDto> savedPoints,
            SiteHistoryDocument document
    ) {
        if (granularity != HistoryGranularity.MONTH) {
            return;
        }

        HistoryBackfillCompletenessSupport.MonthCompleteness completeness =
                HistoryBackfillCompletenessSupport.inspectMonth(
                        site,
                        document,
                        bucket,
                        LocalDate.now(TUNIS_ZONE),
                        maxLookbackMonths,
                        TUNIS_ZONE
                );

        log.info(
                "Solis MONTH bucket saved site={} bucket={} loadedPoints={} savedPoints={} status={} collected={}/{} present={} missingDays={} zeroOrNullDays={} missingOrInvalid={} expectedRange={}..{}",
                site.getSiteCode(),
                bucket,
                loadedPoints == null ? 0 : loadedPoints.size(),
                savedPoints == null ? 0 : savedPoints.size(),
                HistoryBackfillCompletenessSupport.monthStatusLabel(completeness),
                completeness.collectedDays(),
                completeness.expectedDays(),
                completeness.presentDays(),
                completeness.missingDays(),
                completeness.invalidValueDays(),
                completeness.missingOrInvalidDays(),
                completeness.expectedStart(),
                completeness.expectedEnd()
        );
    }

    private record HistoryCandidate(
            HistoryGranularity granularity,
            String type,
            String bucket,
            boolean current
    ) {
    }
}
