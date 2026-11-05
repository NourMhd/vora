package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Remplit progressivement MongoDB avec les historiques absents.
 *
 * Principe:
 * - le frontend lit seulement MongoDB;
 * - ce worker remplit les buckets absents;
 * - Huawei est traité par HuaweiBatchHistoryBackfillService;
 * - Solis MONTH/YEAR peut être traité par SolisBatchHistoryBackfillService;
 * - ce worker reste responsable de Growatt et du DAY Solis/Growatt.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteHistoryBackfillService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final DateTimeFormatter DISPLAY_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SiteRepository siteRepository;
    private final SiteHistoryRepository siteHistoryRepository;
    private final HuaweiHistorySyncService huaweiHistorySyncService;
    private final ObjectProvider<SolisHistorySyncService> solisHistorySyncServiceProvider;
    private final ObjectProvider<GrowattHistorySyncService> growattHistorySyncServiceProvider;

    private final java.util.Map<Brand, AtomicBoolean> runningByBrand = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Brand, AtomicInteger> cursors = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger priorityTurn = new AtomicInteger();

    @Value("${app.history-backfill.enabled:true}")
    private boolean enabled;

    @Value("${app.history-backfill.max-provider-calls-per-run:1}")
    private int maxProviderCallsPerRun;

    @Value("${app.history-backfill.delay-between-provider-calls-ms:30000}")
    private long delayBetweenProviderCallsMs;

    @Value("${app.history-backfill.empty-history-retry-ms:86400000}")
    private long emptyHistoryRetryMs;

    @Value("${app.history-backfill.empty-history-max-retry-ms:604800000}")
    private long emptyHistoryMaxRetryMs;

    @Value("${app.history-backfill.empty-confirmed-after-attempts:3}")
    private int emptyConfirmedAfterAttempts;

    @Value("${app.history-backfill.max-lookback-months:120}")
    private int maxLookbackMonths;

    @Value("${app.history-backfill.current-day-refresh-ms:900000}")
    private long currentDayRefreshMs;

    @Value("${app.history-backfill.current-month-refresh-ms:21600000}")
    private long currentMonthRefreshMs;

    @Value("${app.history-backfill.incomplete-month-refresh-ms:21600000}")
    private long incompleteMonthRefreshMs;

    @Value("${app.history-backfill.month-finalization-grace-hours:6}")
    private int monthFinalizationGraceHours;

    @Value("${app.history-backfill.current-year-refresh-ms:21600000}")
    private long currentYearRefreshMs;

    @Value("${app.history-backfill.historic-day-backfill-enabled:true}")
    private boolean historicDayBackfillEnabled;

    /**
     * 30 = seulement les 30 derniers jours en détail DAY.
     * 0 ou valeur négative = full historique DAY, mais très long.
     */
    @Value("${app.history-backfill.historic-day-lookback-days:30}")
    private int historicDayLookbackDays;

    @Value("${app.history-backfill.solis-year-backfill-enabled:false}")
    private boolean solisYearBackfillEnabled;

    /**
     * Quand true, Solis MONTH/YEAR est traité par SolisBatchHistoryBackfillService.
     * Ce worker garde donc surtout Solis DAY et Growatt.
     */
    @Value("${app.solis-batch-backfill.enabled:true}")
    private boolean solisBatchBackfillEnabled;

    @Scheduled(initialDelayString = "${app.history-backfill.initial-delay-ms:120000}",
            fixedDelayString = "${app.history-backfill.fixed-delay-ms:1000}", scheduler = "solisHistoryScheduler")
    public void backfillMissingHistories() { backfillBrand(Brand.SOLIS); }

    @Scheduled(initialDelayString = "${app.history-backfill.initial-delay-ms:120000}",
            fixedDelayString = "${app.history-backfill.fixed-delay-ms:1000}", scheduler = "growattHistoryScheduler")
    public void backfillGrowattHistories() { backfillBrand(Brand.GROWATT); }

    private void backfillBrand(Brand brand) {
        AtomicBoolean running = runningByBrand.computeIfAbsent(brand, key -> new AtomicBoolean());
        if (!enabled || !running.compareAndSet(false, true)) return;
        try { runOnePass(brand); }
        finally { running.set(false); }
    }

    private int runOnePass(Brand brand) {
        AtomicInteger nextSiteIndex = cursors.computeIfAbsent(brand, key -> new AtomicInteger());
        List<SiteDocument> sites = siteRepository.findAllByOrderBySiteNameAsc()
                .stream()
                .filter(this::isEligibleSite)
                .filter(site -> site.getBrand() == brand)
                .sorted(Comparator.comparing(
                        site -> site.getSiteName() == null ? "" : site.getSiteName(),
                        String.CASE_INSENSITIVE_ORDER
                ))
                .toList();

        if (sites.isEmpty()) {
            nextSiteIndex.set(0);
            return 0;
        }

        int callsDone = 0;
        int startIndex = Math.floorMod(nextSiteIndex.get(), sites.size());

        for (int visited = 0; visited < sites.size() && callsDone < maxProviderCallsPerRun; visited++) {
            int index = (startIndex + visited) % sites.size();
            SiteDocument site = sites.get(index);

            Optional<BackfillTask> task = nextMissingTask(site);

            if (task.isEmpty()) {
                continue;
            }

            boolean providerCallAttempted = executeTask(task.get());
            nextSiteIndex.set((index + 1) % sites.size());

            if (providerCallAttempted) {
                callsDone++;
                sleepQuietly(delayBetweenProviderCallsMs);
            }
        }

        return callsDone;
    }

    private boolean isEligibleSite(SiteDocument site) {
        return site != null
                && site.getBrand() != null
                && site.getSiteCode() != null
                && !site.getSiteCode().isBlank()
                && !Boolean.FALSE.equals(site.getActive())
                && (site.getBrand() == Brand.SOLIS || site.getBrand() == Brand.GROWATT);
    }

    private Optional<BackfillTask> nextMissingTask(SiteDocument site) {
        LocalDate today = LocalDate.now(TUNIS_ZONE);
        LocalDate yesterday = today.minusDays(1);
        LocalDate firstDate = firstHistoryDate(site, today);

        if (firstDate.isAfter(today)) {
            return Optional.empty();
        }

        YearMonth firstMonth = YearMonth.from(firstDate);
        YearMonth lastMonth = YearMonth.from(today);

        /*
         * 1) DAY courant prioritaire.
         * Objectif: courbe d'aujourd'hui à jour sans appel fournisseur depuis l'UI.
         */
        String todayBucket = today.toString();

        boolean recentFirst = Math.floorMod(priorityTurn.getAndIncrement(), 2) == 0;
        boolean todayDue = isMissingOrExpiredEmpty(site, HistoryGranularity.DAY, todayBucket);
        if (recentFirst && todayDue) {
            return Optional.of(new BackfillTask(site, "day", todayBucket, HistoryGranularity.DAY));
        }

        boolean solisMonthYearHandledByBatch =
                site.getBrand() == Brand.SOLIS && solisBatchBackfillEnabled;

        if (!solisMonthYearHandledByBatch) {
            /*
             * 2) MONTH avant YEAR pour Solis.
             * Règle métier: les KPI jour / comparaison / alertes / IP utilisent MONTH en kWh.
             * YEAR reste utile pour les courbes annuelles, mais il ne doit pas retarder
             * la réparation des productions journalières.
             */
            if (site.getBrand() == Brand.SOLIS) {
                Optional<BackfillTask> monthTask = nextMonthBackfillTask(site, firstMonth, lastMonth);
                if (monthTask.isPresent()) {
                    return monthTask;
                }

                if (solisYearBackfillEnabled) {
                    Optional<BackfillTask> yearTask = nextYearBackfillTask(site, firstMonth, lastMonth);
                    if (yearTask.isPresent()) {
                        return yearTask;
                    }
                }
            } else {
                Optional<BackfillTask> yearTask = nextYearBackfillTask(site, firstMonth, lastMonth);
                if (yearTask.isPresent()) {
                    return yearTask;
                }

                Optional<BackfillTask> monthTask = nextMonthBackfillTask(site, firstMonth, lastMonth);
                if (monthTask.isPresent()) {
                    return monthTask;
                }
            }
        }

        if (todayDue) return Optional.of(new BackfillTask(site, "day", todayBucket, HistoryGranularity.DAY));

        /*
         * 4) DAY historique.
         * Très coûteux: un jour = un appel par site.
         */
        if (historicDayBackfillEnabled) {
            LocalDate dayLimit;

            if (historicDayLookbackDays <= 0) {
                dayLimit = firstDate;
            } else {
                dayLimit = yesterday.minusDays(Math.max(0, historicDayLookbackDays - 1L));

                if (dayLimit.isBefore(firstDate)) {
                    dayLimit = firstDate;
                }
            }

            LocalDate dayCursor = yesterday;

            while (!dayCursor.isBefore(dayLimit)) {
                String bucket = dayCursor.toString();

                if (isMissingOrExpiredEmpty(site, HistoryGranularity.DAY, bucket)) {
                    return Optional.of(new BackfillTask(site, "day", bucket, HistoryGranularity.DAY));
                }

                dayCursor = dayCursor.minusDays(1);
            }
        }

        return Optional.empty();
    }

    private Optional<BackfillTask> nextYearBackfillTask(
            SiteDocument site,
            YearMonth firstMonth,
            YearMonth lastMonth
    ) {
        for (int year = lastMonth.getYear(); year >= firstMonth.getYear(); year--) {
            String bucket = String.valueOf(year);

            if (isMissingOrExpiredEmpty(site, HistoryGranularity.YEAR, bucket)) {
                return Optional.of(new BackfillTask(site, "year", bucket, HistoryGranularity.YEAR));
            }
        }

        return Optional.empty();
    }

    private Optional<BackfillTask> nextMonthBackfillTask(
            SiteDocument site,
            YearMonth firstMonth,
            YearMonth lastMonth
    ) {
        YearMonth cursor = lastMonth;

        while (!cursor.isBefore(firstMonth)) {
            String bucket = cursor.toString();

            if (isMissingOrExpiredEmpty(site, HistoryGranularity.MONTH, bucket)) {
                return Optional.of(new BackfillTask(site, "month", bucket, HistoryGranularity.MONTH));
            }

            cursor = cursor.minusMonths(1);
        }

        return Optional.empty();
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

    private boolean isMissingOrExpiredEmpty(
            SiteDocument site,
            HistoryGranularity granularity,
            String bucket
    ) {
        SiteHistoryDocument document = siteHistoryRepository
                .findByBrandAndSiteCodeAndGranularityAndBucket(
                        site.getBrand(),
                        site.getSiteCode(),
                        granularity,
                        bucket
                )
                .orElse(null);

        if (granularity == HistoryGranularity.MONTH) {
            return isMonthBucketDueWithLog(site, document, bucket);
        }

        if (document == null) {
            return true;
        }

        if (document.getPoints() != null && !document.getPoints().isEmpty()) {
            if (site.getBrand() == Brand.SOLIS && looksLikeSolisUnscopedHistory(document.getPoints())) {
                return true;
            }

            return isCurrentRefreshDue(document, granularity, bucket);
        }

        if (document.getLastSyncAt() == null) {
            return true;
        }

        Instant retryAfter = document.getNextRetryAt() != null
                ? document.getNextRetryAt()
                : document.getLastSyncAt().plusMillis(Math.max(60_000L, emptyHistoryRetryMs));

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
                incompleteMonthRefreshMs,
                currentMonthRefreshMs,
                monthFinalizationGraceHours,
                TUNIS_ZONE
        );

        if (due) {
            log.info(
                    "History MONTH bucket due brand={} site={} bucket={} status={} collected={}/{} present={} missingDays={} zeroOrNullDays={} missingOrInvalid={} expectedRange={}..{} lastSyncAt={} reason={}",
                    site.getBrand(),
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
                    "History MONTH bucket OK brand={} site={} bucket={} collected={}/{} expectedRange={}..{} lastSyncAt={}",
                    site.getBrand(),
                    site.getSiteCode(),
                    bucket,
                    completeness.collectedDays(),
                    completeness.expectedDays(),
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

    private boolean looksLikeSolisUnscopedHistory(List<HistoryPointDto> points) {
        if (points == null || points.size() <= 1) {
            return false;
        }

        long validTimes = points.stream()
                .map(HistoryPointDto::getCollectTime)
                .filter(java.util.Objects::nonNull)
                .count();

        long distinctTimes = points.stream()
                .map(HistoryPointDto::getCollectTime)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();

        return validTimes > 1 && distinctTimes <= 1;
    }

    private boolean isCurrentRefreshDue(
            SiteHistoryDocument document,
            HistoryGranularity granularity,
            String bucket
    ) {
        if (document == null || document.getLastSyncAt() == null) {
            return true;
        }

        long refreshMs;

        if (granularity == HistoryGranularity.DAY
                && LocalDate.now(TUNIS_ZONE).toString().equals(bucket)) {
            refreshMs = Math.max(60_000L, currentDayRefreshMs);
        } else if (granularity == HistoryGranularity.MONTH
                && YearMonth.now(TUNIS_ZONE).toString().equals(bucket)) {
            refreshMs = Math.max(60_000L, currentMonthRefreshMs);
        } else if (granularity == HistoryGranularity.YEAR
                && String.valueOf(LocalDate.now(TUNIS_ZONE).getYear()).equals(bucket)) {
            refreshMs = Math.max(60_000L, currentYearRefreshMs);
        } else {
            return false;
        }

        return document.getLastSyncAt().isBefore(Instant.now().minusMillis(refreshMs));
    }

    private boolean executeTask(BackfillTask task) {
        try {
            List<HistoryPointDto> points = switch (task.site().getBrand()) {
                case HUAWEI -> huaweiHistorySyncService.getHistory(
                        task.site(),
                        task.type(),
                        task.bucket(),
                        true
                );

                case SOLIS -> {
                    SolisHistorySyncService solisHistorySyncService =
                            solisHistorySyncServiceProvider.getIfAvailable();

                    if (solisHistorySyncService == null) {
                        log.debug("Solis history backfill skipped because Solis provider is disabled");
                        yield List.of();
                    }

                    yield solisHistorySyncService.getHistory(
                            task.site(),
                            task.type(),
                            task.bucket(),
                            true
                    );
                }

                case GROWATT -> {
                    GrowattHistorySyncService growattHistorySyncService =
                            growattHistorySyncServiceProvider.getIfAvailable();

                    if (growattHistorySyncService == null) {
                        log.debug("Growatt history backfill skipped because Growatt provider is disabled");
                        yield List.of();
                    }

                    yield growattHistorySyncService.getHistory(
                            task.site(),
                            task.type(),
                            task.bucket(),
                            true
                    );
                }

                default -> List.of();
            };

            if (points == null || points.isEmpty()) {
                saveEmptyAttempt(task);
            }

            log.info(
                    "History backfill loaded brand={} site={} type={} bucket={} points={}",
                    task.site().getBrand(),
                    task.site().getSiteCode(),
                    task.type(),
                    task.bucket(),
                    points == null ? 0 : points.size()
            );

            return true;
        } catch (Exception e) {
            /*
             * Pour les vraies erreurs fournisseur temporaires:
             * - auth/relogin
             * - 429/high frequency
             * - réseau
             * on ne marque pas le bucket comme synchronisé.
             *
             * Pour l'ancien bug Growatt "error_time format is incorrect",
             * on marque seulement une tentative vide pour éviter un spam infini.
             * Après correction de GrowattMonitoringProvider, cette erreur ne doit plus apparaître.
             */
            if (isGrowattTimeFormatError(task, e)) {
                saveEmptyAttempt(task);

                log.warn(
                        "History backfill failed brand={} site={} type={} bucket={}. Marked as empty attempt to avoid retry spam. Cause={}",
                        task.site().getBrand(),
                        task.site().getSiteCode(),
                        task.type(),
                        task.bucket(),
                        e.getMessage()
                );

                return true;
            }

            log.warn(
                    "History backfill failed brand={} site={} type={} bucket={}. Bucket not marked, retry later. Cause={}",
                    task.site().getBrand(),
                    task.site().getSiteCode(),
                    task.type(),
                    task.bucket(),
                    e.getMessage()
            );

            return true;
        }
    }

    private boolean isGrowattTimeFormatError(BackfillTask task, Exception e) {
        if (task == null || task.site() == null || task.site().getBrand() != Brand.GROWATT || e == null) {
            return false;
        }

        String message = e.getMessage();

        if (message == null) {
            return false;
        }

        String lower = message.toLowerCase(Locale.ROOT);

        return lower.contains("error_time format is incorrect")
                || lower.contains("time format is incorrect");
    }

    private void saveEmptyAttempt(BackfillTask task) {
        Instant now = Instant.now();

        SiteHistoryDocument existing = siteHistoryRepository
                .findByBrandAndSiteCodeAndGranularityAndBucket(
                        task.site().getBrand(),
                        task.site().getSiteCode(),
                        task.granularity(),
                        task.bucket()
                )
                .orElse(null);

        int attempts = existing != null && existing.getConsecutiveEmptyAttempts() != null
                ? existing.getConsecutiveEmptyAttempts() + 1
                : 1;
        long baseDelay = Math.max(60_000L, emptyHistoryRetryMs);
        int exponent = Math.min(Math.max(0, attempts - 1), 6);
        long retryDelay = Math.min(Math.max(baseDelay, emptyHistoryMaxRetryMs),
                baseDelay * (1L << exponent));
        boolean confirmedEmpty = attempts >= Math.max(1, emptyConfirmedAfterAttempts);

        SiteHistoryDocument marker = SiteHistoryDocument.builder()
                .id(existing != null ? existing.getId() : null)
                .brand(task.site().getBrand())
                .providerAccountKey(task.site().getProviderAccountKey())
                .providerAccountGroup(task.site().getProviderAccountGroup())
                .siteCode(task.site().getSiteCode())
                .granularity(task.granularity())
                .bucket(task.bucket())
                .points(existing != null && existing.getPoints() != null ? existing.getPoints() : List.of())
                .lastSyncAt(now)
                .createdAt(existing != null ? existing.getCreatedAt() : now)
                .updatedAt(now)
                .collectionStatus(confirmedEmpty ? "EMPTY_CONFIRMED" : "EMPTY_RETRY")
                .consecutiveEmptyAttempts(attempts)
                .nextRetryAt(now.plusMillis(retryDelay))
                .lastSuccessfulSyncAt(existing != null ? existing.getLastSuccessfulSyncAt() : null)
                .build();

        siteHistoryRepository.save(marker);
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String value = raw.trim();

        if (value.matches("^\\d{10,13}$")) {
            long epoch = Long.parseLong(value);
            long epochMillis = epoch < 1_000_000_000_000L ? epoch * 1000 : epoch;

            return Instant.ofEpochMilli(epochMillis)
                    .atZone(TUNIS_ZONE)
                    .toLocalDate();
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

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record BackfillTask(
            SiteDocument site,
            String type,
            String bucket,
            HistoryGranularity granularity
    ) {
    }
}
