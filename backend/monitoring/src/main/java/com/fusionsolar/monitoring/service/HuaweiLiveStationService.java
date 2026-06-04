package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.client.FusionSolarStationClient;
import com.fusionsolar.monitoring.dto.CachedDashboardSummary;
import com.fusionsolar.monitoring.dto.CachedHistory;
import com.fusionsolar.monitoring.dto.CachedSiteDetail;
import com.fusionsolar.monitoring.dto.CachedSiteDevicesDto;
import com.fusionsolar.monitoring.dto.CachedSitesSummary;
import com.fusionsolar.monitoring.dto.CachedStations;
import com.fusionsolar.monitoring.dto.DashboardSummaryDto;
import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.dto.SiteDetailDto;
import com.fusionsolar.monitoring.dto.SiteDetailWithHistoryDto;
import com.fusionsolar.monitoring.dto.SiteDevicesResponseDto;
import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import com.fusionsolar.monitoring.dto.StationHistoryResponse;
import com.fusionsolar.monitoring.dto.StationItemDto;
import com.fusionsolar.monitoring.dto.StationListResponse;
import com.fusionsolar.monitoring.dto.StationRealKpiResponse;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory;
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory.HuaweiAccount;
import com.fusionsolar.monitoring.util.DateTimeUtils;
import com.fusionsolar.monitoring.util.SiteNameNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class HuaweiLiveStationService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");

    private static final int STATION_PAGE_SIZE = 100;
    private static final int STATION_KPI_BATCH_SIZE = 100;

    private static final long DASHBOARD_CACHE_TTL_MILLIS = 5 * 60 * 1000;
    private static final long STATIONS_CACHE_TTL_MILLIS = 60 * 60 * 1000;
    private static final long SITE_DETAIL_CACHE_TTL_MILLIS = 4 * 60 * 1000 + 45 * 1000;
    private static final long HOURLY_HISTORY_CACHE_TTL_MILLIS = 15 * 60 * 1000;
    private static final long DAILY_HISTORY_CACHE_TTL_MILLIS = 10 * 60 * 1000;
    private static final long MONTHLY_HISTORY_CACHE_TTL_MILLIS = 30 * 60 * 1000;
    private static final long YEARLY_HISTORY_CACHE_TTL_MILLIS = 60 * 60 * 1000;
    private static final long SITES_SUMMARY_CACHE_TTL_MILLIS = 4 * 60 * 1000 + 45 * 1000;
    private static final long SITE_DEVICES_CACHE_DURATION_MS = 15 * 60 * 1000;

    private static final long STATION_PAGE_DELAY_MS = 300;
    private static final long STATION_KPI_BATCH_DELAY_MS = 1200;
    private static final long GROUP_SUMMARY_MAX_WAIT_MILLIS = 6 * 60 * 1000 + 30 * 1000;

    private final FusionSolarStationClient stationClient;
    private final HuaweiAccountDirectory huaweiAccountDirectory;

    private volatile CachedDashboardSummary cachedDashboard;
    private volatile CachedStations cachedStations;
    private volatile CachedSitesSummary cachedSitesSummary;

    private final Map<String, CachedStations> cachedStationsByAccount = new ConcurrentHashMap<>();
    private final Map<String, CachedSitesSummary> cachedSitesSummaryByAccount = new ConcurrentHashMap<>();
    private final Map<String, CachedSiteDetail> cachedSiteDetails = new ConcurrentHashMap<>();
    private final Map<String, CachedHistory> cachedHistories = new ConcurrentHashMap<>();
    private final Map<String, CachedSiteDevicesDto> siteDevicesCache = new ConcurrentHashMap<>();

    public record AccountSitesSummary(
            String accountKey,
            String accountGroup,
            List<SiteSummaryDto> sites
    ) {
    }

    public List<AccountSitesSummary> getSitesSummaryByAccountGroup() {
        Map<String, List<HuaweiAccount>> accountsByGroup = huaweiAccountDirectory.getEnabledAccountsByGroup();
        List<CompletableFuture<AccountSitesSummary>> pending = new ArrayList<>();

        for (Map.Entry<String, List<HuaweiAccount>> entry : accountsByGroup.entrySet()) {
            String group = entry.getKey();
            List<HuaweiAccount> accounts = entry.getValue();

            pending.add(CompletableFuture.supplyAsync(() -> loadSitesSummaryForGroup(group, accounts)));
        }

        List<AccountSitesSummary> result = new ArrayList<>();
        long deadline = System.currentTimeMillis() + GROUP_SUMMARY_MAX_WAIT_MILLIS;

        while (!pending.isEmpty()) {
            long remainingMs = deadline - System.currentTimeMillis();
            if (remainingMs <= 0) {
                break;
            }

            try {
                CompletableFuture<?> anyCompleted = CompletableFuture.anyOf(
                        pending.toArray(new CompletableFuture[0])
                );
                anyCompleted.get(remainingMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Huawei group sites summary loading interrupted: {}", e.getMessage());
                break;
            } catch (TimeoutException e) {
                break;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("Huawei group sites summary loading failed: {}", cause.getMessage());
            }

            List<CompletableFuture<AccountSitesSummary>> stillPending = new ArrayList<>();
            for (CompletableFuture<AccountSitesSummary> future : pending) {
                if (!future.isDone()) {
                    stillPending.add(future);
                    continue;
                }

                try {
                    AccountSitesSummary summary = future.getNow(null);
                    if (summary != null) {
                        result.add(summary);
                        log.info(
                                "Huawei sites summary loaded group={} account={} sites={}",
                                summary.accountGroup(),
                                summary.accountKey(),
                                summary.sites() != null ? summary.sites().size() : 0
                        );
                    }
                } catch (Exception e) {
                    log.warn("Huawei group sites summary loading failed: {}", e.getMessage());
                }
            }
            pending = stillPending;
        }

        if (!pending.isEmpty()) {
            log.warn(
                    "Huawei sites summary returning {} completed group(s) without waiting for {} slower FusionSolar group(s).",
                    result.size(),
                    pending.size()
            );
        }

        return result;
    }

    private AccountSitesSummary loadSitesSummaryForGroup(String group, List<HuaweiAccount> accounts) {
        boolean loaded = false;

        for (HuaweiAccount account : accounts) {
            try {
                List<SiteSummaryDto> sites = getSitesSummaryForMongoSync(account.key());
                loaded = true;
                return new AccountSitesSummary(account.key(), group, sites);
            } catch (Exception e) {
                log.warn(
                        "Unable to load Huawei sites summary for group={} account={}: {}",
                        group,
                        account.key(),
                        e.getMessage()
                );

                // Les identifiants d'un même group appartiennent au même vrai compte FusionSolar.
                // Si Huawei renvoie un rate limit sur l'un d'eux, essayer immédiatement client2/client3
                // du même group ne résout rien et bloque le worker pendant le cooldown du group.
                if (isRateLimit(e)) {
                    log.warn(
                            "Huawei group={} is in rate-limit cooldown. Skipping remaining Northbound credentials of the same group.",
                            group
                    );
                    break;
                }
            }
        }

        if (!loaded) {
            log.warn("No Huawei Northbound credential succeeded for group={}", group);
        }

        return null;
    }

    private boolean isRateLimit(Exception e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("rate limit")
                        || normalized.contains("access_frequency")
                        || normalized.contains("frequency")
                        || normalized.contains("407")
                        || normalized.contains("429")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public List<StationItemDto> getStations() {
        if (isStationsCacheValid(cachedStations)) {
            return cachedStations.getData();
        }

        Map<String, StationItemDto> uniqueByCode = new LinkedHashMap<>();

        for (AccountSitesSummary ignored : getSitesSummaryByAccountGroup()) {
            // getSitesSummaryByAccountGroup refreshes the per-account station caches.
        }

        for (CachedStations cached : cachedStationsByAccount.values()) {
            if (cached == null || cached.getData() == null) {
                continue;
            }
            for (StationItemDto station : cached.getData()) {
                if (station != null && station.getPlantCode() != null && !station.getPlantCode().isBlank()) {
                    uniqueByCode.putIfAbsent(station.getPlantCode(), station);
                }
            }
        }

        List<StationItemDto> allStations = new ArrayList<>(uniqueByCode.values());
        allStations.sort(Comparator.comparing(
                station -> station.getPlantName() == null ? "" : station.getPlantName(),
                String.CASE_INSENSITIVE_ORDER
        ));

        cachedStations = new CachedStations(allStations, System.currentTimeMillis());
        return allStations;
    }

    public List<StationItemDto> getStations(String accountKey) {
        String effectiveAccountKey = effectiveAccountKey(accountKey);
        CachedStations cached = cachedStationsByAccount.get(effectiveAccountKey);
        if (isStationsCacheValid(cached)) {
            return cached.getData();
        }

        List<StationItemDto> allStations = new ArrayList<>();
        int pageNo = 1;

        while (true) {
            StationListResponse response = stationClient.getStationsPage(effectiveAccountKey, pageNo);

            if (response == null
                    || response.getData() == null
                    || response.getData().getList() == null
                    || response.getData().getList().isEmpty()) {
                break;
            }

            List<StationItemDto> pageStations = response.getData().getList();
            allStations.addAll(pageStations);

            if (pageStations.size() < STATION_PAGE_SIZE) {
                break;
            }

            sleepQuietly(STATION_PAGE_DELAY_MS);
            pageNo++;
        }

        Map<String, StationItemDto> uniqueByCode = new LinkedHashMap<>();
        for (StationItemDto station : allStations) {
            if (station != null && station.getPlantCode() != null && !station.getPlantCode().isBlank()) {
                uniqueByCode.putIfAbsent(station.getPlantCode(), station);
            }
        }

        List<StationItemDto> uniqueStations = new ArrayList<>(uniqueByCode.values());
        CachedStations nextCache = new CachedStations(uniqueStations, System.currentTimeMillis());
        cachedStationsByAccount.put(effectiveAccountKey, nextCache);
        return uniqueStations;
    }

    public List<HistoryPointDto> getHistory(String stationCode, String type, String value) {
        String accountKey = findAccountKeyForStation(stationCode);
        String key = buildHistoryCacheKey(accountKey, stationCode, type, value);

        if (isHistoryCacheValid(key, type)) {
            return cachedHistories.get(key).getData();
        }

        List<HistoryPointDto> history = switch (type.toLowerCase()) {
            case "day" -> mapHistoryPoints(
                    stationClient.getStationHourlyHistory(
                            accountKey,
                            stationCode,
                            DateTimeUtils.toDayTimestamp(DateTimeUtils.parseStrictDay(value))
                    )
            );
            case "month" -> mapHistoryPoints(
                    stationClient.getStationDailyHistory(
                            accountKey,
                            stationCode,
                            DateTimeUtils.toMonthTimestamp(DateTimeUtils.parseStrictMonth(value))
                    )
            );
            case "year" -> mapHistoryPoints(
                    stationClient.getStationMonthlyHistory(
                            accountKey,
                            stationCode,
                            DateTimeUtils.toYearTimestamp(DateTimeUtils.parseStrictYear(value))
                    )
            );
            default -> throw new IllegalArgumentException("Invalid history type: " + type);
        };

        cachedHistories.put(key, new CachedHistory(history, System.currentTimeMillis()));
        return history;
    }

    public List<HistoryPointDto> getYearlyHistory(String stationCode) {
        String accountKey = findAccountKeyForStation(stationCode);
        String key = buildHistoryCacheKey(accountKey, stationCode, "yearly", "all");

        if (isHistoryCacheValid(key, "yearly")) {
            return cachedHistories.get(key).getData();
        }

        try {
            long collectTime = DateTimeUtils.toYearTimestamp(LocalDate.now(TUNIS_ZONE).getYear());

            List<HistoryPointDto> history = mapHistoryPoints(
                    stationClient.getStationYearlyHistory(accountKey, stationCode, collectTime)
            );

            cachedHistories.put(key, new CachedHistory(history, System.currentTimeMillis()));
            return history;

        } catch (Exception e) {
            CachedHistory cached = cachedHistories.get(key);

            if (cached != null && cached.getData() != null) {
                return cached.getData();
            }

            log.warn("Unable to load yearly history for site {}: {}", stationCode, e.getMessage());
            return List.of();
        }
    }

    public SiteDetailDto getSiteDetail(String stationCode) {
        String accountKey = findAccountKeyForStation(stationCode);
        String cacheKey = accountKey + "|" + stationCode;

        if (isSiteCacheValid(cacheKey)) {
            return cachedSiteDetails.get(cacheKey).getData();
        }

        try {
            StationItemDto station = getStations(accountKey).stream()
                    .filter(s -> stationCode.equals(s.getPlantCode()))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Site not found: " + stationCode));

            SiteSummaryDto summary = findValidCachedSiteSummary(accountKey, stationCode);

            if (summary == null) {
                summary = loadSingleSiteSummarySafely(accountKey, station);
            }

            if (summary == null) {
                summary = getCachedSiteSummaryFallback(accountKey, stationCode);
            }

            if (summary == null) {
                throw new RuntimeException("Site summary not available for: " + stationCode);
            }

            SiteDetailDto detail = new SiteDetailDto(
                    Brand.HUAWEI.name(),
                    station.getPlantCode(),
                    SiteNameNormalizer.normalize(station.getPlantName()),
                    summary.getStatus(),
                    extractDouble(station.getCapacity()),
                    station.getGridConnectionDate(),
                    extractDouble(summary.getDayEnergyKwh()),
                    extractDouble(summary.getMonthEnergyKwh()),
                    extractDouble(summary.getYearEnergyKwh()),
                    extractDouble(summary.getTotalEnergyKwh()),
                    summary.getSwapDate()
            );

            cachedSiteDetails.put(cacheKey, new CachedSiteDetail(detail, System.currentTimeMillis()));
            return detail;

        } catch (Exception e) {
            SiteDetailDto fallback = getCachedSiteDetailFallback(cacheKey);

            if (fallback != null) {
                return fallback;
            }

            throw e;
        }
    }

    public SiteDetailWithHistoryDto getSiteDetailWithHistory(String stationCode) {
        try {
            SiteDetailDto summary = getSiteDetail(stationCode);

            String today = LocalDate.now(TUNIS_ZONE).toString();
            String currentMonth = YearMonth.now(TUNIS_ZONE).toString();
            String currentYear = String.valueOf(LocalDate.now(TUNIS_ZONE).getYear());

            List<HistoryPointDto> hourly = getHistory(stationCode, "day", today);
            List<HistoryPointDto> daily = getHistory(stationCode, "month", currentMonth);
            List<HistoryPointDto> monthly = getHistory(stationCode, "year", currentYear);
            List<HistoryPointDto> yearly = getYearlyHistory(stationCode);

            return new SiteDetailWithHistoryDto(summary, hourly, daily, monthly, yearly);

        } catch (Exception e) {
            SiteDetailWithHistoryDto fallback = getCachedFullFallback(stationCode);

            if (fallback != null) {
                return fallback;
            }

            throw e;
        }
    }

    public DashboardSummaryDto getDashboardSummary() {
        if (isDashboardCacheValid()) {
            return cachedDashboard.getData();
        }

        try {
            DashboardSummaryDto summary = buildDashboardSummary(getSitesSummary(), getStations());
            cachedDashboard = new CachedDashboardSummary(summary, System.currentTimeMillis());
            return summary;

        } catch (Exception e) {
            DashboardSummaryDto fallback = getCachedDashboardFallback();

            if (fallback != null) {
                return fallback;
            }

            throw e;
        }
    }

    public List<SiteSummaryDto> getSitesSummary() {
        if (isSitesSummaryCacheValid(cachedSitesSummary)) {
            return cachedSitesSummary.getData();
        }

        try {
            Map<String, SiteSummaryDto> uniqueByCode = new LinkedHashMap<>();
            for (AccountSitesSummary accountSites : getSitesSummaryByAccountGroup()) {
                for (SiteSummaryDto site : accountSites.sites()) {
                    if (site != null && site.getSiteCode() != null && !site.getSiteCode().isBlank()) {
                        uniqueByCode.putIfAbsent(site.getSiteCode(), site);
                    }
                }
            }
            List<SiteSummaryDto> result = new ArrayList<>(uniqueByCode.values());
            long now = System.currentTimeMillis();
            cachedSitesSummary = new CachedSitesSummary(result, now);
            cachedDashboard = new CachedDashboardSummary(buildDashboardSummary(result, getStations()), now);
            return result;
        } catch (Exception e) {
            List<SiteSummaryDto> fallback = getCachedSitesSummaryFallback();

            if (fallback != null) {
                return fallback;
            }

            throw e;
        }
    }

    public List<SiteSummaryDto> getSitesSummary(String accountKey) {
        String effectiveAccountKey = effectiveAccountKey(accountKey);
        CachedSitesSummary cached = cachedSitesSummaryByAccount.get(effectiveAccountKey);
        if (isSitesSummaryCacheValid(cached)) {
            return cached.getData();
        }

        return refreshSitesSummaryCache(effectiveAccountKey);
    }

    public List<SiteSummaryDto> getSitesSummaryForMongoSync(String accountKey) {
        String effectiveAccountKey = effectiveAccountKey(accountKey);
        List<StationItemDto> stations = getStations(effectiveAccountKey);
        List<SiteSummaryDto> result = new ArrayList<>();

        for (StationItemDto station : stations) {
            if (station == null || station.getPlantCode() == null || station.getPlantCode().isBlank()) {
                continue;
            }
            result.add(buildSiteSummaryFromStationOnly(station));
        }

        log.info("Huawei station list loaded for Mongo sync account={} sites={}", effectiveAccountKey, result.size());
        return result;
    }

    public SiteDevicesResponseDto getSiteDevices(String stationCode) {
        if (isSiteDevicesCacheValid(stationCode)) {
            return siteDevicesCache.get(stationCode).getData();
        }

        return new SiteDevicesResponseDto(stationCode, List.of());
    }

    private List<SiteSummaryDto> refreshSitesSummaryCache(String accountKey) {
        CachedSitesSummary cached = cachedSitesSummaryByAccount.get(accountKey);
        if (isSitesSummaryCacheValid(cached)) {
            return cached.getData();
        }

        List<StationItemDto> stations = getStations(accountKey);
        Map<String, StationRealKpiResponse.DataItem> kpiMap = getStationKpiMap(accountKey, stations);

        List<SiteSummaryDto> result = new ArrayList<>();

        for (StationItemDto station : stations) {
            StationRealKpiResponse.DataItem kpiItem = kpiMap.get(station.getPlantCode());
            result.add(kpiItem != null ? buildSiteSummary(station, kpiItem) : buildSiteSummaryFromStationOnly(station));
        }

        long now = System.currentTimeMillis();

        cachedSitesSummaryByAccount.put(accountKey, new CachedSitesSummary(result, now));
        return result;
    }

    private List<StationRealKpiResponse.DataItem> getAllStationsKpi(String accountKey, List<StationItemDto> stations) {
        List<String> codes = stations.stream()
                .map(StationItemDto::getPlantCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();

        if (codes.isEmpty()) {
            return List.of();
        }

        List<StationRealKpiResponse.DataItem> result = new ArrayList<>();
        List<List<String>> batches = partition(codes, STATION_KPI_BATCH_SIZE);

        for (int i = 0; i < batches.size(); i++) {
            List<String> batch = batches.get(i);

            try {
                StationRealKpiResponse response = stationClient.getStationRealKpi(accountKey, batch);

                if (response != null && response.getData() != null) {
                    result.addAll(response.getData());
                }

            } catch (Exception e) {
                log.warn("Unable to load station KPI batch {}/{} for account {}: {}", i + 1, batches.size(), accountKey, e.getMessage());
            }

            if (i < batches.size() - 1) {
                sleepQuietly(STATION_KPI_BATCH_DELAY_MS);
            }
        }

        return result;
    }

    private Map<String, StationRealKpiResponse.DataItem> getStationKpiMap(String accountKey, List<StationItemDto> stations) {
        List<StationRealKpiResponse.DataItem> kpis = getAllStationsKpi(accountKey, stations);
        Map<String, StationRealKpiResponse.DataItem> map = new LinkedHashMap<>();

        for (StationRealKpiResponse.DataItem item : kpis) {
            if (item != null && item.getStationCode() != null) {
                map.put(item.getStationCode(), item);
            }
        }

        return map;
    }

    private SiteSummaryDto buildSiteSummaryFromStationOnly(StationItemDto station) {
        return new SiteSummaryDto(
                Brand.HUAWEI.name(),
                station.getPlantCode(),
                SiteNameNormalizer.normalize(station.getPlantName()),
                "unknown",
                extractDouble(station.getCapacity()),
                station.getGridConnectionDate(),
                null,
                null,
                null,
                null,
                station.getSwapDate()
        );
    }

    private SiteSummaryDto buildSiteSummary(StationItemDto station, StationRealKpiResponse.DataItem kpiItem) {
        Map<String, Object> dataItemMap =
                kpiItem != null && kpiItem.getDataItemMap() != null
                        ? kpiItem.getDataItemMap()
                        : Map.of();

        Object rawStatus = dataItemMap.get("real_health_state");

        log.info(
                "Huawei status raw for site {} ({}) = {}",
                station.getPlantCode(),
                station.getPlantName(),
                rawStatus
        );

        return new SiteSummaryDto(
                Brand.HUAWEI.name(),
                station.getPlantCode(),
                SiteNameNormalizer.normalize(station.getPlantName()),
                rawStatus != null ? mapStatus(rawStatus) : "unknown",
                extractDouble(station.getCapacity()),
                station.getGridConnectionDate(),
                extractDoubleOrNull(dataItemMap.get("day_power")),
                extractDoubleOrNull(dataItemMap.get("month_power")),
                null,
                extractDoubleOrNull(dataItemMap.get("total_power")),
                station.getSwapDate()
        );
    }

    private DashboardSummaryDto buildDashboardSummary(List<SiteSummaryDto> sites, List<StationItemDto> stations) {
        List<SiteSummaryDto> safeSites = sites != null ? sites : List.of();
        List<StationItemDto> safeStations = stations != null ? stations : List.of();

        int totalSites = safeSites.size();

        double totalInstalledPowerKw = safeStations.stream()
                .mapToDouble(station -> extractDouble(station.getCapacity()))
                .sum();

        double totalDayEnergyKwh = safeSites.stream()
                .mapToDouble(site -> extractDouble(site.getDayEnergyKwh()))
                .sum();

        double totalMonthEnergyKwh = safeSites.stream()
                .mapToDouble(site -> extractDouble(site.getMonthEnergyKwh()))
                .sum();

        double totalYearEnergyKwh = safeSites.stream()
                .mapToDouble(site -> extractDouble(site.getYearEnergyKwh()))
                .sum();

        double totalEnergyKwh = safeSites.stream()
                .mapToDouble(site -> extractDouble(site.getTotalEnergyKwh()))
                .sum();

        int healthySites = (int) safeSites.stream()
                .filter(site -> "healthy".equalsIgnoreCase(site.getStatus()))
                .count();

        int faultySites = (int) safeSites.stream()
                .filter(site -> "faulty".equalsIgnoreCase(site.getStatus()))
                .count();

        int disconnectedSites = (int) safeSites.stream()
                .filter(site -> "disconnected".equalsIgnoreCase(site.getStatus()))
                .count();

        return new DashboardSummaryDto(
                totalSites,
                totalInstalledPowerKw,
                totalDayEnergyKwh,
                totalMonthEnergyKwh,
                totalYearEnergyKwh,
                totalEnergyKwh,
                healthySites,
                faultySites,
                disconnectedSites
        );
    }

    private SiteSummaryDto loadSingleSiteSummarySafely(String accountKey, StationItemDto station) {
        try {
            StationRealKpiResponse response = stationClient.getStationRealKpi(accountKey, List.of(station.getPlantCode()));

            if (response == null || response.getData() == null || response.getData().isEmpty()) {
                return null;
            }

            return buildSiteSummary(station, response.getData().get(0));

        } catch (Exception e) {
            log.warn("Unable to load single site summary for {} account {}: {}", station.getPlantCode(), accountKey, e.getMessage());
            return null;
        }
    }

    private SiteSummaryDto findValidCachedSiteSummary(String accountKey, String stationCode) {
        CachedSitesSummary cached = cachedSitesSummaryByAccount.get(accountKey);
        if (cached == null || cached.getData() == null) {
            return null;
        }

        return cached.getData().stream()
                .filter(site -> stationCode.equals(site.getSiteCode()))
                .findFirst()
                .orElse(null);
    }

    private SiteSummaryDto getCachedSiteSummaryFallback(String accountKey, String stationCode) {
        CachedSitesSummary cached = cachedSitesSummaryByAccount.get(accountKey);

        if (cached == null || cached.getData() == null) {
            return null;
        }

        return cached.getData().stream()
                .filter(site -> stationCode.equals(site.getSiteCode()))
                .findFirst()
                .orElse(null);
    }

    private List<HistoryPointDto> mapHistoryPoints(StationHistoryResponse response) {
        if (response == null || response.getData() == null) {
            return List.of();
        }

        return response.getData().stream()
                .filter(item -> item != null && item.getCollectTime() != null)
                .map(item -> new HistoryPointDto(
                        item.getCollectTime(),
                        extractDouble(item.getDataItemMap() != null
                                ? item.getDataItemMap().get("inverter_power")
                                : null)
                ))
                .toList();
    }

    private String mapStatus(Object value) {
        if (value == null) {
            return "unknown";
        }

        String status = value.toString().trim();

        return switch (status) {
            case "1" -> "disconnected";
            case "2" -> "faulty";
            case "3" -> "healthy";
            default -> "unknown";
        };
    }

    private Double extractDoubleOrNull(Object value) {
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

            return Double.parseDouble(text.replace("%", ""));

        } catch (Exception e) {
            return null;
        }
    }

    private double extractDouble(Object value) {
        if (value == null) {
            return 0.0;
        }

        if (value instanceof Number number) {
            return number.doubleValue();
        }

        try {
            String text = value.toString().trim();

            if (text.isBlank()
                    || "null".equalsIgnoreCase(text)
                    || "N/A".equalsIgnoreCase(text)) {
                return 0.0;
            }

            return Double.parseDouble(text.replace("%", ""));

        } catch (Exception e) {
            return 0.0;
        }
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }

        List<List<T>> result = new ArrayList<>();

        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }

        return result;
    }

    private boolean isDashboardCacheValid() {
        return cachedDashboard != null
                && cachedDashboard.getData() != null
                && System.currentTimeMillis() - cachedDashboard.getCachedAt() < DASHBOARD_CACHE_TTL_MILLIS;
    }

    private boolean isStationsCacheValid(CachedStations cached) {
        return cached != null
                && cached.getData() != null
                && !cached.getData().isEmpty()
                && System.currentTimeMillis() - cached.getCachedAt() < STATIONS_CACHE_TTL_MILLIS;
    }

    private boolean isSiteCacheValid(String cacheKey) {
        CachedSiteDetail cached = cachedSiteDetails.get(cacheKey);

        return cached != null
                && cached.getData() != null
                && System.currentTimeMillis() - cached.getCachedAt() < SITE_DETAIL_CACHE_TTL_MILLIS;
    }

    private String buildHistoryCacheKey(String accountKey, String stationCode, String type, String value) {
        return accountKey + "|" + stationCode + "|" + type + "|" + value;
    }

    private long getHistoryCacheTtl(String type) {
        return switch (type.toLowerCase()) {
            case "day" -> HOURLY_HISTORY_CACHE_TTL_MILLIS;
            case "month" -> DAILY_HISTORY_CACHE_TTL_MILLIS;
            case "year" -> MONTHLY_HISTORY_CACHE_TTL_MILLIS;
            case "yearly" -> YEARLY_HISTORY_CACHE_TTL_MILLIS;
            default -> 5 * 60 * 1000;
        };
    }

    private boolean isHistoryCacheValid(String key, String type) {
        CachedHistory cached = cachedHistories.get(key);

        return cached != null
                && cached.getData() != null
                && System.currentTimeMillis() - cached.getCachedAt() < getHistoryCacheTtl(type);
    }

    private boolean isSitesSummaryCacheValid(CachedSitesSummary cached) {
        return cached != null
                && cached.getData() != null
                && !cached.getData().isEmpty()
                && System.currentTimeMillis() - cached.getCachedAt() < SITES_SUMMARY_CACHE_TTL_MILLIS;
    }

    private boolean isSiteDevicesCacheValid(String stationCode) {
        CachedSiteDevicesDto cache = siteDevicesCache.get(stationCode);

        return cache != null
                && cache.getData() != null
                && System.currentTimeMillis() - cache.getCachedAt() < SITE_DEVICES_CACHE_DURATION_MS;
    }

    private DashboardSummaryDto getCachedDashboardFallback() {
        return cachedDashboard != null ? cachedDashboard.getData() : null;
    }

    private List<SiteSummaryDto> getCachedSitesSummaryFallback() {
        return cachedSitesSummary != null ? cachedSitesSummary.getData() : null;
    }

    private SiteDetailDto getCachedSiteDetailFallback(String cacheKey) {
        CachedSiteDetail cached = cachedSiteDetails.get(cacheKey);
        return cached != null ? cached.getData() : null;
    }

    private SiteDetailWithHistoryDto getCachedFullFallback(String stationCode) {
        String accountKey = findAccountKeyForStation(stationCode);
        String cacheKey = accountKey + "|" + stationCode;
        SiteDetailDto summary = getCachedSiteDetailFallback(cacheKey);

        if (summary == null) {
            return null;
        }

        String today = LocalDate.now(TUNIS_ZONE).toString();
        String currentMonth = YearMonth.now(TUNIS_ZONE).toString();
        String currentYear = String.valueOf(LocalDate.now(TUNIS_ZONE).getYear());

        String dayKey = buildHistoryCacheKey(accountKey, stationCode, "day", today);
        String monthKey = buildHistoryCacheKey(accountKey, stationCode, "month", currentMonth);
        String yearKey = buildHistoryCacheKey(accountKey, stationCode, "year", currentYear);
        String yearlyKey = buildHistoryCacheKey(accountKey, stationCode, "yearly", "all");

        List<HistoryPointDto> hourly =
                cachedHistories.get(dayKey) != null ? cachedHistories.get(dayKey).getData() : List.of();

        List<HistoryPointDto> daily =
                cachedHistories.get(monthKey) != null ? cachedHistories.get(monthKey).getData() : List.of();

        List<HistoryPointDto> monthly =
                cachedHistories.get(yearKey) != null ? cachedHistories.get(yearKey).getData() : List.of();

        List<HistoryPointDto> yearly =
                cachedHistories.get(yearlyKey) != null ? cachedHistories.get(yearlyKey).getData() : List.of();

        return new SiteDetailWithHistoryDto(summary, hourly, daily, monthly, yearly);
    }

    private String findAccountKeyForStation(String stationCode) {
        for (Map.Entry<String, List<HuaweiAccount>> entry : huaweiAccountDirectory.getEnabledAccountsByGroup().entrySet()) {
            for (HuaweiAccount account : entry.getValue()) {
                try {
                    boolean found = getStations(account.key()).stream()
                            .anyMatch(station -> stationCode.equals(station.getPlantCode()));
                    if (found) {
                        return account.key();
                    }
                } catch (Exception e) {
                    log.debug("Unable to search station {} in account {}: {}", stationCode, account.key(), e.getMessage());
                }
            }
        }
        return huaweiAccountDirectory.primaryAccountKey();
    }

    private String effectiveAccountKey(String accountKey) {
        return accountKey == null || accountKey.isBlank()
                ? huaweiAccountDirectory.primaryAccountKey()
                : accountKey;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
