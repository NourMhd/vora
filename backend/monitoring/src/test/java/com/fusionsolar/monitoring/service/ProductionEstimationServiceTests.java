package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionEstimationServiceTests {

    private final ProductionEstimationService service = new ProductionEstimationService();

    @Test
    void keepsEveryPositiveRealValueAsMonitored() {
        ProductionEstimationService.DailySiteProduction site = production(Map.of(
                LocalDate.of(2025, 3, 3), 1.25
        ));

        ProductionEstimationService.DailyProductionDecision decision = service.decideDailyProduction(
                site,
                LocalDate.of(2025, 3, 3),
                service.monthlyRealAverages(site)
        );

        assertThat(decision.monitored()).isTrue();
        assertThat(decision.estimated()).isFalse();
        assertThat(decision.effectiveValue()).isEqualTo(1.25);
    }

    @Test
    void estimatesZeroOrAbsentDayWithMonthlyRuleWhenMonthHasPositiveValues() {
        Map<LocalDate, Double> values = new HashMap<>();
        values.put(LocalDate.of(2025, 3, 1), 10.0);
        values.put(LocalDate.of(2025, 3, 2), 12.0);
        values.put(LocalDate.of(2025, 3, 3), 0.0);
        values.put(LocalDate.of(2025, 3, 5), 8.0);
        ProductionEstimationService.DailySiteProduction site = production(values);

        Map<YearMonth, Double> monthlyAverages = service.monthlyRealAverages(site);
        Double theoretical = service.theoreticalMonthlyAverageKwh(site, YearMonth.of(2025, 3));

        ProductionEstimationService.DailyProductionDecision zeroDecision = service.decideDailyProduction(
                site,
                LocalDate.of(2025, 3, 3),
                monthlyAverages
        );
        ProductionEstimationService.DailyProductionDecision absentDecision = service.decideDailyProduction(
                site,
                LocalDate.of(2025, 3, 4),
                monthlyAverages
        );

        assertThat(monthlyAverages.get(YearMonth.of(2025, 3))).isEqualTo(10.0);
        assertThat(zeroDecision.estimated()).isTrue();
        assertThat(zeroDecision.effectiveValue()).isEqualTo(service.round2((10.0 + theoretical) / 2.0));
        assertThat(absentDecision.estimated()).isTrue();
        assertThat(absentDecision.effectiveValue()).isEqualTo(service.round2((10.0 + theoretical) / 2.0));
    }

    @Test
    void doesNotEstimateWhenMonthHasNoPositiveRealValue() {
        ProductionEstimationService.DailySiteProduction site = production(Map.of(
                LocalDate.of(2025, 3, 3), 0.0
        ));

        ProductionEstimationService.DailyProductionDecision decision = service.decideDailyProduction(
                site,
                LocalDate.of(2025, 3, 3),
                service.monthlyRealAverages(site)
        );

        assertThat(decision.estimated()).isFalse();
        assertThat(decision.effectiveValue()).isNull();
        assertThat(decision.status()).contains("NO_REAL_AVERAGE");
    }

    @Test
    void computesDisplayedProductionWithMonthlyRealAndTheoreticalAverage() {
        ProductionEstimationService.DailySiteProduction site = production(Map.of(
                LocalDate.of(2025, 3, 1), 10.0,
                LocalDate.of(2025, 3, 2), 12.0,
                LocalDate.of(2025, 3, 3), 0.0,
                LocalDate.of(2025, 3, 5), 8.0
        ));

        ProductionEstimationService.DisplayedProductionComputation result = service.computeDisplayedProduction(
                site,
                LocalDate.of(2025, 3, 1),
                LocalDate.of(2025, 3, 5)
        );

        double theoretical = 0.0;
        for (int day = 1; day <= 5; day++) {
            theoretical += service.theoreticalDailyKwh(site, LocalDate.of(2025, 3, day));
        }
        double estimationDay = ((30.0 / 3.0) + (theoretical / 5.0)) / 2.0;

        assertThat(result.realKwh()).isEqualTo(30.0);
        assertThat(result.estimatedDays()).isEqualTo(2);
        assertThat(result.estimatedKwh()).isEqualTo(service.round2(estimationDay * 2));
        assertThat(result.displayedKwh()).isEqualTo(service.round2(30.0 + service.round2(estimationDay * 2)));
    }

    private ProductionEstimationService.DailySiteProduction production(Map<LocalDate, Double> values) {
        SiteDocument document = new SiteDocument();
        document.setBrand(Brand.HUAWEI);
        document.setSiteCode("ARI_0001");
        document.setSiteName("ARI test");
        document.setInstalledPowerKw(10.0);
        document.setSwapDate(LocalDate.of(2025, 3, 1));

        return new ProductionEstimationService.DailySiteProduction(
                document,
                document.getSiteCode(),
                document.getSiteName(),
                "ARI",
                "Zone A",
                document.getInstalledPowerKw(),
                document.getSwapDate(),
                values,
                Set.copyOf(values.keySet())
        );
    }
}
