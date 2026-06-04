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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HuaweiBatchHistoryBackfillService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final DateTimeFormatter DISPLAY_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SiteRepository siteRepository;
    private final SiteHistoryRepository siteHistoryRepository;
    private final SiteMongoMapper siteMongoMapper;
    private final FusionSolarStationClient stationClient;
    private final HuaweiAccountDirectory huaweiAccountDirectory;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger accountCursor = new AtomicInteger(0);

    /**
     * Compteur en mémoire pour éviter de dépasser les quotas journaliers Huawei Report.
     * La clé contient la date, le groupe et l’endpoint.
     */
    private final Map<String, DailyCounter> huaweiDailyCounters = new ConcurrentHashMap<>();

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

    @Value("${app.history-batch-backfill.current-month-refresh-ms:3600000}")
    private long currentMonthRefreshMs;

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
        Map<String, List<HuaweiAccount>> accountsByGroup = huaweiAccountDirectory.getEnabledAccountsByGroup();
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
                log.warn("Huawei batch history skipped for group={}: no enabled Northbound credential", accountGroup);
                continue;
            }

            Optional<HuaweiAccount> account = nextAvailableAccount(groupAccounts);
            if (account.isEmpty()) {
                log.info(
                        "Huawei batch history skipped this pass for group={}: all group accounts are cooling down after rate limit.",
                        accountGroup
                );
                continue;
            }

            executeTask(next.get(), account.get().key());
            callsDone++;
        }

        return callsDone;
    }

    private Optional<BatchTask> nextTask(List<SiteDocument> sites, Set<String> excludedGroups) {
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
         * 1) Buckets courants.
         * Objectif: garder l’UI à jour sans bloquer le backfill historique.
         */
        candidates.add(new HistoryCandidate(HistoryGranularity.DAY, "day", today.toString(), true));
        candidates.add(new HistoryCandidate(HistoryGranularity.MONTH, "month", currentMonth.toString(), true));
        candidates.add(new HistoryCandidate(HistoryGranularity.YEAR, "year", String.valueOf(currentYear), true));
        candidates.add(new HistoryCandidate(HistoryGranularity.YEARLY, "yearly", "all", true));

        /*
         * 2) YEAR historique.
         * Très rentable: un appel donne les 12 mois de l’année pour jusqu’à 100 sites.
         */
        for (int year = currentYear; year >= firstMonth.getYear(); year--) {
            String bucket = String.valueOf(year);
            candidates.add(new HistoryCandidate(
                    HistoryGranularity.YEAR,
                    "year",
                    bucket,
                    bucket.equals(String.valueOf(currentYear))
            ));
        }

        /*
         * 3) MONTH historique.
         * Très rentable: un appel donne les jours du mois pour jusqu’à 100 sites.
         */
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

        /*
         * 4) DAY historique.
         * Très coûteux: un bucket = une seule date.
         * Donc il passe après YEAR/MONTH et il est limité par historic-day-lookback-days.
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

    private List<SiteDocument> dueSites(String accountGroup, List<SiteDocument> sites, HistoryCandidate candidate) {
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

        LocalDate yesterday = LocalDate.now(TUNIS_ZONE).minusDays(1);

        return sites.stream()
                .filter(site -> isBucketAfterSiteStart(site, candidate, yesterday))
                .filter(site -> isDocumentDue(existingBySiteCode.get(site.getSiteCode()), candidate))
                .toList();
    }

    private boolean isBucketAfterSiteStart(SiteDocument site, HistoryCandidate candidate, LocalDate yesterday) {
        if (candidate.granularity() == HistoryGranularity.YEARLY) {
            return true;
        }

        LocalDate firstDate = firstHistoryDate(site, yesterday);
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
        registerHuaweiDailyCall(task.accountGroup(), candidate);

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

        for (SiteDocument site : task.sites()) {
            List<HistoryPointDto> points = pointsBySiteCode.get(site.getSiteCode());
            saveHistory(site, candidate.granularity(), candidate.bucket(), points != null ? points : List.of());
        }

        log.info(
                "Huawei batch history loaded group={} account={} type={} bucket={} requestedSites={} returnedSites={}",
                task.accountGroup(),
                accountKey,
                candidate.type(),
                candidate.bucket(),
                task.sites().size(),
                returnedSiteCodes.size()
        );
    }

    private Map<String, List<HistoryPointDto>> mapHistoryBySiteCode(StationHistoryResponse response) {
        Map<String, List<HistoryPointDto>> result = new LinkedHashMap<>();

        if (response == null || response.getData() == null) {
            return result;
        }

        for (StationHistoryItemDto item : response.getData()) {
            if (item == null || item.getStationCode() == null || item.getStationCode().isBlank() || item.getCollectTime() == null) {
                continue;
            }

            Double value = extractDouble(item.getDataItemMap() != null
                    ? item.getDataItemMap().get("inverter_power")
                    : null);

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

        List<HistoryPointDto> pointsToSave = loadedPoints != null ? loadedPoints : List.of();

        if (pointsToSave.isEmpty() && existing != null && existing.getPoints() != null && !existing.getPoints().isEmpty()) {
            pointsToSave = existing.getPoints();
        }

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

        siteHistoryRepository.save(document);
    }

    private LocalDate firstHistoryDate(SiteDocument site, LocalDate yesterday) {
        LocalDate maxLookbackStart = YearMonth.from(yesterday)
                .minusMonths(Math.max(1, maxLookbackMonths) - 1L)
                .atDay(1);

        /*
         * On se base sur la dernière modification fonctionnelle:
         * la vraie date de début production est la date de swap quand elle existe.
         * Sinon on utilise la date de mise en service / grid connection.
         */
        LocalDate productionStartDate = site.getSwapDate() != null
                ? site.getSwapDate()
                : parseDate(site.getGridConnectionDate());

        if (productionStartDate == null) {
            /*
             * Pour les sites existants, gridConnectionDate peut être absent dans l'API.
             * On préfère backfiller jusqu'à maxLookbackStart plutôt que de perdre tout l'ancien historique.
             * Les vrais nouveaux sites sans historique auront des buckets vides, mémorisés et retentés plus tard.
             */
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
        if (site != null && site.getProviderAccountKey() != null && !site.getProviderAccountKey().isBlank()) {
            return site.getProviderAccountKey().trim();
        }
        return huaweiAccountDirectory.primaryAccountKey();
    }

    private String accountGroupOf(SiteDocument site) {
        if (site != null && site.getProviderAccountGroup() != null && !site.getProviderAccountGroup().isBlank()) {
            return site.getProviderAccountGroup().trim();
        }
        if (site != null && site.getProviderAccountKey() != null && !site.getProviderAccountKey().isBlank()) {
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
        String key = dailyQuotaKey(accountGroup, candidate);
        DailyCounter counter = huaweiDailyCounters.computeIfAbsent(key, ignored -> new DailyCounter());
        return counter.count < Math.max(1, limit);
    }

    private void registerHuaweiDailyCall(String accountGroup, HistoryCandidate candidate) {
        String key = dailyQuotaKey(accountGroup, candidate);
        DailyCounter counter = huaweiDailyCounters.computeIfAbsent(key, ignored -> new DailyCounter());
        counter.count++;
    }

    private String dailyQuotaKey(String accountGroup, HistoryCandidate candidate) {
        return LocalDate.now(TUNIS_ZONE)
                + "|"
                + safeGroup(accountGroup)
                + "|"
                + huaweiEndpoint(candidate);
    }

    private String huaweiEndpoint(HistoryCandidate candidate) {
        return switch (candidate.granularity()) {
            case DAY -> "/thirdData/getKpiStationHour";
            case MONTH -> "/thirdData/getKpiStationDay";
            case YEAR -> "/thirdData/getKpiStationMonth";
            case YEARLY -> "/thirdData/getKpiStationYear";
        };
    }

    private String safeGroup(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }

    private Optional<HuaweiAccount> nextAvailableAccount(List<HuaweiAccount> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return Optional.empty();
        }

        int startIndex = Math.floorMod(accountCursor.getAndIncrement(), accounts.size());
        HuaweiAccount bestCoolingAccount = null;
        long bestWaitMs = Long.MAX_VALUE;

        for (int offset = 0; offset < accounts.size(); offset++) {
            HuaweiAccount account = accounts.get((startIndex + offset) % accounts.size());
            long waitMs = stationClient.huaweiWaitMsBeforeNextHistoryCall(account.key());

            if (waitMs <= 0L || !stationClient.isHuaweiAccountCoolingDown(account.key())) {
                return Optional.of(account);
            }

            if (waitMs < bestWaitMs) {
                bestWaitMs = waitMs;
                bestCoolingAccount = account;
            }
        }

        if (bestCoolingAccount != null) {
            log.info(
                    "Huawei batch history account selection: nearest available account={} in about {} ms",
                    bestCoolingAccount.key(),
                    bestWaitMs
            );
        }

        return Optional.empty();
    }

    private static class DailyCounter {
        private int count = 0;
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
