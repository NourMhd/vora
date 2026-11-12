package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionEstimationServiceTests {

    private final ProductionEstimationService service = new ProductionEstimationService();

    @Test
    void keepsPositiveRealValueAsMonitored() {
        LocalDate day = LocalDate.of(2025, 3, 3);
        var site = production("ARI_0001", "ARI", "Zone A", 10.0, day.minusDays(2), Map.of(day, 12.5));

        var decision = service.decideDailyProduction(site, List.of(site), day);

        assertThat(decision.monitored()).isTrue();
        assertThat(decision.estimated()).isFalse();
        assertThat(decision.effectiveValue()).isEqualTo(12.5);
    }

    @Test
    void usesOnlySiteAverageFromThirtyReliableDays() {
        LocalDate missingDay = LocalDate.of(2025, 1, 31);
        Map<LocalDate, Double> values = consecutiveValues(LocalDate.of(2025, 1, 1), 30, 25.0);
        var target = production("ARI_0001", "ARI", "Zone A", 10.0,
                LocalDate.of(2025, 1, 1), values);

        var decision = service.decideDailyProduction(target, List.of(target), missingDay);

        assertThat(decision.effectiveValue()).isEqualTo(25.0);
        assertThat(decision.status()).contains("SITE_HISTORY_ESTIMATED");
    }

    @Test
    void weightsLimitedSiteHistoryWithReliablePeerFromSameGovernorate() {
        LocalDate missingDay = LocalDate.of(2025, 3, 25);
        var target = production("ARI_0001", "ARI", "Zone A", 10.0,
                LocalDate.of(2025, 3, 1), consecutiveValues(LocalDate.of(2025, 3, 1), 10, 20.0));
        var peer = production("ARI_0002", "ARI", "Zone A", 5.0,
                LocalDate.of(2025, 2, 1), consecutiveValues(LocalDate.of(2025, 3, 1), 20, 12.0));

        var decision = service.decideDailyProduction(target, List.of(target, peer), missingDay);

        double confidence = 10.0 / 30.0;
        double peerScaled = (12.0 / 5.0) * 10.0;
        double expected = service.round2(confidence * 20.0 + (1.0 - confidence) * peerScaled);
        assertThat(decision.effectiveValue()).isEqualTo(expected);
        assertThat(decision.status()).contains("REGIONAL_WEIGHTED_ESTIMATED");
    }

    @Test
    void usesReliableRegionalReferenceWhenTargetHasNoRealDay() {
        LocalDate day = LocalDate.of(2025, 3, 25);
        var target = production("ARI_0001", "ARI", "Zone A", 10.0,
                LocalDate.of(2025, 3, 1), Map.of());
        var peer = production("ARI_0002", "ARI", "Zone A", 5.0,
                LocalDate.of(2025, 2, 1), consecutiveValues(LocalDate.of(2025, 3, 1), 20, 12.0));

        var decision = service.decideDailyProduction(target, List.of(target, peer), day);

        assertThat(decision.effectiveValue()).isEqualTo(24.0);
        assertThat(decision.status()).contains("REGIONAL_REFERENCE_ESTIMATED");
    }

    @Test
    void fallsBackToDailyTheoreticalValueWithoutReliableRegionalReference() {
        LocalDate day = LocalDate.of(2025, 3, 25);
        var target = production("ARI_0001", "ARI", "Zone A", 10.0,
                LocalDate.of(2025, 3, 1), Map.of());

        var decision = service.decideDailyProduction(target, List.of(target), day);

        assertThat(decision.effectiveValue()).isEqualTo(service.theoreticalDailyKwh(target, day));
        assertThat(decision.status()).contains("THEORETICAL_FALLBACK_ESTIMATED");
    }

    @Test
    void ignoresPeerFromAnotherGovernorate() {
        LocalDate day = LocalDate.of(2025, 3, 25);
        var target = production("BAR_0001", "BAR", "Zone A", 10.0,
                LocalDate.of(2025, 3, 1), Map.of());
        var otherGovernorate = production("MED_0001", "MED", "Zone C", 5.0,
                LocalDate.of(2025, 2, 1), consecutiveValues(LocalDate.of(2025, 3, 1), 20, 50.0));

        var decision = service.decideDailyProduction(target, List.of(target, otherGovernorate), day);

        assertThat(decision.effectiveValue()).isEqualTo(service.theoreticalDailyKwh(target, day));
        assertThat(decision.status()).contains("THEORETICAL_FALLBACK_ESTIMATED");
    }

    @Test
    void ignoresPeerWithFewerThanTwentyReliableDays() {
        LocalDate day = LocalDate.of(2025, 3, 25);
        var target = production("ARI_0001", "ARI", "Zone A", 10.0,
                LocalDate.of(2025, 3, 1), Map.of());
        var insufficientPeer = production("ARI_0002", "ARI", "Zone A", 5.0,
                LocalDate.of(2025, 2, 1), consecutiveValues(LocalDate.of(2025, 3, 1), 19, 12.0));

        var decision = service.decideDailyProduction(target, List.of(target, insufficientPeer), day);

        assertThat(decision.effectiveValue()).isEqualTo(service.theoreticalDailyKwh(target, day));
        assertThat(decision.status()).contains("THEORETICAL_FALLBACK_ESTIMATED");
    }

    @Test
    void capsExceptionalPeerAtOneHundredTwentyPercentOfTheoreticalAverage() {
        LocalDate day = LocalDate.of(2025, 3, 25);
        var target = production("ARI_0001", "ARI", "Zone A", 10.0,
                LocalDate.of(2025, 3, 1), Map.of());
        var exceptionalPeer = production("ARI_0002", "ARI", "Zone A", 5.0,
                LocalDate.of(2025, 2, 1), consecutiveValues(LocalDate.of(2025, 3, 1), 20, 100.0));

        var decision = service.decideDailyProduction(target, List.of(target, exceptionalPeer), day);

        YearMonth month = YearMonth.of(2025, 3);
        double theoreticalMonthAverage = 0.0;
        for (int currentDay = 1; currentDay <= month.lengthOfMonth(); currentDay++) {
            theoreticalMonthAverage += service.theoreticalDailyKwh(target, month.atDay(currentDay));
        }
        theoreticalMonthAverage /= month.lengthOfMonth();
        assertThat(decision.effectiveValue()).isEqualTo(
                service.round2(theoreticalMonthAverage * ProductionEstimationService.MAX_PEER_TO_THEORETICAL_RATIO)
        );
    }

    @Test
    void computesDisplayedProductionUsingOnlySelectedPeriodAverage() {
        LocalDate start = LocalDate.of(2025, 3, 1);
        LocalDate end = LocalDate.of(2025, 3, 12);
        var target = production("ARI_0001", "ARI", "Zone A", 10.0, start,
                consecutiveValues(start, 10, 20.0));
        var peer = production("ARI_0002", "ARI", "Zone A", 5.0, start.minusMonths(1),
                consecutiveValues(start, 20, 12.0));

        var result = service.computeDisplayedProduction(target, List.of(target, peer), start, end);

        assertThat(result.realKwh()).isEqualTo(200.0);
        assertThat(result.monitoredDays()).isEqualTo(10);
        assertThat(result.estimatedDays()).isEqualTo(2);
        assertThat(result.estimatedKwh()).isEqualTo(40.0);
        assertThat(result.displayedKwh()).isEqualTo(240.0);
    }

    @Test
    void estimatesWithoutInstalledPower() {
        LocalDate day = LocalDate.of(2025, 3, 1);
        var target = production("ARI_0001", "ARI", "Zone A", 0.0, day, Map.of(day, 20.0));

        var result = service.computeDisplayedProduction(target, List.of(target), day, day.plusDays(2));

        assertThat(result.realKwh()).isEqualTo(20.0);
        assertThat(result.estimatedKwh()).isEqualTo(40.0);
        assertThat(result.displayedKwh()).isEqualTo(60.0);
    }

    @Test
    void estimatesOneHundredMissingDaysFromTwoHundredMonitoredDays() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        var target = production("ARI_1", "ARI", "Zone A", 10, start,
                consecutiveValues(start, 200, 100));
        var result = service.computeDisplayedProduction(target, start, start.plusDays(299));
        assertThat(result.realKwh()).isEqualTo(20000.0);
        assertThat(result.estimatedKwh()).isEqualTo(10000.0);
        assertThat(result.displayedKwh()).isEqualTo(30000.0);
        assertThat(result.monitoredDays()).isEqualTo(200);
        assertThat(result.estimatedDays()).isEqualTo(100);
    }

    @Test
    void countsZeroAsMonitoredAndIgnoresValuesOutsidePeriodAndBeforeCommissioning() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        var target = production("ARI_1", "ARI", "Zone A", 10, start.plusDays(1),
                Map.of(start, 999.0, start.plusDays(1), 0.0,
                        start.plusDays(2), 20.0, start.plusDays(20), 999.0));
        var result = service.computeDisplayedProduction(target, start, start.plusDays(3));
        assertThat(result.monitoredDays()).isEqualTo(2);
        assertThat(result.estimatedDays()).isEqualTo(1);
        assertThat(result.realKwh()).isEqualTo(20.0);
        assertThat(result.estimatedKwh()).isEqualTo(10.0);
        assertThat(result.displayedKwh()).isEqualTo(30.0);
    }

    @Test
    void noMonitoredDaysMeansUnavailableAndInvalidValuesAreMissing() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        var values = new HashMap<LocalDate, Double>();
        values.put(start, null);
        values.put(start.plusDays(1), -1.0);
        values.put(start.plusDays(2), Double.NaN);
        values.put(start.plusDays(3), Double.POSITIVE_INFINITY);
        var target = production("ARI_1", "ARI", "Zone A", 10, start, values);
        var result = service.computeDisplayedProduction(target, start, start.plusDays(4));
        assertThat(result.monitoredDays()).isZero();
        assertThat(result.estimatedDays()).isEqualTo(5);
        assertThat(result.displayedKwh()).isNull();
    }

    @Test
    void fullyMonitoredZeroProductionIsAvailable() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        var target = production("ARI_1", "ARI", "Zone A", 10, start, Map.of(start, 0.0));
        var result = service.computeDisplayedProduction(target, start, start);
        assertThat(result.displayedKwh()).isEqualTo(0.0);
        assertThat(result.monitoredDays()).isEqualTo(1);
        assertThat(result.estimatedDays()).isZero();
    }

    private Map<LocalDate, Double> consecutiveValues(LocalDate start, int days, double dailyKwh) {
        Map<LocalDate, Double> values = new HashMap<>();
        for (int i = 0; i < days; i++) {
            values.put(start.plusDays(i), dailyKwh);
        }
        return values;
    }

    private ProductionEstimationService.DailySiteProduction production(
            String siteCode,
            String governorate,
            String zone,
            double installedPowerKw,
            LocalDate swapDate,
            Map<LocalDate, Double> values
    ) {
        SiteDocument document = new SiteDocument();
        document.setBrand(Brand.HUAWEI);
        document.setSiteCode(siteCode);
        document.setSiteName(governorate + " test");
        document.setInstalledPowerKw(installedPowerKw);
        document.setSwapDate(swapDate);

        return new ProductionEstimationService.DailySiteProduction(
                document,
                document.getSiteCode(),
                document.getSiteName(),
                governorate,
                zone,
                document.getInstalledPowerKw(),
                document.getSwapDate(),
                values,
                Set.copyOf(values.keySet())
        );
    }
}
