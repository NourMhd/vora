package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.dto.PerformanceReportRowDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.service.sync.SiteEnergyKpiReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PerformanceReportService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final double EPSILON = 0.000001;

    private static final DateTimeFormatter DISPLAY_DATE_FORMATTER = DateTimeFormatter
            .ofPattern("dd/MM/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);

    private static final Map<String, List<String>> ZONE_GOVERNORATES = Map.of(
            "Zone A", List.of("ARI", "BAR", "MAN", "JEN", "ZAG", "BIZ", "TUN", "NAB", "BEJ", "KEF", "SIL"),
            "Zone B", List.of("KAI", "SOU", "MAH", "KAS", "MON", "SBO", "SFA"),
            "Zone C", List.of("MED", "TAT", "GAB", "TOZ", "GAF", "KEB")
    );

    /*
     * Rendement théorique kWh/kWc/jour, par zone, mois et tranche:
     * tranche 1 = jours 1-10, tranche 2 = jours 11-20, tranche 3 = jours 21-fin du mois.
     * Source: fichier "Tableau Rendement Théorique Par Période.xlsx".
     */
    private static final Map<String, double[][]> THEORETICAL_DAILY_YIELD = Map.of(
            "Zone A", new double[][]{
                    {2.2, 2.4, 2.5},
                    {2.8, 3.3, 3.5},
                    {3.8, 4.2, 4.5},
                    {4.8, 5.2, 5.6},
                    {5.9, 6.1, 6.2},
                    {6.3, 6.4, 6.5},
                    {6.6, 6.7, 6.5},
                    {6.3, 6.0, 5.8},
                    {5.5, 5.0, 4.8},
                    {4.4, 4.0, 3.8},
                    {3.0, 2.8, 2.5},
                    {2.4, 2.3, 2.0}
            },
            "Zone B", new double[][]{
                    {2.266, 2.472, 2.575},
                    {2.884, 3.399, 3.605},
                    {3.914, 4.326, 4.635},
                    {4.944, 5.356, 5.768},
                    {6.077, 6.283, 6.386},
                    {6.489, 6.592, 6.695},
                    {6.798, 6.901, 6.695},
                    {6.489, 6.180, 5.974},
                    {5.665, 5.150, 4.944},
                    {4.532, 4.120, 3.914},
                    {3.090, 2.884, 2.575},
                    {2.480, 2.380, 2.060}
            },
            "Zone C", new double[][]{
                    {2.33398, 2.54616, 2.65225},
                    {2.97052, 3.50097, 3.71315},
                    {4.03142, 4.45578, 4.77405},
                    {5.09232, 5.51668, 5.94104},
                    {6.25931, 6.47149, 6.57758},
                    {6.68367, 6.78976, 6.89585},
                    {7.00194, 7.10803, 6.89585},
                    {6.68367, 6.36540, 6.15322},
                    {5.83495, 5.30450, 5.09232},
                    {4.66796, 4.24360, 4.03142},
                    {3.18270, 2.97052, 2.62650},
                    {2.52960, 2.42760, 2.10120}
            }
    );

    private final SiteRepository siteRepository;
    private final SiteHistoryRepository siteHistoryRepository;
    private final SiteEnergyKpiReconciliationService siteEnergyKpiReconciliationService;
    private final ProductionEstimationService productionEstimationService;
    private final CurrentSiteAccessService currentSiteAccessService;

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

        List<SiteDocument> sites = siteEnergyKpiReconciliationService
                .reconcileAllFromCachedHistories(siteRepository.findAllByOrderBySiteNameAsc())
                .stream()
                .filter(currentSiteAccessService::canAccess)
                .toList();

        LocalDate yesterday = today.minusDays(1);
        LocalDate effectiveEnd = minDate(range.endDate(), yesterday);
        LocalDate historyStart = range.startDate().withDayOfMonth(1);
        LocalDate historyEnd = effectiveEnd == null
                ? null
                : minDate(YearMonth.from(effectiveEnd).atEndOfMonth(), yesterday);
        List<ProductionEstimationService.DailySiteProduction> siteProductions = sites.stream()
                .map(site -> loadSiteProduction(site, historyStart, historyEnd))
                .toList();

        Map<String, ProductionEstimationService.DailySiteProduction> productionsBySiteKey = new HashMap<>();
        for (ProductionEstimationService.DailySiteProduction production : siteProductions) {
            if (production.document() != null) {
                productionsBySiteKey.put(siteKey(production.document()), production);
            }
        }

        return sites.stream()
                .map(site -> toRow(
                        site,
                        range,
                        yesterday,
                        productionsBySiteKey.get(siteKey(site)),
                        siteProductions
                ))
                .toList();
    }

    public List<PerformanceReportRowDto> getReport(String period, Integer year, Integer month, Integer decade) {
        return getReport(period, year, month, decade, null, null);
    }

    private PerformanceReportRowDto toRow(
            SiteDocument site,
            ReportRange range,
            LocalDate yesterday,
            ProductionEstimationService.DailySiteProduction siteProduction,
            List<ProductionEstimationService.DailySiteProduction> allSiteProductions
    ) {
        String governorate = productionEstimationService.governorateForSite(site);
        String zone = productionEstimationService.zoneForGovernorate(governorate);
        String plantType = plantTypeForSite(site);

        LocalDate effectiveFilterEnd = minDate(range.endDate(), yesterday);
        int filterDays = daysInclusive(range.startDate(), effectiveFilterEnd);

        LocalDate swapDate = site.getSwapDate();
        LocalDate commissioningDate = parseFlexibleDate(site.getGridConnectionDate());

        LocalDate productionStart = swapDate != null
                ? maxDate(range.startDate(), swapDate)
                : null;
        LocalDate productionEnd = effectiveFilterEnd;

        int productionDays = daysInclusive(productionStart, productionEnd);
        // Report days use the same swap-bounded period as both energy calculations.
        filterDays = productionDays;
        double installedPowerKw = safeDouble(site.getInstalledPowerKw());

        Double expectedKwh = zone == null || !Double.isFinite(installedPowerKw) || installedPowerKw <= 0 || productionStart == null
                ? null
                : round2(productionEstimationService.theoreticalYieldPerKwc(zone, productionStart, productionEnd) * installedPowerKw);

        ActualComputation actual = computeActualKwh(
                siteProduction,
                allSiteProductions,
                range.startDate(),
                productionEnd,
                yesterday
        );
        Double actualKwh = actual.finalKwh();

        boolean canCompare = expectedKwh != null && actualKwh != null;

        String dataStatus = swapDate == null
                ? "MISSING_SWAP_DATE"
                : expectedKwh == null
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
                swapDate == null ? null : round2(actual.capturedKwh()),
                swapDate == null || actualKwh == null ? null : round2(actual.estimatedMissingKwh()),
                actualKwh,
                differenceKwh,
                differencePercent,
                bonusMalus,
                dataStatus
        );
    }

    private ActualComputation computeActualKwh(
            ProductionEstimationService.DailySiteProduction siteProduction,
            List<ProductionEstimationService.DailySiteProduction> allSiteProductions,
            LocalDate productionStart,
            LocalDate productionEnd,
            LocalDate yesterday
    ) {
        if (siteProduction == null || productionStart == null || productionEnd == null || productionEnd.isBefore(productionStart)) {
            return new ActualComputation(0.0, 0.0, null, 0, 0);
        }

        LocalDate safeProductionEnd = minDate(productionEnd, yesterday);
        if (safeProductionEnd.isBefore(productionStart)) {
            return new ActualComputation(0.0, 0.0, null, 0, 0);
        }

        ProductionEstimationService.DisplayedProductionComputation displayed = productionEstimationService
                .computeDisplayedProduction(
                        siteProduction,
                        allSiteProductions,
                        productionStart,
                        safeProductionEnd
                );

        return new ActualComputation(
                round2(displayed.realKwh()),
                round2(displayed.estimatedKwh()),
                displayed.displayedKwh(),
                displayed.monitoredDays(),
                displayed.estimatedDays()
        );
    }

    private ActualRangeComputation actualKwhDetailsInRange(
            SiteDocument site,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate yesterday
    ) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            return new ActualRangeComputation(0.0, 0, 0);
        }

        LocalDate safeEndDate = minDate(endDate, yesterday);
        if (safeEndDate.isBefore(startDate)) {
            return new ActualRangeComputation(0.0, 0, 0);
        }

        YearMonth startMonth = YearMonth.from(startDate);
        YearMonth endMonth = YearMonth.from(safeEndDate);

        double sum = 0.0;
        int monitoredDays = 0;
        int missingProductionDays = 0;
        boolean found = false;

        YearMonth cursor = startMonth;
        while (!cursor.isAfter(endMonth)) {
            YearMonth currentMonth = cursor;

            LocalDate monthStart = currentMonth.atDay(1);
            LocalDate monthEnd = currentMonth.atEndOfMonth();
            LocalDate rangeStart = maxDate(startDate, monthStart);
            LocalDate rangeEnd = minDate(safeEndDate, monthEnd);

            DailyRangeComputation dailyValue = historyMonth(site, currentMonth)
                    .map(history -> sumDailyRangeWithMissingDays(site, history.getPoints(), rangeStart, rangeEnd, yesterday))
                    .orElseGet(() -> missingDailyRange(rangeStart, rangeEnd, yesterday));

            if (dailyValue != null) {
                sum += dailyValue.capturedKwh();
                monitoredDays += dailyValue.monitoredDays();
                missingProductionDays += dailyValue.missingProductionDays();
                found = true;
            }

            cursor = cursor.plusMonths(1);
        }

        if (!found) {
            return new ActualRangeComputation(null, 0, 0);
        }

        return new ActualRangeComputation(round2(sum), monitoredDays, missingProductionDays);
    }

    private DailyRangeComputation sumDailyRangeWithMissingDays(
            SiteDocument site,
            List<HistoryPointDto> points,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate yesterday
    ) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            return null;
        }

        LocalDate safeEndDate = minDate(endDate, yesterday);
        if (safeEndDate.isBefore(startDate)) {
            return new DailyRangeComputation(0.0, 0, 0);
        }

        Map<LocalDate, Double> valuesByDay = new java.util.HashMap<>();

        if (points != null) {
            for (HistoryPointDto point : points) {
                if (point == null || point.getCollectTime() == null) {
                    continue;
                }

                LocalDate day = toLocalDate(point.getCollectTime());
                if (day == null || day.isBefore(startDate) || day.isAfter(safeEndDate)) {
                    continue;
                }

                double value = point.getValue() == null ? 0.0 : sanitizeHistoryValue(site, point.getValue());
                valuesByDay.merge(day, value, Math::max);
            }
        }

        double sum = 0.0;
        int monitoredDays = 0;
        int missingProductionDays = 0;

        LocalDate cursor = startDate;
        while (!cursor.isAfter(safeEndDate)) {
            Double value = valuesByDay.get(cursor);

            if (value == null || Math.abs(value) <= EPSILON) {
                missingProductionDays++;
            } else {
                sum += value;
                monitoredDays++;
            }

            cursor = cursor.plusDays(1);
        }

        return new DailyRangeComputation(round2(sum), monitoredDays, missingProductionDays);
    }

    private DailyRangeComputation missingDailyRange(
            LocalDate startDate,
            LocalDate endDate,
            LocalDate yesterday
    ) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            return null;
        }

        LocalDate safeEndDate = minDate(endDate, yesterday);
        if (safeEndDate.isBefore(startDate)) {
            return new DailyRangeComputation(0.0, 0, 0);
        }

        return new DailyRangeComputation(0.0, 0, daysInclusive(startDate, safeEndDate));
    }

    private double theoreticalYieldPerKwc(String zone, LocalDate startDate, LocalDate endDate) {
        if (zone == null || !THEORETICAL_DAILY_YIELD.containsKey(zone)
                || startDate == null || endDate == null || endDate.isBefore(startDate)) {
            return 0.0;
        }

        double[][] yields = THEORETICAL_DAILY_YIELD.get(zone);
        double sum = 0.0;

        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            int monthIndex = cursor.getMonthValue() - 1;
            int decadeIndex = decadeForDay(cursor.getDayOfMonth()) - 1;
            sum += yields[monthIndex][decadeIndex];
            cursor = cursor.plusDays(1);
        }

        return sum;
    }

    private ProductionEstimationService.DailySiteProduction loadSiteProduction(
            SiteDocument site,
            LocalDate startDate,
            LocalDate endDate
    ) {
        Map<LocalDate, Double> valuesByDate = new HashMap<>();
        Set<LocalDate> presentDates = new HashSet<>();

        if (site != null && startDate != null && endDate != null && !endDate.isBefore(startDate)) {
            YearMonth cursor = YearMonth.from(startDate);
            YearMonth endMonth = YearMonth.from(endDate);

            while (!cursor.isAfter(endMonth)) {
                historyMonth(site, cursor).ifPresent(history -> collectDailyValues(
                        site,
                        history.getPoints(),
                        startDate,
                        endDate,
                        valuesByDate,
                        presentDates
                ));
                cursor = cursor.plusMonths(1);
            }
        }

        String governorate = productionEstimationService.governorateForSite(site);
        String zone = productionEstimationService.zoneForGovernorate(governorate);

        return new ProductionEstimationService.DailySiteProduction(
                site,
                site != null ? site.getSiteCode() : null,
                site != null ? site.getSiteName() : null,
                governorate,
                zone,
                site != null ? safeDouble(site.getInstalledPowerKw()) : 0.0,
                site != null ? site.getSwapDate() : null,
                valuesByDate,
                presentDates
        );
    }

    private void collectDailyValues(
            SiteDocument site,
            List<HistoryPointDto> points,
            LocalDate startDate,
            LocalDate endDate,
            Map<LocalDate, Double> valuesByDate,
            Set<LocalDate> presentDates
    ) {
        if (points == null || points.isEmpty()) {
            return;
        }

        for (HistoryPointDto point : points) {
            if (point == null || point.getCollectTime() == null) {
                continue;
            }

            LocalDate day = toLocalDate(point.getCollectTime());
            if (day == null || day.isBefore(startDate) || day.isAfter(endDate)) {
                continue;
            }

            presentDates.add(day);
            Double sanitizedValue = sanitizeHistoryValue(site, point.getValue());
            if (sanitizedValue != null) {
                valuesByDate.merge(day, sanitizedValue, Math::max);
            }
        }
    }

    private String siteKey(SiteDocument site) {
        if (site == null) {
            return "";
        }
        return (site.getBrand() == null ? "" : site.getBrand().name()) + "::" + (site.getSiteCode() == null ? "" : site.getSiteCode());
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

    private Optional<SiteHistoryDocument> historyMonth(SiteDocument site, YearMonth yearMonth) {
        return findHistory(site, HistoryGranularity.MONTH, yearMonth.toString());
    }

    private Optional<SiteHistoryDocument> historyYear(SiteDocument site, int year) {
        return findHistory(site, HistoryGranularity.YEAR, String.valueOf(year));
    }

    private Optional<SiteHistoryDocument> findHistory(
            SiteDocument site,
            HistoryGranularity granularity,
            String bucket
    ) {
        Optional<SiteHistoryDocument> grouped = siteHistoryRepository
                .findByBrandAndProviderAccountGroupAndSiteCodeAndGranularityAndBucket(
                        site.getBrand(),
                        accountGroupOf(site),
                        site.getSiteCode(),
                        granularity,
                        bucket
                );

        if (grouped.isPresent()) {
            return grouped;
        }

        return siteHistoryRepository.findByBrandAndSiteCodeAndGranularityAndBucket(
                site.getBrand(),
                site.getSiteCode(),
                granularity,
                bucket
        );
    }

    private String accountGroupOf(SiteDocument site) {
        if (site != null && site.getProviderAccountGroup() != null && !site.getProviderAccountGroup().isBlank()) {
            return site.getProviderAccountGroup().trim();
        }
        if (site != null && site.getProviderAccountKey() != null && !site.getProviderAccountKey().isBlank()) {
            return site.getProviderAccountKey().trim();
        }
        return "primary";
    }

    private Double monthlyPointValue(List<HistoryPointDto> points, YearMonth yearMonth) {
        if (points == null || points.isEmpty()) {
            return null;
        }

        for (HistoryPointDto point : points) {
            if (point == null || point.getValue() == null || point.getCollectTime() == null) {
                continue;
            }

            LocalDate date = toLocalDate(point.getCollectTime());
            if (date != null
                    && date.getYear() == yearMonth.getYear()
                    && date.getMonthValue() == yearMonth.getMonthValue()) {
                return point.getValue();
            }
        }

        return null;
    }

    private Double solisCurrentMonthKwh(SiteDocument site) {
        Double total = realKpiOrNull(site.getTotalEnergyKwh());
        Double month = realKpiOrNull(site.getMonthEnergyKwh());

        if (total != null) {
            if (month != null && month > EPSILON) {
                return round2(Math.min(month, total));
            }

            return total;
        }

        return month;
    }

    private Double sanitizeHistoryValue(SiteDocument site, Double value) {
        Double actual = realKpiOrNull(value);
        if (actual == null) {
            return null;
        }

        if (site.getBrand() == Brand.SOLIS) {
            Double total = realKpiOrNull(site.getTotalEnergyKwh());
            if (total != null && actual > total + EPSILON) {
                return total;
            }
        }

        return actual;
    }

    private LocalDate toLocalDate(Long collectTime) {
        if (collectTime == null) {
            return null;
        }

        long epochMillis = collectTime < 1_000_000_000_000L ? collectTime * 1000 : collectTime;
        return Instant.ofEpochMilli(epochMillis).atZone(TUNIS_ZONE).toLocalDate();
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

    private LocalDate parseFlexibleDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String trimmed = value.trim();

        if (trimmed.matches("\\d{10,13}")) {
            try {
                long raw = Long.parseLong(trimmed);
                long epochMillis = raw < 1_000_000_000_000L ? raw * 1000 : raw;
                return Instant.ofEpochMilli(epochMillis).atZone(TUNIS_ZONE).toLocalDate();
            } catch (NumberFormatException ignored) {
                // Try formatted dates below.
            }
        }

        if (trimmed.length() >= 10) {
            String datePart = trimmed.substring(0, 10);
            try {
                return LocalDate.parse(datePart);
            } catch (DateTimeParseException ignored) {
                // Try full value below.
            }
        }

        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // Try display formats below.
        }

        try {
            return LocalDate.parse(trimmed, DISPLAY_DATE_FORMATTER);
        } catch (DateTimeParseException ignored) {
            // Try slash ISO-like format below.
        }

        try {
            return LocalDate.parse(trimmed, DateTimeFormatter.ofPattern("uuuu/MM/dd"));
        } catch (DateTimeParseException ignored) {
            return null;
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

    private String governorateForSite(SiteDocument site) {
        String text = ((site.getSiteCode() == null ? "" : site.getSiteCode())
                + " "
                + (site.getSiteName() == null ? "" : site.getSiteName()))
                .toUpperCase(Locale.ROOT);

        for (String governorate : ZONE_GOVERNORATES.values().stream().flatMap(List::stream).toList()) {
            if (containsGovernorate(text, governorate)) {
                return governorate;
            }
        }

        return null;
    }

    private boolean containsGovernorate(String text, String governorate) {
        String[] tokens = text.split("[^A-Z0-9]+");

        for (String token : tokens) {
            if (token.equals(governorate) || token.startsWith(governorate)) {
                return true;
            }
        }

        return text.contains(governorate);
    }

    private String zoneForGovernorate(String governorate) {
        if (governorate == null) {
            return null;
        }

        for (Map.Entry<String, List<String>> entry : ZONE_GOVERNORATES.entrySet()) {
            if (entry.getValue().contains(governorate)) {
                return entry.getKey();
            }
        }

        return null;
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

    private boolean isCurrentMonth(YearMonth yearMonth, LocalDate yesterday) {
        return yearMonth.equals(YearMonth.from(yesterday.plusDays(1)));
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

    private Double realKpiOrNull(Double value) {
        if (value == null || value.isNaN() || value.isInfinite() || value < 0) {
            return null;
        }

        return value;
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

    private record DailyRangeComputation(
            Double capturedKwh,
            int monitoredDays,
            int missingProductionDays
    ) {
    }

    private record ActualRangeComputation(
            Double capturedKwh,
            int monitoredDays,
            int missingProductionDays
    ) {
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
