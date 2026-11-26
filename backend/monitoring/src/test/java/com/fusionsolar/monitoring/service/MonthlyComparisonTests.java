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
