package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.DashboardSummaryDto;
import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.dto.SiteDetailDto;
import com.fusionsolar.monitoring.dto.SiteDetailWithHistoryDto;
import com.fusionsolar.monitoring.dto.SiteDevicesResponseDto;
import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import com.fusionsolar.monitoring.exception.ResourceNotFoundException;
import com.fusionsolar.monitoring.persistence.document.SiteDeviceDocument;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.repositories.SiteDeviceRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.provider.mapper.SiteMongoMapper;
import com.fusionsolar.monitoring.service.sync.GrowattHistorySyncService;
import com.fusionsolar.monitoring.service.sync.HistoryBackfillCompletenessSupport;
import com.fusionsolar.monitoring.service.sync.HuaweiHistorySyncService;
import com.fusionsolar.monitoring.service.sync.MonitoringSitesSyncRegistry;
import com.fusionsolar.monitoring.service.sync.SiteEnergyKpiReconciliationService;
import com.fusionsolar.monitoring.service.sync.SolisHistorySyncService;
import com.fusionsolar.monitoring.util.DateTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StationService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");

    private final SiteRepository siteRepository;
    private final SiteDeviceRepository siteDeviceRepository;
    private final SiteHistoryRepository siteHistoryRepository;
    private final MonitoringSitesSyncRegistry monitoringSitesSyncRegistry;
    private final SiteMongoMapper siteMongoMapper;
    private final SiteEnergyKpiReconciliationService siteEnergyKpiReconciliationService;
    private final CurrentSiteAccessService currentSiteAccessService;
    private final com.fusionsolar.monitoring.service.sync.HistoryDemandQueue historyDemandQueue;

    private final ObjectProvider<HuaweiHistorySyncService> huaweiHistorySyncServiceProvider;
    private final ObjectProvider<SolisHistorySyncService> solisHistorySyncServiceProvider;
    private final ObjectProvider<GrowattHistorySyncService> growattHistorySyncServiceProvider;

    @Value("${app.history-backfill.max-lookback-months:120}")
    private int maxLookbackMonths;

    public DashboardSummaryDto getDashboardSummary() {
        List<SiteDocument> sites = siteEnergyKpiReconciliationService
                .reconcileAllFromCachedHistories(getAllSitesOrBootstrap());

        return siteMongoMapper.toDashboardSummary(sites);
    }

    public DashboardSummaryDto getDashboardSummary(Brand brand) {
        List<SiteDocument> sites = siteEnergyKpiReconciliationService
                .reconcileAllFromCachedHistories(getSitesByBrandOrBootstrap(brand));

        return siteMongoMapper.toDashboardSummary(sites);
    }

    public List<SiteSummaryDto> getSitesSummary() {
        return siteEnergyKpiReconciliationService
                .reconcileAllFromCachedHistories(getAllSitesOrBootstrap())
                .stream()
                .map(siteMongoMapper::toSiteSummaryDto)
                .toList();
    }

    public List<SiteSummaryDto> getSitesSummary(Brand brand) {
        return siteEnergyKpiReconciliationService
                .reconcileAllFromCachedHistories(getSitesByBrandOrBootstrap(brand))
                .stream()
                .map(siteMongoMapper::toSiteSummaryDto)
                .toList();
    }

    public SiteDetailDto getSiteDetail(Brand brand, String siteCode) {
        SiteDocument site = siteEnergyKpiReconciliationService
                .reconcileCurrentKpisFromCache(getSiteOrThrow(brand, siteCode));

        return siteMongoMapper.toSiteDetailDto(site);
    }

    public SiteDetailWithHistoryDto getSiteDetailWithHistory(Brand brand, String siteCode) {
        SiteDocument site = siteEnergyKpiReconciliationService
                .reconcileCurrentKpisFromCache(getSiteOrThrow(brand, siteCode));

        SiteDetailDto detail = siteMongoMapper.toSiteDetailDto(site);

        String today = LocalDate.now(TUNIS_ZONE).toString();
        String currentMonth = YearMonth.now(TUNIS_ZONE).toString();
        String currentYear = String.valueOf(LocalDate.now(TUNIS_ZONE).getYear());

        /*
         * /full reste Mongo-only pour éviter les appels fournisseurs lourds au chargement.
         * Les historiques à la demande sont gérés par getHistory(...).
         */
        return new SiteDetailWithHistoryDto(
                detail,
                getCachedHistory(site, HistoryGranularity.DAY, today),
                getCachedHistory(site, HistoryGranularity.MONTH, currentMonth),
                getCachedHistory(site, HistoryGranularity.YEAR, currentYear),
                getCachedHistory(site, HistoryGranularity.YEARLY, "all")
        );
    }

    public java.util.Map<String, String> getLatestCollectedPeriods(Brand brand, String siteCode) {
        SiteDocument site = getSiteOrThrow(brand, siteCode);
        java.util.Map<String, String> periods = new java.util.LinkedHashMap<>();
        for (String type : List.of("day", "month", "year")) {
            var histories = siteHistoryRepository.findAllByBrandAndSiteCodeAndGranularity(
                    brand, siteCode, siteMongoMapper.toGranularity(type));
            LatestCollectedPeriods.latest(histories, accountGroupOf(site), type, java.time.Instant.now())
                    .ifPresent(bucket -> periods.put(type, bucket));
        }
        return periods;
    }

    public void requestHistoryCollection(Brand brand, String siteCode, String type, String value) {
        validateHistoryInput(type, value);
        SiteDocument site = getSiteOrThrow(brand, siteCode);
        if (!canLazyFetchHistory(site)) throw new IllegalArgumentException("Unsupported history brand");
        historyDemandQueue.enqueue(site, type.trim().toLowerCase(java.util.Locale.ROOT), value);
    }

    public List<HistoryPointDto> getHistory(Brand brand, String siteCode, String type, String value) {
        validateHistoryInput(type, value);

        SiteDocument site = getSiteOrThrow(brand, siteCode);
        HistoryGranularity granularity = siteMongoMapper.toGranularity(type);
        String normalizedType = type.trim().toLowerCase();

        SiteHistoryDocument cached = findHistory(site, granularity, value).orElse(null);

        if (isUsableCachedHistory(site, cached)) {
            List<HistoryPointDto> points = cached.getPoints();
            siteEnergyKpiReconciliationService.reconcileAfterHistoryLoad(site, normalizedType, value, points);
            return points;
        }

        /*
         * Objectif métier:
         * - DAY = courbe horaire kW pour n'importe quel jour choisi.
         * - MONTH = source KPI journalier historique kWh.
         * - YEAR = source KPI mensuel historique kWh.
         *
         * Donc si Mongo n'a pas encore le bucket demandé, on charge à la demande
         * depuis la plateforme mère via le backend, jamais depuis Flutter.
         */
        if (canLazyFetchHistory(site)) historyDemandQueue.enqueue(site, normalizedType, value);
        // Serve partial validated data immediately while the queue completes the bucket.
        if (cached != null && cached.getPoints() != null) {
            if (site.getBrand() != Brand.SOLIS ||
                    (!looksLikeSolisUnscopedHistory(cached.getPoints()) && !looksLikeSolisAggregateMonthOrYear(cached)))
                return cached.getPoints();
        }

        if (cached != null && cached.getPoints() != null && isUsableCachedHistory(site, cached)) {
            return cached.getPoints();
        }

        return List.of();
    }

    private boolean isUsableCachedHistory(SiteDocument site, SiteHistoryDocument cached) {
        if (cached == null || cached.getPoints() == null || cached.getPoints().isEmpty()) {
            return false;
        }

        if (cached.getGranularity() == HistoryGranularity.MONTH) {
            HistoryBackfillCompletenessSupport.MonthCompleteness completeness =
                    HistoryBackfillCompletenessSupport.inspectMonth(
                            site,
                            cached,
                            cached.getBucket(),
                            LocalDate.now(TUNIS_ZONE),
                            maxLookbackMonths,
                            TUNIS_ZONE
                    );

            if (completeness.expectedDays() > 0 && completeness.missingOrInvalidDays() > 0) {
                log.info(
                        "Ignored incomplete MONTH cached history. brand={} site={} bucket={} collected={}/{} missingOrInvalid={} expectedRange={}..{}",
                        site.getBrand(),
                        site.getSiteCode(),
                        cached.getBucket(),
                        completeness.collectedDays(),
                        completeness.expectedDays(),
                        completeness.missingOrInvalidDays(),
                        completeness.expectedStart(),
                        completeness.expectedEnd()
                );
                return false;
            }
        }

        if (site.getBrand() == Brand.SOLIS) {
            if (looksLikeSolisUnscopedHistory(cached.getPoints())) {
                log.warn(
                        "Ignored suspicious Solis cached history. site={} granularity={} bucket={} points={}",
                        site.getSiteCode(),
                        cached.getGranularity(),
                        cached.getBucket(),
                        cached.getPoints().size()
                );
                return false;
            }

            if (looksLikeSolisAggregateMonthOrYear(cached)) {
                log.warn(
                        "Ignored Solis aggregate cached history. site={} granularity={} bucket={} points={}",
                        site.getSiteCode(),
                        cached.getGranularity(),
                        cached.getBucket(),
                        cached.getPoints().size()
                );
                return false;
            }
        }

        return true;
    }

    private boolean canLazyFetchHistory(SiteDocument site) {
        return site != null && (
                site.getBrand() == Brand.HUAWEI ||
                        site.getBrand() == Brand.SOLIS ||
                        site.getBrand() == Brand.GROWATT
        );
    }

    private List<HistoryPointDto> fetchAndCacheProviderHistory(
            SiteDocument site,
            String type,
            String value,
            HistoryGranularity granularity,
            SiteHistoryDocument cached
    ) {
        try {
            return switch (site.getBrand()) {
                case HUAWEI -> {
                    HuaweiHistorySyncService huaweiHistorySyncService =
                            huaweiHistorySyncServiceProvider.getIfAvailable();

                    if (huaweiHistorySyncService == null) {
                        log.debug("Huawei lazy history skipped because Huawei provider is disabled");
                        yield safeCachedPoints(cached);
                    }

                    yield huaweiHistorySyncService.getHistory(site, type, value, true);
                }

                case SOLIS -> {
                    SolisHistorySyncService solisHistorySyncService =
                            solisHistorySyncServiceProvider.getIfAvailable();

                    if (solisHistorySyncService == null) {
                        log.debug("Solis lazy history skipped because Solis provider is disabled");
                        yield safeCachedPoints(cached);
                    }

                    /*
                     * forceRefresh=true pour recharger même si un ancien document vide existe.
                     */
                    yield solisHistorySyncService.getHistory(site, type, value, true);
                }

                case GROWATT -> {
                    GrowattHistorySyncService growattHistorySyncService =
                            growattHistorySyncServiceProvider.getIfAvailable();

                    if (growattHistorySyncService == null) {
                        log.debug("Growatt lazy history skipped because Growatt provider is disabled");
                        yield safeCachedPoints(cached);
                    }

                    yield growattHistorySyncService.getHistory(site, type, value, true);
                }

                default -> safeCachedPoints(cached);
            };
        } catch (Exception e) {
            log.warn(
                    "Lazy provider history failed brand={} site={} type={} value={}. Returning cached/empty. Cause={}",
                    site.getBrand(),
                    site.getSiteCode(),
                    type,
                    value,
                    e.getMessage()
            );

            return safeCachedPoints(cached);
        }
    }

    private List<HistoryPointDto> safeCachedPoints(SiteHistoryDocument cached) {
        return cached != null && cached.getPoints() != null
                ? cached.getPoints()
                : List.of();
    }

    public SiteDevicesResponseDto getSiteDevices(Brand brand, String siteCode) {
        SiteDocument site = getSiteOrThrow(brand, siteCode);

        SiteDeviceDocument cached = siteDeviceRepository
                .findByBrandAndProviderAccountGroupAndSiteCode(
                        site.getBrand(),
                        accountGroupOf(site),
                        siteCode
                )
                .orElseGet(() -> siteDeviceRepository.findByBrandAndSiteCode(site.getBrand(), siteCode).orElse(null));

        return cached != null && cached.getDevices() != null
                ? siteMongoMapper.toSiteDevicesResponseDto(cached)
                : new SiteDevicesResponseDto(siteCode, List.of());
    }

    public List<HistoryPointDto> getAllTimeHistory(Brand brand, String siteCode) {
        SiteDocument site = getSiteOrThrow(brand, siteCode);

        SiteHistoryDocument cached = findHistory(site, HistoryGranularity.YEARLY, "all").orElse(null);

        return cached != null && cached.getPoints() != null
                ? cached.getPoints()
                : List.of();
    }

    private List<HistoryPointDto> getCachedHistory(
            SiteDocument site,
            HistoryGranularity granularity,
            String bucket
    ) {
        List<HistoryPointDto> points = findHistory(site, granularity, bucket)
                .map(SiteHistoryDocument::getPoints)
                .orElse(List.of());

        if (site.getBrand() == Brand.SOLIS) {
            if (looksLikeSolisUnscopedHistory(points)) {
                log.warn(
                        "Ignored suspicious Solis cached history from /full. site={} granularity={} bucket={} points={}",
                        site.getSiteCode(),
                        granularity,
                        bucket,
                        points.size()
                );
                return List.of();
            }

            SiteHistoryDocument document = findHistory(site, granularity, bucket).orElse(null);
            if (looksLikeSolisAggregateMonthOrYear(document)) {
                return List.of();
            }
        }

        return points;
    }

    private Optional<SiteHistoryDocument> findHistory(
            SiteDocument site,
            HistoryGranularity granularity,
            String bucket
    ) {
        Optional<SiteHistoryDocument> grouped = siteHistoryRepository
                .findByBrandAndProviderAccountGroupAndSiteCodeAndGranularityAndBucket(
                        site.getBrand(),
                        accountGroupOf(site),
                        site.getSiteCode(),
                        granularity,
                        bucket
                );

        if (grouped.isPresent()) {
            return grouped;
        }

        return siteHistoryRepository.findByBrandAndSiteCodeAndGranularityAndBucket(
                site.getBrand(),
                site.getSiteCode(),
                granularity,
                bucket
        );
    }

    private String accountGroupOf(SiteDocument site) {
        if (site != null && site.getProviderAccountGroup() != null && !site.getProviderAccountGroup().isBlank()) {
            return site.getProviderAccountGroup().trim();
        }

        if (site != null && site.getProviderAccountKey() != null && !site.getProviderAccountKey().isBlank()) {
            return site.getProviderAccountKey().trim();
        }

        return "primary";
    }

    private void validateHistoryInput(String type, String value) {
        if (type == null || value == null || value.isBlank()) {
            throw new IllegalArgumentException("type and value are required");
        }

        switch (type.toLowerCase()) {
            case "day" -> DateTimeUtils.parseStrictDay(value);
            case "month" -> DateTimeUtils.parseStrictMonth(value);
            case "year" -> DateTimeUtils.parseStrictYear(value);
            default -> throw new IllegalArgumentException("Invalid type. Expected day, month, or year");
        }
    }

    private boolean looksLikeSolisAggregateMonthOrYear(SiteHistoryDocument cached) {
        if (cached == null
                || cached.getGranularity() == null
                || cached.getBucket() == null
                || cached.getPoints() == null
                || cached.getPoints().size() != 1) {
            return false;
        }

        if (cached.getGranularity() != HistoryGranularity.MONTH
                && cached.getGranularity() != HistoryGranularity.YEAR) {
            return false;
        }

        try {
            if (cached.getGranularity() == HistoryGranularity.MONTH) {
                YearMonth bucket = YearMonth.parse(cached.getBucket());
                YearMonth current = YearMonth.now(TUNIS_ZONE);

                if (bucket.isBefore(current)) {
                    return true;
                }

                return bucket.equals(current) && LocalDate.now(TUNIS_ZONE).getDayOfMonth() > 1;
            }

            if (cached.getGranularity() == HistoryGranularity.YEAR) {
                int bucketYear = Integer.parseInt(cached.getBucket());
                int currentYear = LocalDate.now(TUNIS_ZONE).getYear();

                if (bucketYear < currentYear) {
                    return true;
                }

                return bucketYear == currentYear && LocalDate.now(TUNIS_ZONE).getMonthValue() > 1;
            }
        } catch (Exception ignored) {
            return false;
        }

        return false;
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

    private List<SiteDocument> getAllSitesOrBootstrap() {
        List<SiteDocument> sites = siteRepository.findAllByOrderBySiteNameAsc()
                .stream()
                .filter(this::isVisibleSite)
                .toList();

        if (!sites.isEmpty()) {
            return sites;
        }

        bootstrapAllBrandsIfNeeded();

        sites = siteRepository.findAllByOrderBySiteNameAsc()
                .stream()
                .filter(this::isVisibleSite)
                .toList();

        if (sites.isEmpty()) {
            throw new ResourceNotFoundException("No sites available");
        }

        return sites;
    }

    private List<SiteDocument> getSitesByBrandOrBootstrap(Brand brand) {
        List<SiteDocument> sites = siteRepository.findAllByBrandOrderBySiteNameAsc(brand)
                .stream()
                .filter(this::isVisibleSite)
                .toList();

        if (!sites.isEmpty()) {
            return sites;
        }

        try {
            monitoringSitesSyncRegistry
                    .getRequiredSyncService(brand)
                    .bootstrapIfEmpty();
        } catch (Exception e) {
            log.warn("Bootstrap failed for brand {}: {}", brand, e.getMessage());
        }

        sites = siteRepository.findAllByBrandOrderBySiteNameAsc(brand)
                .stream()
                .filter(this::isVisibleSite)
                .toList();

        if (sites.isEmpty()) {
            throw new ResourceNotFoundException("No sites available for brand: " + brand);
        }

        return sites;
    }

    private boolean isVisibleSite(SiteDocument site) {
        return currentSiteAccessService.canAccess(site);
    }

    private SiteDocument getSiteOrThrow(Brand brand, String siteCode) {
        SiteDocument site = siteRepository
                .findByBrandAndSiteCode(brand, siteCode)
                .orElse(null);

        if (isVisibleSite(site)) {
            return site;
        }

        try {
            monitoringSitesSyncRegistry
                    .getRequiredSyncService(brand)
                    .bootstrapIfEmpty();
        } catch (Exception e) {
            log.warn("Bootstrap failed for brand {}: {}", brand, e.getMessage());
        }

        site = siteRepository
                .findByBrandAndSiteCode(brand, siteCode)
                .orElse(null);

        if (isVisibleSite(site)) {
            return site;
        }

        throw new ResourceNotFoundException(
                "Site not found or inactive: " + siteCode + " for brand: " + brand
        );
    }

    private void bootstrapAllBrandsIfNeeded() {
        for (Brand brand : monitoringSitesSyncRegistry.getAvailableBrands()) {
            try {
                monitoringSitesSyncRegistry
                        .getRequiredSyncService(brand)
                        .bootstrapIfEmpty();
            } catch (Exception e) {
                log.warn("Bootstrap failed for brand {}: {}", brand, e.getMessage());
            }
        }
    }
}