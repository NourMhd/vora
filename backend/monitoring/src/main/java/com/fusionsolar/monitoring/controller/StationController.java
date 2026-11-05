package com.fusionsolar.monitoring.controller;

import com.fusionsolar.monitoring.dto.DashboardSummaryDto;
import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.dto.SiteDetailDto;
import com.fusionsolar.monitoring.dto.SiteDetailWithHistoryDto;
import com.fusionsolar.monitoring.dto.SiteDevicesResponseDto;
import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.provider.MonitoringProviderRegistry;
import com.fusionsolar.monitoring.service.StationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class StationController {

    private final StationService stationService;
    private final MonitoringProviderRegistry monitoringProviderRegistry;

    @GetMapping("/brands")
    public List<String> getAvailableBrands() {
        return monitoringProviderRegistry.getAvailableBrands().stream()
                .map(Enum::name)
                .toList();
    }

    @GetMapping("/dashboard/summary")
    public DashboardSummaryDto getDashboardSummary() {
        return stationService.getDashboardSummary();
    }

    @GetMapping("/brands/{brand}/dashboard/summary")
    public DashboardSummaryDto getDashboardSummaryByBrand(@PathVariable String brand) {
        return stationService.getDashboardSummary(parseBrand(brand));
    }

    @GetMapping("/sites")
    public List<SiteSummaryDto> getSitesSummary() {
        return stationService.getSitesSummary();
    }

    @GetMapping("/brands/{brand}/sites")
    public List<SiteSummaryDto> getSitesSummaryByBrand(@PathVariable String brand) {
        return stationService.getSitesSummary(parseBrand(brand));
    }

    @GetMapping("/brands/{brand}/sites/{siteCode}")
    public SiteDetailDto getSiteDetail(
            @PathVariable String brand,
            @PathVariable String siteCode
    ) {
        return stationService.getSiteDetail(parseBrand(brand), siteCode);
    }

    @GetMapping("/brands/{brand}/sites/{siteCode}/full")
    public SiteDetailWithHistoryDto getSiteDetailWithHistory(
            @PathVariable String brand,
            @PathVariable String siteCode
    ) {
        SiteDetailWithHistoryDto dto =
                stationService.getSiteDetailWithHistory(parseBrand(brand), siteCode);

        log.info(
                "CONTROLLER FULL site={} status={} day={} month={} year={} total={}",
                dto.getSummary().getSiteCode(),
                dto.getSummary().getStatus(),
                dto.getSummary().getDayEnergyKwh(),
                dto.getSummary().getMonthEnergyKwh(),
                dto.getSummary().getYearEnergyKwh(),
                dto.getSummary().getTotalEnergyKwh()
        );

        return dto;
    }

    @org.springframework.web.bind.annotation.PostMapping("/brands/{brand}/sites/{siteCode}/history/collect")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public java.util.Map<String, String> collectHistory(@PathVariable String brand,
            @PathVariable String siteCode, @RequestParam String type, @RequestParam String value) {
        stationService.requestHistoryCollection(parseBrand(brand), siteCode, type, value);
        return java.util.Map.of("status", "QUEUED");
    }

    @GetMapping("/brands/{brand}/sites/{siteCode}/history/latest-periods")
    public java.util.Map<String, String> latestCollectedPeriods(@PathVariable String brand, @PathVariable String siteCode) {
        return stationService.getLatestCollectedPeriods(parseBrand(brand), siteCode);
    }

    @GetMapping("/brands/{brand}/sites/{siteCode}/history")
    public List<HistoryPointDto> getHistory(
            @PathVariable String brand,
            @PathVariable String siteCode,
            @RequestParam String type,
            @RequestParam String value
    ) {
        return stationService.getHistory(parseBrand(brand), siteCode, type, value);
    }


    @GetMapping("/brands/{brand}/sites/{siteCode}/history/all-time")
    public List<HistoryPointDto> getAllTimeHistory(
            @PathVariable String brand,
            @PathVariable String siteCode
    ) {
        return stationService.getAllTimeHistory(parseBrand(brand), siteCode);
    }

    @GetMapping("/brands/{brand}/sites/{siteCode}/devices")
    public SiteDevicesResponseDto getSiteDevices(
            @PathVariable String brand,
            @PathVariable String siteCode
    ) {
        return stationService.getSiteDevices(parseBrand(brand), siteCode);
    }

    private Brand parseBrand(String value) {
        try {
            return Brand.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid brand: " + value);
        }
    }
}