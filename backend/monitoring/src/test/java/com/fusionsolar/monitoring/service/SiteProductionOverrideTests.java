package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.provider.mapper.SiteMongoMapper;
import com.fusionsolar.monitoring.util.SiteProductionPolicy;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

class SiteProductionOverrideTests {
    private final ProductionEstimationService service = new ProductionEstimationService();
    private final LocalDate day = LocalDate.of(2026, 1, 1);
    private static void eq(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected);
    }
    private SiteDocument document(String name) {
        var doc = new SiteDocument();
        doc.setSiteCode("NE=12345"); doc.setSiteName(name);
        doc.setInstalledPowerKw(10.0); doc.setSwapDate(day);
        doc.setDayEnergyKwh(120.0); doc.setMonthEnergyKwh(500.0);
        doc.setYearEnergyKwh(1000.0); doc.setTotalEnergyKwh(2000.0);
        return doc;
    }
    private ProductionEstimationService.DailySiteProduction daily(SiteDocument doc, double value) {
        return new ProductionEstimationService.DailySiteProduction(doc, doc.getSiteCode(),
                doc.getSiteName(), "SFA", "Zone B", 10.0, doc.getSwapDate(),
                Map.of(day, value), Set.of(day));
    }
    @Test void matchesOnlyTheExactSiteRegardlessOfCase() {
        eq(SiteProductionPolicy.forcesZero(document(" sfa_0138 ")), true);
        eq(SiteProductionPolicy.forcesZero(document("SFA_01380")), false);
        eq(SiteProductionPolicy.forcesZero(document("SFA_0139")), false);
        var doc = document("Other name"); doc.setSiteCode("SFA_0138");
        eq(SiteProductionPolicy.forcesZero(doc), true);
    }
    @Test void overridesPresentAndMissingProductionWithoutUsingPeers() {
        var target = daily(document("SFA_0138"), 999.0);
        var peer = daily(document("SFA_0139"), 200.0);
        var result = service.computeDisplayedProduction(target, List.of(peer), day, day.plusDays(5));
        eq(result.realKwh(), 0.0); eq(result.estimatedKwh(), 0.0); eq(result.displayedKwh(), 0.0);
        eq(service.decideDailyProduction(target, List.of(peer), day).effectiveValue(), 0.0);
        eq(service.decideDailyProduction(target, List.of(peer), day.plusDays(1)).effectiveValue(), 0.0);
        eq(service.decideDailyProduction(target, day.plusDays(1), Map.of()).effectiveValue(), 0.0);
        target.document().setSwapDate(null);
        var missingSwap = daily(target.document(), 999.0);
        eq(service.computeDisplayedProduction(missingSwap, day, day).displayedKwh(), 0.0);
    }
    @Test void neverUsesTheOverrideAsAReferenceForAnotherSite() {
        var target = daily(document("SFA_0139"), 20.0);
        var excluded = daily(document("SFA_0138"), 999.0);
        var result = service.computeDisplayedProduction(target, List.of(excluded), day, day.plusDays(1));
        eq(result.realKwh(), 20.0); eq(result.estimatedKwh(), 20.0); eq(result.displayedKwh(), 40.0);
    }
    @Test void zeroesSummaryDetailAndDashboardWithoutChangingStoredValues() {
        var doc = document("SFA_0138"); var mapper = new SiteMongoMapper();
        var summary = mapper.toSiteSummaryDto(doc); var detail = mapper.toSiteDetailDto(doc);
        eq(summary.getDayEnergyKwh(), 0.0); eq(summary.getMonthEnergyKwh(), 0.0);
        eq(summary.getYearEnergyKwh(), 0.0); eq(summary.getTotalEnergyKwh(), 0.0);
        eq(detail.getDayEnergyKwh(), 0.0); eq(detail.getMonthEnergyKwh(), 0.0);
        eq(detail.getYearEnergyKwh(), 0.0); eq(detail.getTotalEnergyKwh(), 0.0);
        var ordinary = document("SFA_0139");
        eq(mapper.toDashboardSummary(List.of(doc, ordinary)).getTotalDayEnergyKwh(), 120.0);
        eq(doc.getDayEnergyKwh(), 120.0); eq(doc.getTotalEnergyKwh(), 2000.0);
    }
    @Test void zeroesHistoryCopiesAndPreservesTimestampsAndOtherSites() {
        var point = new HistoryPointDto(123L, 99.0);
        var points = List.of(point);
        var result = SiteProductionPolicy.history(document("SFA_0138"), points);
        eq(result.get(0).getValue(), 0.0); eq(result.get(0).getCollectTime(), 123L);
        eq(point.getValue(), 99.0);
        eq(SiteProductionPolicy.history(document("SFA_0139"), points).get(0).getValue(), 99.0);
    }
    @Test void reportShowsZeroEvenWithoutSwapAndLeavesTheoreticalForNormalSites() throws Exception {
        var doc = document("SFA_0138"); doc.setSwapDate(null);
        var report = new PerformanceReportService(null, null, null, service, null, null);
        var rangeClass = Class.forName(PerformanceReportService.class.getName() + "$ReportRange");
        var constructor = rangeClass.getDeclaredConstructor(LocalDate.class, LocalDate.class);
        constructor.setAccessible(true);
        var method = PerformanceReportService.class.getDeclaredMethod("toRow", SiteDocument.class,
                rangeClass, LocalDate.class, ProductionEstimationService.DailySiteProduction.class, List.class);
        method.setAccessible(true);
        var production = daily(doc, 999.0);
        var row = (com.fusionsolar.monitoring.dto.PerformanceReportRowDto) method.invoke(report,
                doc, constructor.newInstance(day, day.plusDays(2)), day.plusDays(2), production, List.of(production));
        eq(row.getCapturedKwh(), 0.0); eq(row.getEstimatedMissingKwh(), 0.0);
        eq(row.getActualKwh(), 0.0); eq(row.getDataStatus(), "FORCED_ZERO");
    }
    @Test void bothComparisonTablesShowZeroInsteadOfAMissingValue() throws Exception {
        var comparison = new DailyComparisonService(null, null, service, null);
        var doc = document("SFA_0138"); doc.setSwapDate(null);
        var production = daily(doc, 999.0);
        var historical = DailyComparisonService.class.getDeclaredMethod("historicalCell",
                ProductionEstimationService.DailySiteProduction.class, LocalDate.class);
        historical.setAccessible(true);
        var estimated = DailyComparisonService.class.getDeclaredMethod("estimatedCell",
                ProductionEstimationService.DailySiteProduction.class, List.class, LocalDate.class);
        estimated.setAccessible(true);
        var first = (com.fusionsolar.monitoring.dto.DailyComparisonCellDto)
                historical.invoke(comparison, production, day);
        var second = (com.fusionsolar.monitoring.dto.DailyComparisonCellDto)
                estimated.invoke(comparison, production, List.of(production), day.plusDays(2));
        eq(first.getValue(), 0.0); eq(second.getValue(), 0.0);
    }

    @Test void preservesNullEnergyForOrdinarySites() {
        eq(SiteProductionPolicy.energy(document("SFA_0139"), null), null);
        eq(SiteProductionPolicy.energy(null, null), null);
        eq(SiteProductionPolicy.energy(document("SFA_0138"), null), 0.0);
        var ordinary = document("SFA_0139"); ordinary.setDayEnergyKwh(null);
        eq(new SiteMongoMapper().toSiteSummaryDto(ordinary).getDayEnergyKwh(), 0.0);
    }

}
