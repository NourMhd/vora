package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.util.SiteProductionPolicy;
import com.fusionsolar.monitoring.dto.DailyComparisonCellDto;
import com.fusionsolar.monitoring.dto.DailyComparisonReportDto;
import com.fusionsolar.monitoring.dto.DailyComparisonRowDto;
import com.fusionsolar.monitoring.dto.DailyComparisonSelectableSiteDto;
import com.fusionsolar.monitoring.dto.DailyComparisonSiteDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.util.SiteNameNormalizer;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class DailyComparisonService {

    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final List<String> DEFAULT_SITE_CODES = List.of(
            "ARI_0222",
            "ARI_0112",
            "ARI_0061",
            "ARI_0088"
    );
    private static final String SWAPPED_NOT_MONITORED = "swapé non monitorisé";

    private final SiteRepository siteRepository;
    private final SiteHistoryRepository siteHistoryRepository;
    private final ProductionEstimationService productionEstimationService;
    private final CurrentSiteAccessService currentSiteAccessService;

    public DailyComparisonService(SiteRepository siteRepository, SiteHistoryRepository siteHistoryRepository,
            ProductionEstimationService productionEstimationService, CurrentSiteAccessService currentSiteAccessService) {
        this.siteRepository = siteRepository;
        this.siteHistoryRepository = siteHistoryRepository;
        this.productionEstimationService = productionEstimationService;
        this.currentSiteAccessService = currentSiteAccessService;
    }

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
                .map(site -> ProductionEstimationService.productionOrigin(site.document()))
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(safeEnd);

        LocalDate effectiveStartDate = requestedStart != null ? requestedStart : defaultStartDate;

        if (safeEnd.isBefore(effectiveStartDate)) {
            Map<String, Double> zeroTotals = new HashMap<>();
            for (var site : sites) {
                if (ProductionEstimationService.productionOrigin(site.document()) != null) {
                    zeroTotals.put(site.siteCode(), 0.0);
                }
            }
            return new DailyComparisonReportDto(
                    sites.stream().map(this::toSiteDto).toList(),
                    formatDate(effectiveStartDate),
                    formatDate(safeEnd),
                    List.of(),
                    List.of(),
                    totalRow("TOTAL", sites, zeroTotals),
                    totalRow("TOTAL", sites, zeroTotals)
            );
        }

        List<SiteDocument> inventory = siteRepository.findAllByOrderBySiteNameAsc();
        List<ProductionEstimationService.DailySiteProduction> siteValues = sites.stream()
                .map(site -> ProductionHistoryLoader.load(site.document(), site.siteCode(), site.siteName(),
                        siteHistoryRepository, productionEstimationService,
                        site.document() != null && ProductionHistoryLoader.unambiguous(site.document(), inventory), yesterday))
                .toList();
        Map<ProductionEstimationService.DailySiteProduction, ProductionEstimationService.SiteReference> references = new java.util.IdentityHashMap<>();
        for (var site : siteValues) references.put(site, productionEstimationService.continuousReference(site, yesterday));

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
                        cursor, yesterday, references.get(siteValue)
                );

                historicalCells.add(historicalCell);
                estimatedCells.add(estimatedCell);
            }

            historicalRows.add(new DailyComparisonRowDto(formatDate(cursor), formatDisplayDate(cursor), historicalCells));
            estimatedRows.add(new DailyComparisonRowDto(formatDate(cursor), formatDisplayDate(cursor), estimatedCells));
            cursor = cursor.plusDays(1);
        }

        for (var site : siteValues) {
            var total = productionEstimationService.continuousProduction(site, effectiveStartDate, safeEnd, yesterday, references.get(site));
            if (total.realKwh() != null && total.displayedKwh() != null) {
                historicalTotals.put(site.siteCode(), total.realKwh());
                estimatedTotals.put(site.siteCode(), total.displayedKwh());
            }
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

    private DailyComparisonCellDto historicalCell(ProductionEstimationService.DailySiteProduction siteValue, LocalDate day) {
        if (siteValue.document() == null) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, "site introuvable", false, "SITE_NOT_FOUND");
        }

        if (ProductionEstimationService.productionOrigin(siteValue.document()) == null
                || day.isBefore(ProductionEstimationService.productionOrigin(siteValue.document()))) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, "pas encore swapé", false, "NOT_SWAPPED_YET");
        }
        if (SiteProductionPolicy.forcesZero(siteValue.document())) {
            return new DailyComparisonCellDto(siteValue.siteCode(), 0.0, formatKwh(0.0), false, "FORCED_ZERO");
        }

        if (!productionEstimationService.isPresent(siteValue, day)) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, SWAPPED_NOT_MONITORED, false, "ABSENT");
        }

        Double value = productionEstimationService.rawReportValue(siteValue, day);
        if (value == null) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, SWAPPED_NOT_MONITORED, false, "NULL");
        }

        if (value <= 0) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, SWAPPED_NOT_MONITORED, false, "ZERO");
        }

        return new DailyComparisonCellDto(siteValue.siteCode(), value, formatKwh(value), false, "ORIGINAL");
    }

    private DailyComparisonCellDto estimatedCell(
            ProductionEstimationService.DailySiteProduction siteValue,
            List<ProductionEstimationService.DailySiteProduction> allSites,
            LocalDate day
    ) {
        LocalDate yesterday = LocalDate.now(TUNIS_ZONE).minusDays(1);
        return estimatedCell(siteValue, day, yesterday, productionEstimationService.continuousReference(siteValue, yesterday));
    }

    private DailyComparisonCellDto estimatedCell(ProductionEstimationService.DailySiteProduction siteValue,
            LocalDate day, LocalDate yesterday, ProductionEstimationService.SiteReference reference) {
        if (siteValue.document() == null) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, "site introuvable", false, "SITE_NOT_FOUND");
        }

        if (ProductionEstimationService.productionOrigin(siteValue.document()) == null
                || day.isBefore(ProductionEstimationService.productionOrigin(siteValue.document()))) {
            return new DailyComparisonCellDto(siteValue.siteCode(), null, "pas encore swapé", false, "NOT_SWAPPED_YET");
        }
        if (SiteProductionPolicy.forcesZero(siteValue.document())) {
            return new DailyComparisonCellDto(siteValue.siteCode(), 0.0, formatKwh(0.0), false, "FORCED_ZERO");
        }

        ProductionEstimationService.DailyProductionDecision decision = productionEstimationService
                .continuousDailyDecision(siteValue, day, yesterday, reference);

        if (decision.monitored() && decision.effectiveValue() != null) {
            return new DailyComparisonCellDto(
                    siteValue.siteCode(),
                    decision.effectiveValue(),
                    formatKwh(decision.effectiveValue()),
                    false,
                    decision.status()
            );
        }

        if (decision.estimated() && decision.effectiveValue() != null) {
            return new DailyComparisonCellDto(
                    siteValue.siteCode(),
                    decision.effectiveValue(),
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
