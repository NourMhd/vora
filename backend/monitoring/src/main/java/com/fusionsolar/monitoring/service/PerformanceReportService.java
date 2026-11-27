package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.util.SiteProductionPolicy;
import com.fusionsolar.monitoring.service.ProductionEstimationService.DailySiteProduction;
import com.fusionsolar.monitoring.dto.PerformanceReportRowDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.service.sync.SiteEnergyKpiReconciliationService;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class PerformanceReportService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final double EPSILON = 0.000001;

    private final SiteRepository siteRepository;
    private final SiteHistoryRepository siteHistoryRepository;
    private final ProductionEstimationService productionEstimationService;
    private final CurrentSiteAccessService currentSiteAccessService;

    public PerformanceReportService(SiteRepository siteRepository,
            SiteHistoryRepository siteHistoryRepository,
            SiteEnergyKpiReconciliationService siteEnergyKpiReconciliationService,
            ProductionEstimationService productionEstimationService,
            CurrentSiteAccessService currentSiteAccessService,
            com.fusionsolar.monitoring.persistence.repositories.SiteDeviceRepository siteDeviceRepository) {
        this.siteRepository = siteRepository;
        this.siteHistoryRepository = siteHistoryRepository;
        this.productionEstimationService = productionEstimationService;
        this.currentSiteAccessService = currentSiteAccessService;
    }

    public List<PerformanceReportRowDto> getReport(
            String period,
            Integer year,
            Integer month,
            Integer decade,
            String startDate,
            String endDate
    ) {
        LocalDate today = LocalDate.now(TUNIS_ZONE);
        ReportRange range = resolveReportRange(period, year, month, decade, startDate, endDate, today);

        List<SiteDocument> sites = siteRepository.findAllByOrderBySiteNameAsc();
        LocalDate yesterday = today.minusDays(1);
        List<DailySiteProduction> productions = loadVisibleProductions(
                sites, yesterday);

        return productions.stream()
                .map(production -> toContinuousRow(production.document(), range, yesterday, production,
                        productionEstimationService.continuousReference(production, yesterday)))
                .toList();
    }

    public List<PerformanceReportRowDto> getReport(String period, Integer year, Integer month, Integer decade) {
        return getReport(period, year, month, decade, null, null);
    }

    public List<MonthlyProduction> getMonthlyComparison(YearMonth start, YearMonth end) {
        LocalDate today = LocalDate.now(TUNIS_ZONE);
        YearMonth currentMonth = YearMonth.from(today);
        if (start.isAfter(end) || end.isAfter(currentMonth)
                || start.isBefore(end.minusMonths(59))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Période de 1 à 60 mois requise.");
        }

        LocalDate yesterday = today.minusDays(1);
        List<SiteDocument> sites = siteRepository.findAllByOrderBySiteNameAsc();
        List<DailySiteProduction> productions = loadVisibleProductions(sites, yesterday);

        return productions.stream().map(production -> {
            SiteDocument site = production.document();
            var reference = productionEstimationService.continuousReference(production, yesterday);
            List<MonthlyCell> cells = new ArrayList<>();
            for (YearMonth month = start; !month.isAfter(end); month = month.plusMonths(1)) {
                var range = new ReportRange(month.atDay(1), month.atEndOfMonth());
                var row = toContinuousRow(site, range, yesterday, production, reference);
                cells.add(monthlyCell(month, row, production));
            }
            return new MonthlyProduction(site.getId(), site.getBrand() == null ? null : site.getBrand().name(),
                    site.getSiteCode(), site.getSiteName(), site.getInstalledPowerKw(),
                    production.zone(), production.governorate(), cells);
        }).toList();
    }

    private List<DailySiteProduction> loadVisibleProductions(List<SiteDocument> sites, LocalDate yesterday) {
        return sites.stream().filter(currentSiteAccessService::canAccess)
                .map(site -> ProductionHistoryLoader.load(site, site.getSiteCode(), site.getSiteName(),
                        siteHistoryRepository, productionEstimationService,
                        ProductionHistoryLoader.unambiguous(site, sites), yesterday))
                .toList();
    }

    private MonthlyCell monthlyCell(YearMonth month, PerformanceReportRowDto row,
            ProductionEstimationService.DailySiteProduction production) {
        Integer days = row.getProductionDaysCount();
        Double actual = row.getActualKwh();
        Double power = production.document().getInstalledPowerKw();
        Double perKwc = actual != null && power != null && Double.isFinite(power) && power > 0 ? actual / power : null;
        return new MonthlyCell(month.toString(), actual,
                row.getCapturedKwh(), row.getExpectedKwh(),
                row.getDifferenceKwh(), row.getDifferencePercent(), row.getProductionStartDate(),
                row.getProductionEndDate(), days, row.getCapturedDaysCount(),
                row.getEstimatedDaysCount(), perKwc,
                perKwc != null && days != null && days > 0 ? perKwc / days : null);
    }

    public record MonthlyProduction(String id, String brand, String siteCode, String siteName, Double installedPowerKw,
            String zone, String governorate, List<MonthlyCell> months) {}

    public record MonthlyCell(String month, Double actualKwh, Double historicalKwh, Double expectedKwh,
            Double differenceKwh, Double differencePercent, String startDate, String endDate,
            Integer days, Integer positiveDays, Integer missingDays, Double kwhPerKwc, Double kwhPerKwcDay) {}

    private PerformanceReportRowDto toRow(
            SiteDocument site,
            ReportRange range,
            LocalDate yesterday,
            ProductionEstimationService.DailySiteProduction siteProduction,
            List<ProductionEstimationService.DailySiteProduction> allSiteProductions
    ) {
        return toRow(site, range, yesterday, siteProduction, allSiteProductions, () -> null);
    }

    private PerformanceReportRowDto toRow(
            SiteDocument site,
            ReportRange range,
            LocalDate yesterday,
            ProductionEstimationService.DailySiteProduction siteProduction,
            List<ProductionEstimationService.DailySiteProduction> allSiteProductions,
            Supplier<Double> inverterTotal
    ) {
        return toContinuousRow(site, range, yesterday, siteProduction,
                productionEstimationService.continuousReference(siteProduction, yesterday));
    }

    private PerformanceReportRowDto toContinuousRow(SiteDocument site, ReportRange range,
            LocalDate yesterday, DailySiteProduction siteProduction,
            ProductionEstimationService.SiteReference reference) {
        String governorate = productionEstimationService.governorateForSite(site);
        String zone = productionEstimationService.zoneForGovernorate(governorate);
        String plantType = plantTypeForSite(site);

        LocalDate effectiveFilterEnd = minDate(range.endDate(), yesterday);

        LocalDate swapDate = site.getSwapDate();
        LocalDate commissioningDate = ProductionEstimationService.parseFlexibleDate(site.getGridConnectionDate());

        LocalDate origin = ProductionEstimationService.productionOrigin(site);
        if (origin == null) return missingDatesRow(site, range, plantType, zone, governorate);
        LocalDate productionStart = maxDate(range.startDate(), origin);
        LocalDate productionEnd = effectiveFilterEnd;
        int productionDays = daysInclusive(productionStart, productionEnd);
        int filterDays = productionDays;
        double installedPowerKw = safeDouble(site.getInstalledPowerKw());
        Double expectedKwh = productionEstimationService.continuousExpected(zone, installedPowerKw,
                productionStart, productionEnd);
        // Never use live cumulative counters or peer sites in these reports.
        if (siteProduction == null) {
            siteProduction = new DailySiteProduction(site, site.getSiteCode(), site.getSiteName(), governorate,
                    zone, installedPowerKw, site.getSwapDate(), Map.of(), Set.of());
            reference = productionEstimationService.continuousReference(siteProduction, yesterday);
        }
        var result = productionEstimationService.continuousProduction(siteProduction, productionStart,
                productionEnd, yesterday, reference);
        ActualComputation actual = new ActualComputation(result.realKwh(), result.estimatedKwh(),
                result.displayedKwh(), result.monitoredDays(), result.estimatedDays());
        boolean forcedZero = SiteProductionPolicy.forcesZero(site);
        if (forcedZero) actual = new ActualComputation(0.0, 0.0, 0.0, 0, 0);
        Double actualKwh = actual.finalKwh();

        boolean canCompare = expectedKwh != null && actualKwh != null;

        String dataStatus = forcedZero ? "FORCED_ZERO" : expectedKwh == null
                ? "MISSING_EXPECTED_KWH"
                : actualKwh == null
                    ? "MISSING_REAL_KWH"
                    : productionDays <= 0
                        ? "NO_PRODUCTION_IN_FILTER"
                        : "OK";

        Double differenceKwh = canCompare
                ? round2(actualKwh - expectedKwh)
                : null;

        Double differencePercent = canCompare && Math.abs(expectedKwh) > EPSILON
                ? round2((actualKwh - expectedKwh) * 100.0 / expectedKwh)
                : null;

        String bonusMalus = canCompare && Math.abs(expectedKwh) > EPSILON
                ? actualKwh >= expectedKwh ? "BONUS" : "MALUS"
                : "N/A";

        return new PerformanceReportRowDto(
                site.getBrand() != null ? site.getBrand().name() : null,
                site.getSiteCode(),
                site.getSiteName(),
                site.getStatus() == null ? "UNKNOWN" : site.getStatus().name(),
                round2(installedPowerKw),
                filterDays,
                filterDays,
                productionDays,
                actual.capturedDays(),
                actual.estimatedDays(),
                formatIsoDate(range.startDate()),
                formatIsoDate(range.endDate()),
                formatIsoDate(swapDate),
                formatIsoDate(commissioningDate),
                formatIsoDate(productionDays > 0 ? productionStart : null),
                formatIsoDate(productionDays > 0 ? productionEnd : null),
                plantType,
                zone,
                governorate,
                expectedKwh,
                SiteProductionPolicy.energy(site, actualKwh == null ? null : round2(actual.capturedKwh())),
                SiteProductionPolicy.energy(site, actualKwh == null ? null : round2(actual.estimatedMissingKwh())),
                actualKwh,
                differenceKwh,
                differencePercent,
                bonusMalus,
                dataStatus
        );
    }

    /** No report energy or day calculation is permitted without either origin date. */
    private PerformanceReportRowDto missingDatesRow(SiteDocument site, ReportRange range,
            String plantType, String zone, String governorate) {
        return new PerformanceReportRowDto(
                site.getBrand() == null ? null : site.getBrand().name(),
                site.getSiteCode(), site.getSiteName(),
                site.getStatus() == null ? "UNKNOWN" : site.getStatus().name(),
                site.getInstalledPowerKw(),
                null, null, null, null, null,
                formatIsoDate(range.startDate()), formatIsoDate(range.endDate()),
                null, null, null, null,
                plantType, zone, governorate,
                null, null, null, null, null, null,
                "N/A", "MISSING_ORIGIN_DATE");
    }

    private ReportRange resolveReportRange(
            String period,
            Integer year,
            Integer month,
            Integer decade,
            String startDate,
            String endDate,
            LocalDate today
    ) {
        LocalDate parsedStart = parseApiDate(startDate);
        LocalDate parsedEnd = parseApiDate(endDate);

        if (parsedStart != null || parsedEnd != null) {
            LocalDate start = parsedStart != null ? parsedStart : parsedEnd;
            LocalDate end = parsedEnd != null ? parsedEnd : parsedStart;

            if (start == null || end == null) {
                throw new IllegalArgumentException("startDate and endDate are required together");
            }

            if (end.isBefore(start)) {
                throw new IllegalArgumentException("endDate must be greater than or equal to startDate");
            }

            return new ReportRange(start, end);
        }

        ReportPeriod reportPeriod = ReportPeriod.from(period);
        int effectiveYear = year != null ? year : today.getYear();
        int effectiveMonth = month != null ? month : today.getMonthValue();
        int effectiveDecade = decade != null ? decade : decadeForDay(today.getDayOfMonth());

        validateInput(reportPeriod, effectiveYear, effectiveMonth, effectiveDecade);

        if (reportPeriod == ReportPeriod.TEN_DAYS) {
            YearMonth yearMonth = YearMonth.of(effectiveYear, effectiveMonth);
            int startDay = effectiveDecade == 1 ? 1 : effectiveDecade == 2 ? 11 : 21;
            int endDay = effectiveDecade == 1 ? 10 : effectiveDecade == 2 ? 20 : yearMonth.lengthOfMonth();
            return new ReportRange(yearMonth.atDay(startDay), yearMonth.atDay(endDay));
        }

        if (reportPeriod == ReportPeriod.MONTH) {
            YearMonth yearMonth = YearMonth.of(effectiveYear, effectiveMonth);
            return new ReportRange(yearMonth.atDay(1), yearMonth.atEndOfMonth());
        }

        return new ReportRange(LocalDate.of(effectiveYear, 1, 1), LocalDate.of(effectiveYear, 12, 31));
    }

    private LocalDate parseApiDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date format. Expected yyyy-MM-dd");
        }
    }

    private String formatIsoDate(LocalDate value) {
        return value != null ? value.toString() : null;
    }

    private String plantTypeForSite(SiteDocument site) {
        double kwp = safeDouble(site.getInstalledPowerKw());

        if (kwp <= 0) {
            return "UNKNOWN";
        }

        return kwp >= 1 && kwp <= 5 ? "RT" : "GF";
    }

    private int decadeForDay(int dayOfMonth) {
        if (dayOfMonth <= 10) {
            return 1;
        }

        if (dayOfMonth <= 20) {
            return 2;
        }

        return 3;
    }

    private void validateInput(ReportPeriod period, int year, int month, int decade) {
        if (year < 2000 || year > 2100) {
            throw new IllegalArgumentException("year must be between 2000 and 2100");
        }

        if (period == ReportPeriod.TEN_DAYS || period == ReportPeriod.MONTH) {
            if (month < 1 || month > 12) {
                throw new IllegalArgumentException("month must be between 1 and 12");
            }
        }

        if (period == ReportPeriod.TEN_DAYS && (decade < 1 || decade > 3)) {
            throw new IllegalArgumentException("decade must be 1, 2 or 3");
        }
    }

    private int daysInclusive(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            return 0;
        }

        return Math.toIntExact(ChronoUnit.DAYS.between(startDate, endDate) + 1);
    }

    private LocalDate maxDate(LocalDate left, LocalDate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    private LocalDate minDate(LocalDate left, LocalDate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private double safeDouble(Double value) {
        return value == null ? 0.0 : value;
    }

    private Double round2(Double value) {
        if (value == null) {
            return null;
        }

        return Math.round(value * 100.0) / 100.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record ReportRange(LocalDate startDate, LocalDate endDate) {
    }

    private record ActualComputation(
            Double capturedKwh,
            Double estimatedMissingKwh,
            Double finalKwh,
            int capturedDays,
            int estimatedDays
    ) {
    }

    private enum ReportPeriod {
        TEN_DAYS,
        MONTH,
        YEAR,
        CUSTOM;

        static ReportPeriod from(String value) {
            if (value == null || value.isBlank()) {
                return MONTH;
            }

            String normalized = value.trim()
                    .toUpperCase(Locale.ROOT)
                    .replace("-", "_")
                    .replace(" ", "_");

            if ("10_DAYS".equals(normalized) || "TEN_DAYS".equals(normalized) || "DECADE".equals(normalized)) {
                return TEN_DAYS;
            }

            if ("MONTH".equals(normalized) || "MOIS".equals(normalized)) {
                return MONTH;
            }

            if ("YEAR".equals(normalized) || "ANNEE".equals(normalized) || "ANNÉE".equals(normalized)) {
                return YEAR;
            }

            if ("CUSTOM".equals(normalized) || "RANGE".equals(normalized) || "PERIODE_LIBRE".equals(normalized)) {
                return CUSTOM;
            }

            throw new IllegalArgumentException("Invalid period. Expected TEN_DAYS, MONTH, YEAR or CUSTOM");
        }
    }
}
