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
import com.fusionsolar.monitoring.service.sync.MonitoringSitesSyncRegistry;
import com.fusionsolar.monitoring.service.sync.SiteEnergyKpiReconciliationService;
import com.fusionsolar.monitoring.util.DateTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
         * /full doit rester Mongo-only.
         * Les historiques externes sont chargés par /history en lazy/cache,
         * sinon Solis 429 et Huawei ACCESS_FREQUENCY_IS_TOO_HIGH reviennent.
         */
        return new SiteDetailWithHistoryDto(
                detail,
                getCachedHistory(site, HistoryGranularity.DAY, today),
                getCachedHistory(site, HistoryGranularity.MONTH, currentMonth),
                getCachedHistory(site, HistoryGranularity.YEAR, currentYear),
                getCachedHistory(site, HistoryGranularity.YEARLY, "all")
        );
    }

    public List<HistoryPointDto> getHistory(Brand brand, String siteCode, String type, String value) {
        validateHistoryInput(type, value);

        SiteDocument site = getSiteOrThrow(brand, siteCode);
        HistoryGranularity granularity = siteMongoMapper.toGranularity(type);

        /*
         * Mongo-only: les appels API fournisseur ne doivent plus partir depuis une requête UI.
         * Le worker HuaweiBatchHistoryBackfillService + SiteHistoryBackfillService remplissent Mongo en arrière-plan.
         * Résultat: pas de spike quand un utilisateur ouvre une courbe, donc beaucoup moins de 407/429.
         */
        SiteHistoryDocument cached = findHistory(site, granularity, value).orElse(null);

        if (cached == null || cached.getPoints() == null) {
            return List.of();
        }

        List<HistoryPointDto> points = cached.getPoints();
        siteEnergyKpiReconciliationService.reconcileAfterHistoryLoad(site, type, value, points);
        return points;
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
        return findHistory(site, granularity, bucket)
                .map(SiteHistoryDocument::getPoints)
                .orElse(List.of());
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

