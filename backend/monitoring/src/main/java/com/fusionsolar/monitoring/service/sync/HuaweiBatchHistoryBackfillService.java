package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.client.FusionSolarStationClient;
import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.dto.StationHistoryItemDto;
import com.fusionsolar.monitoring.dto.StationHistoryResponse;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.provider.mapper.SiteMongoMapper;
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory;
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory.HuaweiAccount;
import com.fusionsolar.monitoring.util.DateTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Backfill Huawei optimisé: appels par batch de 100 sites et séparation par groupe FusionSolar.
 *
 * Objectif:
 * - le front lit MongoDB;
 * - les historiques manquants sont remplis en arrière-plan;
 * - chaque groupe FusionSolar a son propre throttle, partagé par ses API Northbound;
 * - les buckets courants sont rafraîchis périodiquement pour que chaque nouveau jour arrive automatiquement.
 *
 * Règle importante:
 * - DAY = courbe horaire en kW;
 * - MONTH = production journalière kWh;
 * - les KPI jour, alertes, comparaison et indice performance utilisent MONTH;
 * - pour le mois courant, on sauvegarde seulement les jours terminés jusqu'à hier.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HuaweiBatchHistoryBackfillService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");

    private final SiteRepository siteRepository;
    private final SiteHistoryRepository siteHistoryRepository;
    private final SiteMongoMapper siteMongoMapper;
    private final FusionSolarStationClient stationClient;
    private final HuaweiAccountDirectory huaweiAccountDirectory;
    private final PersistentRateBudgetService persistentRateBudgetService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger accountCursor = new AtomicInteger(0);

    @Value("${app.history-batch-backfill.enabled:true}")
    private boolean enabled;

    @Value("${app.history-batch-backfill.max-huawei-calls-per-run:10}")
    private int maxHuaweiCallsPerRun;

    @Value("${app.history-batch-backfill.huawei-batch-size:100}")
    private int huaweiBatchSize;

    @Value("${app.history-batch-backfill.huawei-report-daily-call-limit:22}")
    private int huaweiReportDailyCallLimit;

    @Value("${app.history-batch-backfill.max-lookback-months:120}")
    private int maxLookbackMonths;

    /**
     * Quand true, le worker crée aussi un bucket DAY pour chaque date historique.
     * La courbe Day peut donc afficher n'importe quel jour déjà collecté en MongoDB,
     * pas seulement la journée courante.
     */
    @Value("${app.history-batch-backfill.historic-day-backfill-enabled:true}")
    private boolean historicDayBackfillEnabled;

    /**
     * 30 = seulement les 30 derniers jours en détail horaire DAY.
     * 0 ou valeur négative = full historique DAY, mais très long.
     */
    @Value("${app.history-batch-backfill.historic-day-lookback-days:30}")
    private int historicDayLookbackDays;

    @Value("${app.history-batch-backfill.empty-history-retry-ms:86400000}")
    private long emptyHistoryRetryMs;

    @Value("${app.history-batch-backfill.current-day-refresh-ms:900000}")
    private long currentDayRefreshMs;

    @Value("${app.history-batch-backfill.current-month-refresh-ms:21600000}")
    private long currentMonthRefreshMs;

    @Value("${app.history-batch-backfill.incomplete-month-refresh-ms:${app.history-backfill.incomplete-month-refresh-ms:21600000}}")
    private long incompleteMonthRefreshMs;

    @Value("${app.history-batch-backfill.invalid-value-month-refresh-ms:${app.history-backfill.invalid-value-month-refresh-ms:21600000}}")
    private long invalidValueMonthRefreshMs;

    @Value("${app.history-batch-backfill.month-finalization-grace-hours:${app.history-backfill.month-finalization-grace-hours:6}}")
    private int monthFinalizationGraceHours;

    @Value("${app.history-batch-backfill.current-year-refresh-ms:21600000}")
    private long currentYearRefreshMs;

    @Value("${app.history-batch-backfill.yearly-refresh-ms:86400000}")
    private long yearlyRefreshMs;

    @Scheduled(
            initialDelayString = "${app.history-batch-backfill.initial-delay-ms:30000}",
            fixedDelayString = "${app.history-batch-backfill.fixed-delay-ms:60000}"
    )
    public void backfillHuaweiHistoriesInBatches() {
        if (!enabled) {
            return;
        }

        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            int calls = runOnePass();
            if (calls > 0) {
                log.info("Huawei batch history pass finished: {} provider call(s)", calls);
            }
        } catch (Exception e) {
            log.warn("Huawei batch history pass failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private int runOnePass() {
        Map<String, List<HuaweiAccount>> accountsByGroup =
                huaweiAccountDirectory.getEnabledAccountsByGroup();

        if (accountsByGroup.isEmpty()) {
            log.warn("Huawei batch backfill skipped: no enabled Huawei API account configured");
            return 0;
        }

        List<SiteDocument> sites = siteRepository.findAllByBrandOrderBySiteNameAsc(Brand.HUAWEI)
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
        Set<String> groupsAlreadyVisited = new HashSet<>();

        while (callsDone < Math.max(1, maxHuaweiCallsPerRun)
                && groupsAlreadyVisited.size() < accountsByGroup.size()) {
            Optional<BatchTask> next = nextTask(sites, groupsAlreadyVisited);
            if (next.isEmpty()) {
                break;
            }

            String accountGroup = next.get().accountGroup();
            groupsAlreadyVisited.add(accountGroup);

            List<HuaweiAccount> groupAccounts = accountsByGroup.get(accountGroup);
            if (groupAccounts == null || groupAccounts.isEmpty()) {
                log.warn(
                        "Huawei batch history skipped for group={}: no enabled Northbound credential",
                        accountGroup
                );
                continue;
            }

            HistoryCandidate selectedCandidate = next.get().candidate();
            List<HuaweiAccount> eligibleAccounts = groupAccounts.stream().filter(a ->
                    persistentRateBudgetService.usedToday("HUAWEI", quotaScopeFor(a.key(), accountGroup),
                            huaweiEndpoint(selectedCandidate), TUNIS_ZONE) < Math.max(1, huaweiReportDailyCallLimit)).toList();
            Optional<HuaweiAccount> account = nextAvailableAccount(eligibleAccounts, huaweiEndpoint(selectedCandidate));
            if (account.isEmpty()) {
                log.info(
                        "Huawei batch history skipped this pass for group={}: all group accounts are cooling down after rate limit.",
                        accountGroup
                );
                continue;
            }

            try {
                executeTask(next.get(), account.get().key());
                callsDone++;
            } catch (com.fusionsolar.monitoring.service.huawei.HuaweiApiDeferredException deferred) {
                log.info("Huawei history deferred group={} endpoint={} retryAfterMs={}",
                        accountGroup, huaweiEndpoint(selectedCandidate), deferred.getRetryAfterMs());
                // Continue with the other enterprise instead of aborting the whole pass.
            }
        }

        return callsDone;
    }

    private Optional<BatchTask> nextTask(
            List<SiteDocument> sites,
            Set<String> excludedGroups
    ) {
        Map<String, List<SiteDocument>> sitesByGroup = sites.stream()
                .collect(Collectors.groupingBy(
                        this::accountGroupOf,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        for (HistoryCandidate candidate : candidates()) {
            for (Map.Entry<String, List<SiteDocument>> entry : sitesByGroup.entrySet()) {
                String accountGroup = entry.getKey();

                if (excludedGroups.contains(accountGroup)) {
                    continue;
                }

                int dailyLimit = dailyLimitForGroup(entry.getValue().size());

                if (!hasHuaweiDailyQuota(accountGroup, candidate, dailyLimit)) {
                    continue;
                }

                List<HuaweiAccount> candidateAccounts = huaweiAccountDirectory.getEnabledAccountsByGroup()
                        .getOrDefault(accountGroup, List.of()).stream().filter(a ->
                                persistentRateBudgetService.usedToday("HUAWEI", quotaScopeFor(a.key(), accountGroup),
                                        huaweiEndpoint(candidate), TUNIS_ZONE) < dailyLimit).toList();
                if (nextAvailableAccount(candidateAccounts, huaweiEndpoint(candidate)).isEmpty()) continue;

                List<SiteDocument> dueSites = dueSites(accountGroup, entry.getValue(), candidate)
                        .stream()
                        .limit(Math.max(1, Math.min(100, huaweiBatchSize)))
                        .toList();

                if (!dueSites.isEmpty()) {
                    return Optional.of(new BatchTask(candidate, accountGroup, dueSites));
                }
            }
        }

        return Optional.empty();
    }

    private List<HistoryCandidate> candidates() {
        LocalDate today = LocalDate.now(TUNIS_ZONE);
        LocalDate yesterday = today.minusDays(1);
        YearMonth currentMonth = YearMonth.from(today);
        int currentYear = today.getYear();
        YearMonth firstMonth = currentMonth.minusMonths(Math.max(1, maxLookbackMonths) - 1L);

        List<HistoryCandidate> candidates = new ArrayList<>();

        /*
         * 1) Buckets courants importants.
         * DAY courant = courbe puissance du jour.
         * MONTH courant = source kWh pour KPI jour / alertes / comparaison.
         */
        candidates.add(new HistoryCandidate(
                HistoryGranularity.DAY,
                "day",
                today.toString(),
                true
        ));
        candidates.add(new HistoryCandidate(
                HistoryGranularity.MONTH,
                "month",
                currentMonth.toString(),
                true
        ));

        /*
         * 2) MONTH historique en priorité.
         * C'est le plus important pour combler les KPI journaliers.
         */
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

        /*
         * 3) YEAR / YEARLY après MONTH.
         * Utile pour les courbes année, mais moins critique que MONTH.
         */
        for (int year = currentYear; year >= firstMonth.getYear(); year--) {
            String bucket = String.valueOf(year);
            candidates.add(new HistoryCandidate(
                    HistoryGranularity.YEAR,
                    "year",
                    bucket,
                    year == currentYear
            ));
        }

        candidates.add(new HistoryCandidate(
                HistoryGranularity.YEARLY,
                "yearly",
                "all",
                true
        ));

        /*
         * 4) DAY historique.
         * Très coûteux: un bucket = une seule date.
         */
        if (historicDayBackfillEnabled) {
            LocalDate firstDay;

            if (historicDayLookbackDays <= 0) {
                firstDay = firstMonth.atDay(1);
            } else {
                firstDay = yesterday.minusDays(Math.max(0, historicDayLookbackDays - 1L));
                LocalDate hardLimit = firstMonth.atDay(1);

                if (firstDay.isBefore(hardLimit)) {
                    firstDay = hardLimit;
                }
            }

            LocalDate dayCursor = yesterday;
            while (!dayCursor.isBefore(firstDay)) {
                candidates.add(new HistoryCandidate(
                        HistoryGranularity.DAY,
                        "day",
                        dayCursor.toString(),
                        false
                ));
                dayCursor = dayCursor.minusDays(1);
            }
        }

        return candidates;
    }

    private List<SiteDocument> dueSites(
            String accountGroup,
            List<SiteDocument> sites,
            HistoryCandidate candidate
    ) {
        Map<String, SiteHistoryDocument> existingBySiteCode = siteHistoryRepository
                .findAllByBrandAndProviderAccountGroupAndGranularityAndBucket(
                        Brand.HUAWEI,
                        accountGroup,
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
            LocalDate today
    ) {
        if (candidate.granularity() == HistoryGranularity.YEARLY) {
            return true;
        }

        if (candidate.granularity() == HistoryGranularity.MONTH) {
            return HistoryBackfillCompletenessSupport.isMonthBucketAfterSiteStart(
                    site,
                    candidate.bucket(),
                    today,
                    maxLookbackMonths,
                    TUNIS_ZONE
            );
        }

        LocalDate firstDate = HistoryBackfillCompletenessSupport.firstHistoryDate(
                site,
                today,
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
                    "Huawei MONTH bucket due site={} group={} bucket={} status={} collected={}/{} present={} missingDays={} zeroOrNullDays={} missingOrInvalid={} expectedRange={}..{} lastSyncAt={} reason={}",
                    site.getSiteCode(),
                    accountGroupOf(site),
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
                    "Huawei MONTH bucket OK site={} group={} bucket={} collected={}/{} present={} missingDays={} zeroOrNullDays={} expectedRange={}..{} lastSyncAt={}",
                    site.getSiteCode(),
                    accountGroupOf(site),
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
            case DAY -> Math.max(60_000L, currentDayRefreshMs);
            case MONTH -> Math.max(60_000L, currentMonthRefreshMs);
            case YEAR -> Math.max(60_000L, currentYearRefreshMs);
            case YEARLY -> Math.max(60_000L, yearlyRefreshMs);
        };
    }

    private LocalDate bucketEndDate(HistoryCandidate candidate) {
        try {
            return switch (candidate.granularity()) {
                case DAY -> LocalDate.parse(candidate.bucket());
                case MONTH -> YearMonth.parse(candidate.bucket()).atEndOfMonth();
                case YEAR -> LocalDate.of(Integer.parseInt(candidate.bucket()), 12, 31);
                case YEARLY -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private void executeTask(BatchTask task, String accountKey) {
        List<String> siteCodes = task.sites()
                .stream()
                .map(SiteDocument::getSiteCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();

        if (siteCodes.isEmpty()) {
            return;
        }

        String stationCodes = String.join(",", siteCodes);
        StationHistoryResponse response;

        HistoryCandidate candidate = task.candidate();

        if (candidate.granularity() == HistoryGranularity.DAY) {
            response = stationClient.getStationHourlyHistory(
                    accountKey,
                    stationCodes,
                    DateTimeUtils.toDayTimestamp(LocalDate.parse(candidate.bucket()))
            );
        } else if (candidate.granularity() == HistoryGranularity.MONTH) {
            response = stationClient.getStationDailyHistory(
                    accountKey,
                    stationCodes,
                    DateTimeUtils.toMonthTimestamp(YearMonth.parse(candidate.bucket()))
            );
        } else if (candidate.granularity() == HistoryGranularity.YEAR) {
            response = stationClient.getStationMonthlyHistory(
                    accountKey,
                    stationCodes,
                    DateTimeUtils.toYearTimestamp(Integer.parseInt(candidate.bucket()))
            );
        } else {
            response = stationClient.getStationYearlyHistory(
                    accountKey,
                    stationCodes,
                    DateTimeUtils.toYearTimestamp(LocalDate.now(TUNIS_ZONE).getYear())
            );
        }

        if (response == null || response.getData() == null) {
            throw new IllegalStateException(
                    "Huawei history provider returned no payload for type="
                            + candidate.type()
                            + " bucket="
                            + candidate.bucket()
                            + ". Bucket is not marked as collected."
            );
        }

        Map<String, List<HistoryPointDto>> pointsBySiteCode = mapHistoryBySiteCode(response);
        Set<String> returnedSiteCodes = pointsBySiteCode.keySet();
        long returnedPoints = pointsBySiteCode.values().stream().mapToLong(List::size).sum();

        for (SiteDocument site : task.sites()) {
            List<HistoryPointDto> loadedPoints =
                    pointsBySiteCode.getOrDefault(site.getSiteCode(), List.of());

            saveHistory(
                    site,
                    candidate.granularity(),
                    candidate.bucket(),
                    loadedPoints
            );
        }

        log.info(
                "Huawei batch history loaded group={} account={} type={} bucket={} requestedSites={} returnedSites={} returnedPoints={}",
                task.accountGroup(),
                accountKey,
                candidate.type(),
                candidate.bucket(),
                task.sites().size(),
                returnedSiteCodes.size(),
                returnedPoints
        );
    }

    private Map<String, List<HistoryPointDto>> mapHistoryBySiteCode(
            StationHistoryResponse response
    ) {
        Map<String, List<HistoryPointDto>> result = new LinkedHashMap<>();

        if (response == null || response.getData() == null) {
            return result;
        }

        for (StationHistoryItemDto item : response.getData()) {
            if (item == null
                    || item.getStationCode() == null
                    || item.getStationCode().isBlank()
                    || item.getCollectTime() == null) {
                continue;
            }

            Double value = extractDouble(
                    item.getDataItemMap() != null
                            ? item.getDataItemMap().get("inverter_power")
                            : null
            );

            result.computeIfAbsent(item.getStationCode(), key -> new ArrayList<>())
                    .add(new HistoryPointDto(item.getCollectTime(), value));
        }

        result.replaceAll((siteCode, points) -> points.stream()
                .sorted(Comparator.comparing(
                        HistoryPointDto::getCollectTime,
                        Comparator.nullsLast(Long::compareTo)
                ))
                .toList());

        return result;
    }

    private void saveHistory(
            SiteDocument site,
            HistoryGranularity granularity,
            String bucket,
            List<HistoryPointDto> loadedPoints
    ) {
        Instant now = Instant.now();
        String accountGroup = accountGroupOf(site);

        SiteHistoryDocument existing = siteHistoryRepository
                .findByBrandAndProviderAccountGroupAndSiteCodeAndGranularityAndBucket(
                        Brand.HUAWEI,
                        accountGroup,
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
                Brand.HUAWEI,
                site.getSiteCode(),
                granularity,
                bucket,
                pointsToSave,
                existing,
                now
        );

        document.setProviderAccountKey(accountKeyOf(site));
        document.setProviderAccountGroup(accountGroup);

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
                "Huawei MONTH bucket saved site={} group={} bucket={} loadedPoints={} savedPoints={} status={} collected={}/{} present={} missingDays={} zeroOrNullDays={} missingOrInvalid={} expectedRange={}..{}",
                site.getSiteCode(),
                accountGroupOf(site),
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

    private Double extractDouble(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number number) {
            return number.doubleValue();
        }

        try {
            String text = value.toString().trim();

            if (text.isBlank()
                    || "null".equalsIgnoreCase(text)
                    || "N/A".equalsIgnoreCase(text)) {
                return null;
            }

            return Double.parseDouble(text.replace("%", "").replace(",", "."));
        } catch (Exception e) {
            return null;
        }
    }

    private String accountKeyOf(SiteDocument site) {
        if (site != null
                && site.getProviderAccountKey() != null
                && !site.getProviderAccountKey().isBlank()) {
            return site.getProviderAccountKey().trim();
        }

        return huaweiAccountDirectory.primaryAccountKey();
    }

    private String accountGroupOf(SiteDocument site) {
        if (site != null
                && site.getProviderAccountGroup() != null
                && !site.getProviderAccountGroup().isBlank()) {
            return site.getProviderAccountGroup().trim();
        }

        if (site != null
                && site.getProviderAccountKey() != null
                && !site.getProviderAccountKey().isBlank()) {
            return site.getProviderAccountKey().trim();
        }

        return huaweiAccountDirectory.primaryAccountGroup();
    }

    private int dailyLimitForGroup(int sitesCount) {
        int officialLikeLimit = ((Math.max(1, sitesCount) + 99) / 100) + 24;

        /*
         * Marge de sécurité pour éviter les 407/429.
         * Exemple: limite officielle 25/26 => limite conseillée 22.
         */
        int safeLimit = Math.max(1, officialLikeLimit - 3);

        return Math.max(1, Math.min(safeLimit, huaweiReportDailyCallLimit));
    }

    private boolean hasHuaweiDailyQuota(
            String accountGroup,
            HistoryCandidate candidate,
            int limit
    ) {
        return huaweiAccountDirectory.getEnabledAccountsByGroup().getOrDefault(accountGroup, List.of()).stream()
                .anyMatch(account -> persistentRateBudgetService.usedToday("HUAWEI",
                        quotaScopeFor(account.key(), accountGroup), huaweiEndpoint(candidate), TUNIS_ZONE) < Math.max(1, limit));
    }

    private boolean reserveHuaweiDailyCall(
            String accountGroup,
            HistoryCandidate candidate,
            int limit
    ) {
        return persistentRateBudgetService.tryConsumeDaily(
                "HUAWEI",
                safeGroup(accountGroup),
                huaweiEndpoint(candidate),
                limit,
                TUNIS_ZONE
        );
    }

    private String huaweiEndpoint(HistoryCandidate candidate) {
        return switch (candidate.granularity()) {
            case DAY -> "/thirdData/getKpiStationHour";
            case MONTH -> "/thirdData/getKpiStationDay";
            case YEAR -> "/thirdData/getKpiStationMonth";
            case YEARLY -> "/thirdData/getKpiStationYear";
        };
    }

    @Value("${app.huawei-api.quota-scope:group}")
    private String quotaScope;
    private String quotaScopeFor(String account, String group) {
        return "account".equalsIgnoreCase(quotaScope) ? account : safeGroup(group);
    }
    private String safeGroup(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }

    private Optional<HuaweiAccount> nextAvailableAccount(List<HuaweiAccount> accounts, String endpoint) {
        if (accounts == null || accounts.isEmpty()) return Optional.empty();
        // Prefer a cached session, avoid pointless logins on api-02..05 in shared mode.
        List<HuaweiAccount> ready = accounts.stream()
                .filter(a -> stationClient.huaweiWaitMsBeforeCall(a.key(), endpoint) <= 0L).toList();
        if (ready.isEmpty()) return Optional.empty();
        if (!"account".equalsIgnoreCase(quotaScope)) {
            return ready.stream().filter(a -> stationClient.hasUsableHuaweiSession(a.key())).findFirst()
                    .or(() -> Optional.of(ready.get(0)));
        }
        return Optional.of(ready.get(Math.floorMod(accountCursor.getAndIncrement(), ready.size())));
    }

    private record HistoryCandidate(
            HistoryGranularity granularity,
            String type,
            String bucket,
            boolean current
    ) {
    }

    private record BatchTask(
            HistoryCandidate candidate,
            String accountGroup,
            List<SiteDocument> sites
    ) {
    }
}
