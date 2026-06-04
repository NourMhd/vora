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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Backfill Solis optimisé pour les buckets MONTH/YEAR.
 *
 * Les endpoints Solis stationMonthEnergyList/stationYearEnergyList acceptent une liste idList
 * de plusieurs stations. On utilise donc ce worker en batch pour remplir rapidement les courbes
 * mois/année, puis SiteHistoryBackfillService garde le DAY en site par site.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "providers.solis", name = "enabled", havingValue = "true")
public class SolisBatchHistoryBackfillService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final DateTimeFormatter DISPLAY_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SiteRepository siteRepository;
    private final SiteHistoryRepository siteHistoryRepository;
    private final SiteMongoMapper siteMongoMapper;
    private final SolisMonitoringProvider solisMonitoringProvider;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${app.solis-batch-backfill.enabled:true}")
    private boolean enabled;

    @Value("${app.solis-batch-backfill.batch-size:100}")
    private int batchSize;

    @Value("${app.solis-batch-backfill.max-calls-per-run:2}")
    private int maxCallsPerRun;

    @Value("${app.solis-batch-backfill.max-lookback-months:${app.history-backfill.max-lookback-months:120}}")
    private int maxLookbackMonths;

    @Value("${app.solis-batch-backfill.empty-history-retry-ms:${app.history-backfill.empty-history-retry-ms:86400000}}")
    private long emptyHistoryRetryMs;

    @Value("${app.solis-batch-backfill.current-month-refresh-ms:${app.history-backfill.current-month-refresh-ms:3600000}}")
    private long currentMonthRefreshMs;

    @Value("${app.solis-batch-backfill.current-year-refresh-ms:${app.history-backfill.current-year-refresh-ms:21600000}}")
    private long currentYearRefreshMs;

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
                    .limit(Math.max(1, Math.min(100, batchSize)))
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

        // Buckets courants en premier.
        candidates.add(new HistoryCandidate(HistoryGranularity.MONTH, "month", currentMonth.toString(), true));
        candidates.add(new HistoryCandidate(HistoryGranularity.YEAR, "year", String.valueOf(currentYear), true));

        // YEAR historique: rentable pour la courbe année.
        for (int year = currentYear; year >= firstMonth.getYear(); year--) {
            String bucket = String.valueOf(year);
            candidates.add(new HistoryCandidate(
                    HistoryGranularity.YEAR,
                    "year",
                    bucket,
                    bucket.equals(String.valueOf(currentYear))
            ));
        }

        // MONTH historique: rentable pour la courbe mois.
        YearMonth cursor = currentMonth;
        while (!cursor.isBefore(firstMonth)) {
            String bucket = cursor.toString();
            candidates.add(new HistoryCandidate(
                    HistoryGranularity.MONTH,
                    "month",
                    bucket,
                    bucket.equals(currentMonth.toString())
            ));
            cursor = cursor.minusMonths(1);
        }

        return candidates;
    }

    private List<SiteDocument> dueSites(List<SiteDocument> sites, HistoryCandidate candidate) {
        Map<String, SiteHistoryDocument> existingBySiteCode = siteHistoryRepository
                .findAllByBrandAndGranularityAndBucket(Brand.SOLIS, candidate.granularity(), candidate.bucket())
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
                .filter(site -> isDocumentDue(existingBySiteCode.get(site.getSiteCode()), candidate))
                .toList();
    }

    private boolean isBucketAfterSiteStart(SiteDocument site, HistoryCandidate candidate, LocalDate referenceDate) {
        LocalDate firstDate = firstHistoryDate(site, referenceDate);
        LocalDate bucketEnd = bucketEndDate(candidate);
        return bucketEnd == null || !bucketEnd.isBefore(firstDate);
    }

    private boolean isDocumentDue(SiteHistoryDocument document, HistoryCandidate candidate) {
        if (document == null) {
            return true;
        }

        boolean hasPoints = document.getPoints() != null && !document.getPoints().isEmpty();
        if (hasPoints) {
            if (!candidate.current()) {
                return false;
            }

            long refreshMs = refreshIntervalMs(candidate.granularity());
            if (document.getLastSyncAt() == null) {
                return true;
            }

            return document.getLastSyncAt().isBefore(Instant.now().minusMillis(refreshMs));
        }

        if (document.getLastSyncAt() == null) {
            return true;
        }

        Instant retryAfter = document.getLastSyncAt().plusMillis(Math.max(60_000L, emptyHistoryRetryMs));
        return !Instant.now().isBefore(retryAfter);
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
                .limit(100)
                .toList();

        if (siteCodes.isEmpty()) {
            return;
        }

        Map<String, List<HistoryPointDto>> pointsBySite = solisMonitoringProvider.getBatchHistory(
                siteCodes,
                candidate.type(),
                candidate.bucket()
        );

        for (SiteDocument site : sites) {
            List<HistoryPointDto> points = pointsBySite.get(site.getSiteCode());
            saveHistory(site, candidate.granularity(), candidate.bucket(), points != null ? points : List.of());
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

        List<HistoryPointDto> pointsToSave = loadedPoints != null ? loadedPoints : List.of();
        if (pointsToSave.isEmpty() && existing != null && existing.getPoints() != null && !existing.getPoints().isEmpty()) {
            pointsToSave = existing.getPoints();
        }

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

        siteHistoryRepository.save(document);
    }

    private LocalDate firstHistoryDate(SiteDocument site, LocalDate referenceDate) {
        LocalDate maxLookbackStart = YearMonth.from(referenceDate)
                .minusMonths(Math.max(1, maxLookbackMonths) - 1L)
                .atDay(1);

        LocalDate productionStartDate = site.getSwapDate() != null
                ? site.getSwapDate()
                : parseDate(site.getGridConnectionDate());

        if (productionStartDate == null) {
            return maxLookbackStart;
        }

        if (productionStartDate.isBefore(maxLookbackStart)) {
            return maxLookbackStart;
        }

        return productionStartDate;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String value = raw.trim();

        if (value.matches("^\\d{10,13}$")) {
            long epoch = Long.parseLong(value);
            long epochMillis = epoch < 1_000_000_000_000L ? epoch * 1000 : epoch;
            return Instant.ofEpochMilli(epochMillis).atZone(TUNIS_ZONE).toLocalDate();
        }

        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            // Try display format below.
        }

        try {
            return LocalDate.parse(value, DISPLAY_DATE_FORMATTER);
        } catch (DateTimeParseException ignored) {
            // Try text cleanup below.
        }

        String firstTenChars = value.length() >= 10 ? value.substring(0, 10) : value;

        try {
            return LocalDate.parse(firstTenChars);
        } catch (DateTimeParseException ignored) {
            // Try slash format below.
        }

        try {
            return LocalDate.parse(firstTenChars, DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        } catch (DateTimeParseException ignored) {
            // Try year-month below.
        }

        String normalized = value.toUpperCase(Locale.ROOT).replace('/', '-');
        if (normalized.matches("^\\d{4}-\\d{2}$")) {
            return YearMonth.parse(normalized).atDay(1);
        }

        return null;
    }

    private record HistoryCandidate(
            HistoryGranularity granularity,
            String type,
            String bucket,
            boolean current
    ) {
    }
}
