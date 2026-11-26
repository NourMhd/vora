package com.fusionsolar.monitoring.controller;

import com.fusionsolar.monitoring.dto.DailyComparisonReportDto;
import com.fusionsolar.monitoring.dto.DailyComparisonSelectableSiteDto;
import com.fusionsolar.monitoring.service.DailyComparisonService;
import com.fusionsolar.monitoring.service.PerformanceReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/comparatif")
public class DailyComparisonController {

    private final DailyComparisonService dailyComparisonService;
    private final PerformanceReportService performanceReportService;

    @GetMapping("/monthly")
    public List<PerformanceReportService.MonthlyProduction> getMonthlyComparison(
            @RequestParam @DateTimeFormat(pattern = "uuuu-MM") YearMonth startMonth,
            @RequestParam @DateTimeFormat(pattern = "uuuu-MM") YearMonth endMonth
    ) {
        return performanceReportService.getMonthlyComparison(startMonth, endMonth);
    }

    /**
     * Nouvelle route propre : tous les sites disponibles.
     */
    @GetMapping("/sites")
    public List<DailyComparisonSelectableSiteDto> getSelectableSites() {
        return dailyComparisonService.getSelectableSites();
    }

    /**
     * Ancienne route gardée pour compatibilité avec le frontend actuel.
     * Elle retourne maintenant tous les sites, pas seulement ARI.
     */
    @GetMapping("/ari/sites")
    public List<DailyComparisonSelectableSiteDto> getAriSelectableSitesCompatibility() {
        return dailyComparisonService.getSelectableSites();
    }

    /**
     * Nouvelle route propre pour le rapport comparatif.
     */
    @GetMapping
    public DailyComparisonReportDto getComparison(
            @RequestParam(required = false) String siteCodes,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate
    ) {
        return dailyComparisonService.getAriComparison(siteCodes, startDate, endDate);
    }

    /**
     * Ancienne route gardée pour compatibilité avec le frontend actuel.
     */
    @GetMapping("/ari")
    public DailyComparisonReportDto getAriComparisonCompatibility(
            @RequestParam(required = false) String siteCodes,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate
    ) {
        return dailyComparisonService.getAriComparison(siteCodes, startDate, endDate);
    }
}