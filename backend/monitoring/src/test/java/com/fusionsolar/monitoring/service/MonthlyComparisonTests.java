package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.DeviceItemDto;
import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.SiteDeviceDocument;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.repositories.SiteDeviceRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

class MonthlyComparisonTests {
    private static final ZoneId TUNIS = ZoneId.of("Africa/Tunis");

    @Test
    void separatesCollectedHistoryAndKeepsForecastStableWhenCounterBecomesIncomplete() {
        LocalDate today = LocalDate.now(TUNIS);
        YearMonth january = YearMonth.of(today.getYear() - 1, 1);
        YearMonth december = january.minusMonths(1);
        var target = site("TUN_0001", january.atDay(20));
        target.setGridConnectionDate(january.atDay(10).toString());
        var peer = site("TUN_0002", january.atDay(20));
        var missingDates = site("TUN_0003", null);
        var hidden = site("TUN_0004", january.atDay(1));
        hidden.setActive(false);
        var forcedZero = site("SFA_0138", january.atDay(20));

        var inverter = new DeviceItemDto();
        inverter.setId("first");
        inverter.setDevTypeId(1);
        var second = new DeviceItemDto();
        second.setId("second");
        second.setDevTypeId(1);
        var counter = SiteDeviceDocument.builder().devices(List.of(inverter, second))
                .providerAccountKey("primary")
                .inverterTotalKwh(Map.of("first", 7000.0, "second", 3000.0))
                .inverterTotalObservedAt(Map.of("first", Instant.now(), "second", Instant.now()))
                .build();
        var sites = repository(SiteRepository.class,
                (p, m, a) -> List.of(target, peer, missingDates, hidden, forcedZero));
        var history = repository(SiteHistoryRepository.class, (p, m, a) -> {
            if (a.length != 5 || !january.toString().equals(a[4])) return Optional.empty();
            var document = new SiteHistoryDocument();
            if (target.getSiteCode().equals(a[2])) {
                document.setPoints(List.of(point(january.atDay(12), 7.0), point(january.atDay(20), 13.0)));
                return Optional.of(document);
            }
            if (peer.getSiteCode().equals(a[2])) {
                document.setPoints(List.of(point(january.atDay(20), 30.0)));
                return Optional.of(document);
            }
            return Optional.empty();
        });
        var devices = repository(SiteDeviceRepository.class,
                (p, m, a) -> target.getSiteCode().equals(a[2]) ? Optional.of(counter) : Optional.empty());
        var estimation = new ProductionEstimationService();
        var service = new PerformanceReportService(sites, history, null, estimation,
                new CurrentSiteAccessService(), devices);

        var matrix = service.getMonthlyComparison(december, january);
        equal(matrix.size(), 4);
        equal(matrix.get(0).months().get(0).actualKwh(), 0.0);
        var cell = matrix.get(0).months().get(1);
        double expectedAllocation = estimation.round2(10_000
                * estimation.theoreticalYieldPerKwc("Zone A", january.atDay(10), january.atEndOfMonth())
                / estimation.theoreticalYieldPerKwc("Zone A", january.atDay(10), today));
        equal(cell.actualKwh(), expectedAllocation);
        equal(cell.historicalKwh(), 20.0);
        equal(cell.startDate(), january.atDay(10).toString());
        equal(cell.days(), 22);
        equal(cell.positiveDays(), 2);
        equal(cell.missingDays(), 20);
        equal(cell.kwhPerKwcDay(), cell.actualKwh() / 5.0 / 22);
        equal(matrix.get(2).months().get(1).actualKwh(), null);
        equal(matrix.get(2).months().get(1).historicalKwh(), null);
        equal(matrix.get(3).months().get(1).actualKwh(), 0.0);

        counter.setInverterTotalKwh(Map.of("first", 7000.0));
        var fallback = service.getMonthlyComparison(january, january).get(0).months().get(0);
        equal(fallback.expectedKwh(), cell.expectedKwh());
        equal(fallback.startDate(), january.atDay(20).toString());
        equal(fallback.historicalKwh(), 13.0);
        equal(fallback.actualKwh(), 343.0);
    }

