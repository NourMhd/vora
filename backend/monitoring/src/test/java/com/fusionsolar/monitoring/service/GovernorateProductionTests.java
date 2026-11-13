package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.service.ProductionEstimationService.DailySiteProduction;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GovernorateProductionTests {

    private final ProductionEstimationService service =
            new ProductionEstimationService();

    private final LocalDate start = LocalDate.of(2025, 1, 1);

    private DailySiteProduction site(
            String code,
            String governorate,
            String zone,
            double power,
            LocalDate swap,
            Map<LocalDate, Double> values
    ) {
        SiteDocument document = new SiteDocument();
        document.setSiteCode(code);

        return new DailySiteProduction(
                document,
                code,
                code,
                governorate,
                zone,
                power,
                swap,
                values,
                values.keySet()
        );
    }

    private static void kwh(Double actual, double expected) {
        assertNotNull(actual);
        assertEquals(expected, actual.doubleValue(), 0.000001);
    }

    @Test
    void selectsSpecificYieldAndPreservesPositiveProductionBelowOneKwhPerKwc() {
        var target = site(
                "ARI_target", "ARI", "Zone A", 10, start,
                Map.of(
                        start, 10.0,
                        start.plusDays(1), 0.0,
                        start.plusDays(2), 9.999
                )
        );

        // 30 / 5 = 6 kWh/kWc : meilleure référence.
        var best = site(
                "ARI_best", "ARI", "Zone A", 5, start,
                Map.of(start, 30.0)
        );

        // Plus de kWh absolus, mais seulement 4 kWh/kWc.
        var larger = site(
                "ARI_big", "ARI", "Zone A", 100, start,
                Map.of(start, 400.0)
        );

        // Un autre gouvernorat ne doit pas servir de référence.
        var otherGovernorate = site(
                "MED_1", "MED", "Zone C", 1, start,
                Map.of(start, 100.0)
        );

        var peers = List.of(target, best, larger, otherGovernorate);

        var result = service.computeDisplayedProduction(
                target, peers, start, start.plusDays(3)
        );

        // Réel : 10 + 9.999 = 19.999, arrondi à 20.
        // Estimé : 2 jours x 6 kWh/kWc x 10 kWc = 120.
        kwh(result.realKwh(), 20.0);
        assertEquals(2, result.monitoredDays());
        assertEquals(2, result.estimatedDays());
        kwh(result.estimatedKwh(), 120.0);
        kwh(result.displayedKwh(), 140.0);

        // Le meilleur site utilise sa propre moyenne pour ses jours absents.
        result = service.computeDisplayedProduction(
                best, peers, start, start.plusDays(3)
        );

        kwh(result.realKwh(), 30.0);
        assertEquals(1, result.monitoredDays());
        assertEquals(3, result.estimatedDays());
        kwh(result.estimatedKwh(), 90.0);
        kwh(result.displayedKwh(), 120.0);
    }

    @Test
    void averagesAllPositiveRealDaysIncludingLowProductionAndExcludesZero() {
        var target = site(
                "ARI_1", "ARI", "Zone A", 10, start,
                Map.of()
        );

        var peer = site(
                "ARI_2", "ARI", "Zone A", 5, start,
                Map.of(
                        start, 10.0,
                        start.plusDays(1), 20.0,
                        start.plusDays(2), 0.0,
                        start.plusDays(3), 4.999
                )
        );

        var result = service.computeDisplayedProduction(
                target, List.of(peer), start, start.plusDays(4)
        );

        // Les trois jours positifs participent à la moyenne :
        // ((10 + 20 + 4.999) / 3 / 5) x 10 x 5 = 116.663...
        kwh(result.realKwh(), 0.0);
        assertEquals(0, result.monitoredDays());
        assertEquals(5, result.estimatedDays());
        kwh(result.estimatedKwh(), 116.66);
        kwh(result.displayedKwh(), 116.66);
    }

    @Test
    void usesDailyTheoreticalAcrossDecadesWithoutAValidGovernorateReference() {
        var target = site(
                "ARI_1", "ARI", "Zone A", 10, start,
                Map.of()
        );

        var other = site(
                "MED_1", "MED", "Zone C", 1, start,
                Map.of(start, 99.0)
        );

        var result = service.computeDisplayedProduction(
                target, List.of(other),
                start.plusDays(8), start.plusDays(11)
        );

        // Deux jours à 22 kWh et deux jours à 24 kWh.
        kwh(result.estimatedKwh(), 92.0);
        kwh(result.displayedKwh(), 92.0);

        var unknown = site(
                "unknown", null, null, 10, start,
                Map.of()
        );

        assertNull(service.computeDisplayedProduction(
                unknown, List.of(other), start, start
        ).displayedKwh());
    }

    @Test
    void clipsEachCandidateToItsOwnSwapAndTheCommonFilter() {
        var target = site(
                "ARI_1", "ARI", "Zone A", 10,
                start.plusDays(2), Map.of()
        );

        var peer = site(
                "ARI_2", "ARI", "Zone A", 5,
                start.plusDays(1),
                Map.of(
                        start, 500.0,
                        start.plusDays(1), 20.0,
                        start.plusDays(10), 500.0
                )
        );

        var result = service.computeDisplayedProduction(
                target, List.of(peer), start, start.plusDays(3)
        );

        assertEquals(2, result.estimatedDays());
        kwh(result.displayedKwh(), 80.0);

        var noSwap = site(
                "ARI_3", "ARI", "Zone A", 1, null,
                Map.of(start, 1000.0)
        );

        result = service.computeDisplayedProduction(
                target, List.of(noSwap), start, start.plusDays(3)
        );

        kwh(result.displayedKwh(), 44.0);

        assertNull(service.computeDisplayedProduction(
                noSwap, List.of(peer), start, start
        ).displayedKwh());
    }

    @Test
    void preservesValuesAtAndBelowTheFormerThreshold() {
        var reference = site(
                "ARI_1", "ARI", "Zone A", 10.26, start,
                Map.of(start, 10.26)
        );

        var result = service.computeDisplayedProduction(
                reference, start, start
        );

        assertEquals(1, result.monitoredDays());
        assertEquals(0, result.estimatedDays());
        kwh(result.displayedKwh(), 10.26);

        var justBelow = site(
                "ARI_2", "ARI", "Zone A", 10.26, start,
                Map.of(start, 10.25999)
        );

        result = service.computeDisplayedProduction(
                justBelow, List.of(reference), start, start
        );

        // L'arrondi affiche 10.26, mais le jour reste réel.
        assertEquals(1, result.monitoredDays());
        assertEquals(0, result.estimatedDays());
        kwh(result.estimatedKwh(), 0.0);
        kwh(result.displayedKwh(), 10.26);

        var low = site(
                "ARI_3", "ARI", "Zone A", 10.26, start,
                Map.of(
                        start, 5.13,
                        start.plusDays(1), 0.0
                )
        );

        result = service.computeDisplayedProduction(
                low, List.of(reference), start, start.plusDays(1)
        );

        // 0.5 kWh/kWc est conservé ; seul le jour à zéro est estimé.
        assertEquals(1, result.monitoredDays());
        assertEquals(1, result.estimatedDays());
        kwh(result.realKwh(), 5.13);
        kwh(result.estimatedKwh(), 10.26);
        kwh(result.displayedKwh(), 15.39);
    }

    @Test
    void rejectsInvalidPowerAndEstimatesInvalidHistories() {
        var reference = site(
                "ARI_reference", "ARI", "Zone A", 10.26, start,
                Map.of(start, 10.26)
        );

        for (double power : new double[]{
                0, -1, Double.NaN, Double.POSITIVE_INFINITY
        }) {
            var bad = site(
                    "ARI_bad", "ARI", "Zone A", power, start,
                    Map.of(start, 100.0)
            );

            assertNull(service.computeDisplayedProduction(
                    bad, start, start
            ).displayedKwh());
        }

        Map<LocalDate, Double> values = new HashMap<>();
        values.put(start, null);
        values.put(start.plusDays(1), -1.0);
        values.put(start.plusDays(2), Double.NaN);
        values.put(start.plusDays(3), Double.POSITIVE_INFINITY);

        var target = site(
                "ARI_bad", "ARI", "Zone A", 10, start, values
        );

        var result = service.computeDisplayedProduction(
                target, List.of(reference), start, start.plusDays(3)
        );

        assertEquals(0, result.monitoredDays());
        assertEquals(4, result.estimatedDays());
        kwh(result.displayedKwh(), 40.0);
    }
}