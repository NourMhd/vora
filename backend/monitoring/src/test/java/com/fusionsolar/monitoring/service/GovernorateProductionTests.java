package com.fusionsolar.monitoring.service;

import org.junit.jupiter.api.Test;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.service.ProductionEstimationService.DailySiteProduction;
import java.time.LocalDate;
import java.util.*;

class GovernorateProductionTests {
    private final ProductionEstimationService service = new ProductionEstimationService();
    private final LocalDate start = LocalDate.of(2025, 1, 1);
    private static void eq(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected);
    }
    private DailySiteProduction site(String code, String gov, String zone, double power,
                                      LocalDate swap, Map<LocalDate, Double> values) {
        SiteDocument doc = new SiteDocument();
        doc.setSiteCode(code);
        return new DailySiteProduction(doc, code, code, gov, zone, power, swap, values, values.keySet());
    }
    @Test
    void selectsSpecificYieldRatherThanAbsoluteEnergyAndAllowsOneValidDay() {
        var target = site("ARI_target", "ARI", "Zone A", 10, start,
                Map.of(start, 10.0, start.plusDays(1), 0.0, start.plusDays(2), 9.999));
        var best = site("ARI_best", "ARI", "Zone A", 5, start, Map.of(start, 30.0)); // 6
        var larger = site("ARI_big", "ARI", "Zone A", 100, start, Map.of(start, 400.0)); // 4
        var otherGov = site("MED_1", "MED", "Zone C", 1, start, Map.of(start, 100.0));
        var peers = List.of(target, best, larger, otherGov);
        var r = service.computeDisplayedProduction(target, peers, start, start.plusDays(3));
        eq(r.realKwh(), 10.0); eq(r.monitoredDays(), 1); eq(r.estimatedDays(), 3);
        eq(r.estimatedKwh(), 180.0); eq(r.displayedKwh(), 190.0);
        r = service.computeDisplayedProduction(best, peers, start, start.plusDays(3));
        eq(r.realKwh(), 30.0); eq(r.estimatedKwh(), 90.0); eq(r.displayedKwh(), 120.0);
    }
    @Test
    void averagesOnlyValidRealDaysAndNeverEstimatedOrLowValues() {
        var target = site("ARI_1", "ARI", "Zone A", 10, start, Map.of());
        var peer = site("ARI_2", "ARI", "Zone A", 5, start,
                Map.of(start, 10.0, start.plusDays(1), 20.0, start.plusDays(2), 0.0,
                        start.plusDays(3), 4.999));
        var r = service.computeDisplayedProduction(target, List.of(peer), start, start.plusDays(4));
        eq(r.monitoredDays(), 0); eq(r.estimatedDays(), 5);
        eq(r.estimatedKwh(), 150.0); eq(r.displayedKwh(), 150.0);
    }
    @Test
    void usesDailyTheoreticalAcrossDecadesWithoutAValidGovernorateReference() {
        var target = site("ARI_1", "ARI", "Zone A", 10, start, Map.of());
        var other = site("MED_1", "MED", "Zone C", 1, start, Map.of(start, 99.0));
        var r = service.computeDisplayedProduction(target, List.of(other), start.plusDays(8), start.plusDays(11));
        eq(r.estimatedKwh(), 92.0); eq(r.displayedKwh(), 92.0); // 2*22 + 2*24
        target = site("unknown", null, null, 10, start, Map.of());
        eq(service.computeDisplayedProduction(target, List.of(other), start, start).displayedKwh(), null);
    }
    @Test
    void clipsEachCandidateToItsOwnSwapAndTheCommonFilter() {
        var target = site("ARI_1", "ARI", "Zone A", 10, start.plusDays(2), Map.of());
        var peer = site("ARI_2", "ARI", "Zone A", 5, start.plusDays(1),
                Map.of(start, 500.0, start.plusDays(1), 20.0, start.plusDays(10), 500.0));
        var r = service.computeDisplayedProduction(target, List.of(peer), start, start.plusDays(3));
        eq(r.estimatedDays(), 2); eq(r.displayedKwh(), 80.0);
        var noSwap = site("ARI_3", "ARI", "Zone A", 1, null, Map.of(start, 1000.0));
        r = service.computeDisplayedProduction(target, List.of(noSwap), start, start.plusDays(3));
        eq(r.displayedKwh(), 44.0);
        eq(service.computeDisplayedProduction(noSwap, List.of(peer), start, start).displayedKwh(), null);
    }
    @Test
    void preservesExactThresholdAndRejectsInvalidPowerOrHistories() {
        var valid = site("ARI_1", "ARI", "Zone A", 10.26, start, Map.of(start, 10.26));
        var r = service.computeDisplayedProduction(valid, start, start);
        eq(r.monitoredDays(), 1); eq(r.displayedKwh(), 10.26);
        var low = site("ARI_2", "ARI", "Zone A", 10.26, start, Map.of(start, 10.25999));
        r = service.computeDisplayedProduction(low, List.of(valid), start, start);
        eq(r.monitoredDays(), 0); eq(r.displayedKwh(), 10.26);
        for (double power : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var bad = site("ARI_bad", "ARI", "Zone A", power, start, Map.of(start, 100.0));
            eq(service.computeDisplayedProduction(bad, start, start).displayedKwh(), null);
        }
        Map<LocalDate,Double> values = new HashMap<>();
        values.put(start, null); values.put(start.plusDays(1), -1.0);
        values.put(start.plusDays(2), Double.NaN); values.put(start.plusDays(3), Double.POSITIVE_INFINITY);
        r = service.computeDisplayedProduction(site("ARI_bad", "ARI", "Zone A", 10, start, values),
                List.of(valid), start, start.plusDays(3));
        eq(r.monitoredDays(), 0); eq(r.estimatedDays(), 4); eq(r.displayedKwh(), 40.0);
    }
}