    @Test
    void sumsMonthlyFallbacksAndPreservesAllPositiveDays() throws Exception {
        var jan = LocalDate.of(2025, 1, 30);
        var feb = jan.plusDays(2);
        var end = feb.plusDays(2);
        var site = site("TUN_0040", jan);
        var values = Map.of(jan, 4.0, feb, 20.0, feb.plusDays(1), 0.0, end, 1.0);
        var coverage = Map.of(
                jan, ProductionEstimationService.DailyCoverage.PARTIAL,
                feb, ProductionEstimationService.DailyCoverage.PARTIAL,
                feb.plusDays(1), ProductionEstimationService.DailyCoverage.COMPLETE);
        var history = new ProductionEstimationService.DailySiteProduction(site, site.getSiteCode(),
                site.getSiteName(), "TUN", "Zone A", 5.0, jan, values, values.keySet(), coverage);
        var peerSite = site("TUN_0041", jan);
        var peerValues = Map.of(jan, 30.0, feb, 100.0);
        var peer = new ProductionEstimationService.DailySiteProduction(peerSite, peerSite.getSiteCode(),
                peerSite.getSiteName(), "TUN", "Zone A", 5.0, jan, peerValues, peerValues.keySet());
        var service = new PerformanceReportService(null, null, null, new ProductionEstimationService(), null, null);
        var range = Class.forName(PerformanceReportService.class.getName() + "$ReportRange");
        var constructor = range.getDeclaredConstructor(LocalDate.class, LocalDate.class);
        constructor.setAccessible(true);
        var method = PerformanceReportService.class.getDeclaredMethod("toRow", SiteDocument.class,
                range, LocalDate.class, ProductionEstimationService.DailySiteProduction.class, List.class);
        method.setAccessible(true);
        var sites = List.of(history, peer);

        var first = (com.fusionsolar.monitoring.dto.PerformanceReportRowDto)
                method.invoke(service, site, constructor.newInstance(jan, feb.minusDays(1)), end, history, sites);
        var second = (com.fusionsolar.monitoring.dto.PerformanceReportRowDto)
                method.invoke(service, site, constructor.newInstance(feb, end), end, history, sites);
        var combined = (com.fusionsolar.monitoring.dto.PerformanceReportRowDto)
                method.invoke(service, site, constructor.newInstance(jan, end), end, history, sites);

        // Jan 30 stays 4 kWh; Jan 31 gets the January peer's 30 kWh.
        equal(first.getActualKwh(), 34.0);
        equal(first.getEstimatedMissingKwh(), 30.0);
        // Positive values stay unchanged; the confirmed zero uses the February peer.
        equal(second.getActualKwh(), 121.0);
        equal(second.getEstimatedMissingKwh(), 100.0);
        equal(combined.getActualKwh(), 155.0);
        equal(combined.getCapturedKwh(), 25.0);
        equal(combined.getEstimatedMissingKwh(), 130.0);
        equal(combined.getEstimatedDaysCount(), 2);
    }

    @Test
    void monthlyReportPreservesPositivePartialHistory() {
        var day = LocalDate.of(2025, 1, 31);
        var target = site("TUN_0042", day);
        var sites = repository(SiteRepository.class, (p, m, a) -> List.of(target));
        var history = repository(SiteHistoryRepository.class, (p, m, a) -> {
            var document = new SiteHistoryDocument();
            HistoryPointDto partial = point(day, 4.0);
            partial.setStatus("PARTIAL");
            document.setPoints(List.of(partial));
            return Optional.of(document);
        });
        var service = new PerformanceReportService(sites, history, null,
                new ProductionEstimationService(), new CurrentSiteAccessService(), null);

        var month = YearMonth.from(day);
        var cell = service.getMonthlyComparison(month, month).get(0).months().get(0);
        equal(cell.actualKwh(), 4.0);
        equal(cell.historicalKwh(), 4.0);
        equal(cell.positiveDays(), 1);
        equal(cell.missingDays(), 0);
    }

