package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.DailyComparisonCellDto;
import com.fusionsolar.monitoring.dto.DailyComparisonReportDto;
import com.fusionsolar.monitoring.dto.DailyComparisonRowDto;
import com.fusionsolar.monitoring.dto.DailyComparisonSelectableSiteDto;
import com.fusionsolar.monitoring.dto.DailyComparisonSiteDto;
import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.util.SiteNameNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DailyComparisonService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final List<String> DEFAULT_SITE_CODES = List.of(
            "ARI_0222",
            "ARI_0112",
            "ARI_0061",
            "ARI_0088"
    );
    private static final double EPSILON = ProductionEstimationService.EPSILON;
    private static final String SWAPPED_NOT_MONITORED = "swapé non monitorisé";

    private final SiteRepository siteRepository;
    private final SiteHistoryRepository siteHistoryRepository;
    private final ProductionEstimationService productionEstimationService;
    private final CurrentSiteAccessService currentSiteAccessService;

    public DailyComparisonReportDto getAriComparison(String siteCodes, String startDate, String endDate) {
        LocalDate yesterday = LocalDate.now(TUNIS_ZONE).minusDays(1);
        LocalDate requestedStart = parseApiDate(startDate);
        LocalDate requestedEnd = parseApiDate(endDate);
        LocalDate safeEnd = requestedEnd == null || requestedEnd.isAfter(yesterday) ? yesterday : requestedEnd;

        List<String> requestedCodes = parseSiteCodes(siteCodes);
        List<ComparisonSite> sites = requestedCodes.stream()
                .map(this::resolveSite)
                .toList();

        LocalDate defaultStartDate = sites.stream()
                .map(ComparisonSite::swapDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(safeEnd);

        LocalDate effectiveStartDate = requestedStart != null ? requestedStart : defaultStartDate;

        if (safeEnd.isBefore(effectiveStartDate)) {
            return new DailyComparisonReportDto(
                    sites.stream().map(this::toSiteDto).toList(),
                    formatDate(effectiveStartDate),
                    formatDate(safeEnd),
                    List.of(),
                    List.of(),
                    totalRow("TOTAL", sites, Map.of()),
                    totalRow("TOTAL", sites, Map.of())
            );
        }

        LocalDate historyStart = effectiveStartDate.withDayOfMonth(1);
        LocalDate calculatedHistoryEnd = YearMonth.from(safeEnd).atEndOfMonth();
        LocalDate historyEnd = calculatedHistoryEnd.isAfter(yesterday)
                ? yesterday
                : calculatedHistoryEnd;

        List<ProductionEstimationService.DailySiteProduction> siteValues = sites.stream()
                .map(site -> loadSiteValues(site, historyStart, historyEnd))
                .toList();

        Map<String, Double> historicalTotals = new HashMap<>();
        Map<String, Double> estimatedTotals = new HashMap<>();
        List<DailyComparisonRowDto> historicalRows = new ArrayList<>();
        List<DailyComparisonRowDto> estimatedRows = new ArrayList<>();

        LocalDate cursor = effectiveStartDate;
        while (!cursor.isAfter(safeEnd)) {
            List<DailyComparisonCellDto> historicalCells = new ArrayList<>();
            List<DailyComparisonCellDto> estimatedCells = new ArrayList<>();

            for (ProductionEstimationService.DailySiteProduction siteValue : siteValues) {
                DailyComparisonCellDto historicalCell = historicalCell(siteValue, cursor);
                DailyComparisonCellDto estimatedCell = estimatedCell(
                        siteValue,
                        siteValues,
                        cursor
                );

                addToTotal(historicalTotals, siteValue.siteCode(), historicalCell.getValue());
                addToTotal(estimatedTotals, siteValue.siteCode(), estimatedCell.getValue());

                historicalCells.add(historicalCell);
                estimatedCells.add(estimatedCell);
            }

            historicalRows.add(new DailyComparisonRowDto(formatDate(cursor), formatDisplayDate(cursor), historicalCells));
            estimatedRows.add(new DailyComparisonRowDto(formatDate(cursor), formatDisplayDate(cursor), estimatedCells));
            cursor = cursor.plusDays(1);
        }

        return new DailyComparisonReportDto(
                sites.stream().map(this::toSiteDto).toList(),
                formatDate(effectiveStartDate),
                formatDate(safeEnd),
                historicalRows,
                estimatedRows,
                totalRow("TOTAL", sites, historicalTotals),
                totalRow("TOTAL", sites, estimatedTotals)
        );
    }

    public List<DailyComparisonSelectableSiteDto> getSelectableSites() {
    return siteRepository.findAllByOrderBySiteNameAsc().stream()
            .filter(currentSiteAccessService::canAccess)
            .map(this::toSelectableSiteDto)
            .sorted(Comparator
                    .comparing(DailyComparisonSelectableSiteDto::getZone, Comparator.nullsLast(String::compareTo))
                    .thenComparing(DailyComparisonSelectableSiteDto::getGovernorate, Comparator.nullsLast(String::compareTo))
                    .thenComparing(DailyComparisonSelectableSiteDto::getInstalledPowerKw, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(DailyComparisonSelectableSiteDto::getSiteName, Comparator.nullsLast(String::compareTo)))
            .toList();
}

    private ComparisonSite resolveSite(String requestedCode) {
        String normalizedCode = normalizeSiteCode(requestedCode);
        List<SiteDocument> matches = new ArrayList<>();

        for (String candidateCode : candidateSiteCodes(normalizedCode)) {
            siteRepository.findAllBySiteCode(candidateCode).forEach(site -> addUniqueSite(matches, site));
        }

        if (matches.isEmpty()) {
            siteRepository.findAllByOrderBySiteNameAsc().stream()
                    .filter(site -> matchesRequestedSite(site, normalizedCode))
                    .forEach(site -> addUniqueSite(matches, site));
        }

        Optional<SiteDocument> selected = matches.stream()
                .filter(currentSiteAccessService::canAccess)
                .findFirst();

        if (selected.isEmpty()) {
            return new ComparisonSite(null, normalizedCode, normalizedCode, null, null, true);
        }

        SiteDocument site = selected.get();
        return new ComparisonSite(
                site,
                site.getSiteCode(),
                SiteNameNormalizer.normalize(site.getSiteName()),
                site.getSwapDate(),
                site.getGridConnectionDate(),
                false
        );
    }

    private ProductionEstimationService.DailySiteProduction loadSiteValues(
            ComparisonSite site,
            LocalDate startDate,
            LocalDate endDate
    ) {
        Map<LocalDate, Double> valuesByDate = new HashMap<>();
        Set<LocalDate> presentDates = new HashSet<>();

        if (site.document() != null) {
            YearMonth cursor = YearMonth.from(startDate);
            YearMonth endMonth = YearMonth.from(endDate);

            while (!cursor.isAfter(endMonth)) {
                Optional<SiteHistoryDocument> monthHistory = findHistory(
                        site.document(),
                        HistoryGranularity.MONTH,
                        cursor.toString()
                );

                monthHistory.ifPresent(history -> collectDailyValues(history.getPoints(), startDate, endDate, valuesByDate, presentDates));
                cursor = cursor.plusMonths(1);
            }
        }

        String governorate = productionEstimationService.governorateForSite(site.document());
        String zone = productionEstimationService.zoneForGovernorate(governorate);

        return new ProductionEstimationService.DailySiteProduction(
                site.document(),
                site.siteCode(),
                site.siteName(),
                governorate,
                zone,
                site.document() != null && site.document().getInstalledPowerKw() != null ? site.document().getInstalledPowerKw() : 0.0,
                site.swapDate(),
                valuesByDate,
                presentDates
        );
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

    private void collectDailyValues(
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
            Double normalizedValue = productionEstimationService.normalizeHistoryValue(point.getValue());
            if (normalizedValue != null) {
                valuesByDate.merge(day, normalizedValue, Math::max);
            }
        }
    }

    private DailyComparisonCellDto historicalCell(ProductionEstimationService.DailySiteProduction siteValue, LocalDate day) {
        if (siteValue.document() == null) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, "site introuvable", false, "SITE_NOT_FOUND");
        }

        if (!productionEstimationService.isProductionDay(siteValue, day)) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, "pas encore swapé", false, "NOT_SWAPPED_YET");
        }

        if (!productionEstimationService.isPresent(siteValue, day)) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, SWAPPED_NOT_MONITORED, false, "ABSENT");
        }

        Double value = productionEstimationService.valueForDay(siteValue, day);
        if (value == null) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, SWAPPED_NOT_MONITORED, false, "NULL");
        }

        if (value <= EPSILON) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, SWAPPED_NOT_MONITORED, false, "ZERO");
        }

        return new DailyComparisonCellDto(siteValue.siteCode(), productionEstimationService.round2(value), formatKwh(value), false, "ORIGINAL");
    }

    private DailyComparisonCellDto estimatedCell(
            ProductionEstimationService.DailySiteProduction siteValue,
            List<ProductionEstimationService.DailySiteProduction> allSites,
            LocalDate day
    ) {
        if (siteValue.document() == null) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, "site introuvable", false, "SITE_NOT_FOUND");
        }

        if (!productionEstimationService.isProductionDay(siteValue, day)) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, "pas encore swapé", false, "NOT_SWAPPED_YET");
        }

        ProductionEstimationService.DailyProductionDecision decision = productionEstimationService
                .decideDailyProduction(siteValue, allSites, day);

        if (decision.monitored() && decision.effectiveValue() != null) {
            return new DailyComparisonCellDto(
                    siteValue.siteCode(),
                    productionEstimationService.round2(decision.effectiveValue()),
                    formatKwh(decision.effectiveValue()),
                    false,
                    decision.status()
            );
        }

        if (decision.estimated() && decision.effectiveValue() != null) {
            return new DailyComparisonCellDto(
                    siteValue.siteCode(),
                    productionEstimationService.round2(decision.effectiveValue()),
                    formatKwh(decision.effectiveValue()) + " (estimable)",
                    true,
                    decision.status()
            );
        }

        return new DailyComparisonCellDto(siteValue.siteCode(), null, "non estimable", true, decision.status());
    }

    private DailyComparisonRowDto totalRow(String label, List<ComparisonSite> sites, Map<String, Double> totals) {
        List<DailyComparisonCellDto> cells = sites.stream()
                .map(site -> {
                    Double value = totals.get(site.siteCode());
                    return new DailyComparisonCellDto(
                            site.siteCode(),
                            value == null ? null : productionEstimationService.round2(value),
                            value == null ? "—" : formatKwh(value),
                            false,
                            "TOTAL"
                    );
                })
                .toList();

        return new DailyComparisonRowDto(null, label, cells);
    }

    private DailyComparisonSiteDto toSiteDto(ComparisonSite site) {
        SiteDocument document = site.document();
        String governorate = productionEstimationService.governorateForSite(document);
        String zone = productionEstimationService.zoneForGovernorate(governorate);
        return new DailyComparisonSiteDto(
                document != null && document.getBrand() != null ? document.getBrand().name() : null,
                site.siteCode(),
                site.siteName(),
                formatDate(site.swapDate()),
                site.gridConnectionDate(),
                document != null ? productionEstimationService.round2(document.getInstalledPowerKw()) : null,
                governorate,
                zone
        );
    }

    private DailyComparisonSelectableSiteDto toSelectableSiteDto(SiteDocument site) {
        String governorate = productionEstimationService.governorateForSite(site);
        String zone = productionEstimationService.zoneForGovernorate(governorate);
        return new DailyComparisonSelectableSiteDto(
                site.getBrand() != null ? site.getBrand().name() : null,
                site.getSiteCode(),
                SiteNameNormalizer.normalize(site.getSiteName()),
                formatDate(site.getSwapDate()),
                site.getGridConnectionDate(),
                productionEstimationService.round2(site.getInstalledPowerKw()),
                governorate,
                zone
        );
    }

    private List<String> parseSiteCodes(String value) {
        if (value == null || value.isBlank()) {
            return defaultSiteCodesForCurrentScope();
        }

        List<String> codes = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(code -> !code.isBlank())
                .map(this::normalizeSiteCode)
                .distinct()
                .toList();

        return codes.isEmpty() ? defaultSiteCodesForCurrentScope() : codes;
    }

    private List<String> defaultSiteCodesForCurrentScope() {
        List<String> visibleSiteCodes = siteRepository.findAllByOrderBySiteNameAsc()
                .stream()
                .filter(currentSiteAccessService::canAccess)
                .map(SiteDocument::getSiteCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .limit(4)
                .toList();

        if (!visibleSiteCodes.isEmpty()) {
            return visibleSiteCodes;
        }

        return currentSiteAccessService.currentScope() == SiteAccessScope.PROJECT
                ? DEFAULT_SITE_CODES
                : List.of();
    }

    private List<String> candidateSiteCodes(String normalizedCode) {
        if (normalizedCode == null || normalizedCode.isBlank()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        values.add(normalizedCode);
        values.add(normalizedCode.replace("_", ""));
        values.add(normalizedCode.replace("_", " "));

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private String normalizeSiteCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        if (normalized.matches("ARI\\d{4}")) {
            return "ARI_" + normalized.substring(3);
        }
        return normalized;
    }

    private void addToTotal(Map<String, Double> totals, String siteCode, Double value) {
        if (siteCode == null || value == null) {
            return;
        }
        totals.merge(siteCode, value, Double::sum);
    }

    private void addUniqueSite(List<SiteDocument> sites, SiteDocument site) {
        if (site == null) {
            return;
        }

        boolean alreadyAdded = sites.stream().anyMatch(existing -> sameSite(existing, site));
        if (!alreadyAdded) {
            sites.add(site);
        }
    }

    private boolean sameSite(SiteDocument first, SiteDocument second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        if (first.getId() != null && second.getId() != null) {
            return first.getId().equals(second.getId());
        }
        return Objects.equals(first.getBrand(), second.getBrand())
                && Objects.equals(first.getSiteCode(), second.getSiteCode());
    }

    private boolean matchesRequestedSite(SiteDocument site, String normalizedCode) {
        if (site == null || normalizedCode == null || normalizedCode.isBlank()) {
            return false;
        }

        String requestedToken = compactSiteToken(normalizedCode);
        String requestedDigits = requestedToken.replaceAll("\\D", "");
        return fieldMatchesRequestedSite(site.getSiteCode(), requestedToken, requestedDigits)
                || fieldMatchesRequestedSite(site.getProviderSiteId(), requestedToken, requestedDigits)
                || fieldMatchesRequestedSite(site.getSiteName(), requestedToken, requestedDigits);
    }

    private boolean fieldMatchesRequestedSite(String value, String requestedToken, String requestedDigits) {
        if (value == null || requestedToken == null || requestedToken.isBlank()) {
            return false;
        }

        String fieldToken = compactSiteToken(value);
        if (fieldToken.isBlank()) {
            return false;
        }

        if (fieldToken.equals(requestedToken) || fieldToken.contains(requestedToken)) {
            return true;
        }

        return requestedDigits != null
                && requestedDigits.length() >= 3
                && (fieldToken.equals(requestedDigits)
                || fieldToken.endsWith(requestedDigits)
                || (fieldToken.contains("ARI") && fieldToken.contains(requestedDigits)));
    }

    private boolean isAriSite(SiteDocument site) {
        String code = compactSiteToken(site.getSiteCode());
        String name = compactSiteToken(site.getSiteName());
        return code.startsWith("ARI") || name.startsWith("ARI") || name.contains("ARI");
    }

    private String compactSiteToken(String value) {
        return value == null
                ? ""
                : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
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

    private LocalDate toLocalDate(Long collectTime) {
        if (collectTime == null) {
            return null;
        }

        long epochMillis = collectTime < 1_000_000_000_000L ? collectTime * 1000 : collectTime;
        return Instant.ofEpochMilli(epochMillis).atZone(TUNIS_ZONE).toLocalDate();
    }

    private String formatDate(LocalDate date) {
        return date != null ? date.toString() : null;
    }

    private String formatDisplayDate(LocalDate date) {
        if (date == null) {
            return "—";
        }
        return date.getDayOfMonth() + "/" + date.getMonthValue() + "/" + date.getYear();
    }

    private String formatKwh(Double value) {
        if (value == null) {
            return "—";
        }
        return String.format(Locale.FRANCE, "%.2f", productionEstimationService.round2(value));
    }

    private record ComparisonSite(
            SiteDocument document,
            String siteCode,
            String siteName,
            LocalDate swapDate,
            String gridConnectionDate,
            boolean missingSite
    ) {
    }
}