    @Test
    void cumulativeNeverReplacesPositiveDays() throws Exception {
        var day = LocalDate.of(2025, 1, 30);
        var site = site("TUN_0043", day);
        var service = new PerformanceReportService(null, null, null, new ProductionEstimationService(), null, null);
        var range = Class.forName(PerformanceReportService.class.getName() + "$ReportRange");
        var constructor = range.getDeclaredConstructor(LocalDate.class, LocalDate.class);
        constructor.setAccessible(true);
        var method = PerformanceReportService.class.getDeclaredMethod("toRow", SiteDocument.class,
                range, LocalDate.class, ProductionEstimationService.DailySiteProduction.class, List.class,
                java.util.function.Supplier.class);
        method.setAccessible(true);
        var values = Map.of(day, 20.0, day.plusDays(1), 0.001);
        var production = new ProductionEstimationService.DailySiteProduction(site, site.getSiteCode(),
                site.getSiteName(), "TUN", "Zone A", 5.0, day, values, values.keySet());
        var row = (com.fusionsolar.monitoring.dto.PerformanceReportRowDto) method.invoke(service, site,
                constructor.newInstance(day, day.plusDays(1)), day.plusDays(1), production, List.of(production),
                (java.util.function.Supplier<Double>) () -> 1000.0);
        equal(row.getActualKwh(), 20.0);
        equal(row.getCapturedDaysCount(), 2);
        equal(row.getEstimatedDaysCount(), 0);
        var missing = Map.of(day, 20.0, day.plusDays(1), 0.0);
        production = new ProductionEstimationService.DailySiteProduction(site, site.getSiteCode(),
                site.getSiteName(), "TUN", "Zone A", 5.0, day, missing, missing.keySet());
        row = (com.fusionsolar.monitoring.dto.PerformanceReportRowDto) method.invoke(service, site,
                constructor.newInstance(day, day.plusDays(1)), day.plusDays(1), production, List.of(production),
                (java.util.function.Supplier<Double>) () -> 1.0);
        equal(row.getActualKwh(), 20.0);
        equal(row.getCapturedDaysCount(), 1);
        equal(row.getEstimatedDaysCount(), 1);
    }

    @Test
    void legacyHistoryCannotCrossAccountGroups() {
        var foreignGroup = new SiteHistoryDocument();
        foreignGroup.setProviderAccountGroup("other-group");
        foreignGroup.setProviderAccountKey("primary");
        var foreignAccount = new SiteHistoryDocument();
        foreignAccount.setProviderAccountKey("other-account");
        var unattributed = new SiteHistoryDocument();
        var sameAccount = new SiteHistoryDocument();
        sameAccount.setProviderAccountKey("primary");
        var histories = repository(SiteHistoryRepository.class, (proxy, method, args) ->
                method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, args)
                        : List.of(foreignGroup, foreignAccount, unattributed, sameAccount));

        equal(histories.findLegacyHistoryForAccount(
                Brand.HUAWEI, "TUN_0001", HistoryGranularity.MONTH,
                "2025-01", "primary", false), Optional.of(sameAccount));
        equal(histories.findLegacyHistoryForAccount(
                Brand.HUAWEI, "TUN_0001", HistoryGranularity.MONTH,
                "2025-01", "different", false), Optional.empty());
    }

    private static SiteDocument site(String code, LocalDate swap) {
        var document = new SiteDocument();
        document.setBrand(Brand.HUAWEI);
        document.setProviderAccountKey("primary");
        document.setSiteCode(code);
        document.setSiteName(code);
        document.setInstalledPowerKw(5.0);
        document.setSwapDate(swap);
        return document;
    }

    private static HistoryPointDto point(LocalDate date, double value) {
        return new HistoryPointDto(date.atStartOfDay(TUNIS).toInstant().toEpochMilli(), value);
    }

    private static <T> T repository(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static void equal(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
