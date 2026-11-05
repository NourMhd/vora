package com.fusionsolar.monitoring.service.report;

import com.fusionsolar.monitoring.dto.AlarmDashboardDto;
import com.fusionsolar.monitoring.dto.AlarmDto;
import com.fusionsolar.monitoring.dto.AlarmSectionDto;
import com.fusionsolar.monitoring.dto.PerformanceReportRowDto;
import com.fusionsolar.monitoring.dto.report.*;
import com.fusionsolar.monitoring.service.AlarmService;
import com.fusionsolar.monitoring.service.PerformanceReportService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AiReportService {

    private static final int RANKING_LIMIT = 8;
    private static final int TECHNICAL_PREVIEW_LIMIT = 120;

    private final PerformanceReportService performanceReportService;
    private final AlarmService alarmService;
    private final GeminiReportNarrativeService geminiReportNarrativeService;
    private final SimplePdfReportService simplePdfReportService;

    public AiReportService(
            PerformanceReportService performanceReportService,
            AlarmService alarmService,
            GeminiReportNarrativeService geminiReportNarrativeService,
            SimplePdfReportService simplePdfReportService
    ) {
        this.performanceReportService = performanceReportService;
        this.alarmService = alarmService;
        this.geminiReportNarrativeService = geminiReportNarrativeService;
        this.simplePdfReportService = simplePdfReportService;
    }

    public AiReportPreviewDto buildPreview(
            LocalDate startDate,
            LocalDate endDate,
            String scope,
            String scopeValue,
            String type,
            boolean useAi
    ) {
        validateRange(startDate, endDate);
        String reportType = normalizeReportType(type);

        List<PerformanceReportRowDto> allRows = performanceReportService.getReport(
                "CUSTOM",
                startDate.getYear(),
                startDate.getMonthValue(),
                null,
                startDate.toString(),
                endDate.toString()
        );
        List<PerformanceReportRowDto> rows = filterByScope(allRows, scope, scopeValue);
        AlarmDashboardDto alarmDashboard = alarmService.getDashboard();
        List<AlarmDto> activeAlarms = filterAlarmsByScope(flattenActiveAlarms(alarmDashboard), rows, scope, scopeValue);

        ReportProductionSummaryDto productionSummary = buildProductionSummary(rows);
        ReportGlobalKpisDto globalKpis = buildGlobalKpis(rows, productionSummary, activeAlarms.size());
        ReportDataQualityDto dataQuality = buildDataQuality(rows, productionSummary);
        List<ReportZoneAnalysisDto> zoneAnalysis = buildZoneAnalysis(rows, activeAlarms);
        List<ReportGovernorateAnalysisDto> governorateAnalysis = buildGovernorateAnalysis(rows, activeAlarms);
        List<ReportSiteRankingDto> bestSites = buildBestSites(rows);
        List<ReportSiteRankingDto> worstSites = buildWorstSites(rows);
        ReportAlarmSummaryDto alarms = buildAlarmSummary(alarmDashboard, activeAlarms);
        List<ReportRecommendationDto> recommendations = buildRecommendations(worstSites, alarms, dataQuality, productionSummary);
        List<ReportTechnicalRowDto> appendix = buildTechnicalAppendix(rows, reportType);

        AiReportPreviewDto preview = AiReportPreviewDto.builder()
                .period(ReportPeriodDto.builder()
                        .startDate(startDate.toString())
                        .endDate(endDate.toString())
                        .daysCount((int) ChronoUnit.DAYS.between(startDate, endDate) + 1)
                        .build())
                .scope(scope)
                .scopeValue(scopeValue == null ? null : scopeValue.trim())
                .reportType(reportType)
                .generatedAt(Instant.now().toString())
                .globalKpis(globalKpis)
                .productionSummary(productionSummary)
                .dataQuality(dataQuality)
                .zoneAnalysis(zoneAnalysis)
                .governorateAnalysis(governorateAnalysis)
                .bestSites(bestSites)
                .worstSites(worstSites)
                .alarms(alarms)
                .recommendations(recommendations)
                .technicalAppendix(appendix)
                .aiEnabled(true)
                .aiStatus("GEMINI_PENDING")
                .build();

        enrichNarrative(preview, useAi);
        return preview;
    }

    public byte[] buildPdf(
            LocalDate startDate,
            LocalDate endDate,
            String scope,
            String scopeValue,
            String type,
            boolean useAi
    ) {
        AiReportPreviewDto preview = buildPreview(startDate, endDate, scope, scopeValue, type, useAi);
        return simplePdfReportService.generate(preview);
    }

    private String normalizeReportType(String raw) {
        String value = raw == null || raw.isBlank() ? "STANDARD" : raw.trim().toUpperCase(Locale.ROOT);
        return "SUMMARY".equals(value) ? "SUMMARY" : "STANDARD";
    }

    private void enrichNarrative(AiReportPreviewDto preview, boolean useAi) {
        if (!useAi) {
            throw new GeminiReportNarrativeService.GeminiAiUnavailableException(
                    "Rapport AI configuré en mode Gemini obligatoire : le paramètre useAi=false est interdit."
            );
        }

        try {
            GeminiReportNarrativeService.NarrativeResult result = geminiReportNarrativeService.generate(preview);
            preview.setAiEnabled(true);
            preview.setAiStatus(result.status());
            preview.setAiNarrative(result.narrative());
        } catch (GeminiReportNarrativeService.GeminiAiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new GeminiReportNarrativeService.GeminiAiUnavailableException(
                    "Rapport AI non généré : Gemini est obligatoire et la génération a échoué. "
                            + e.getMessage(),
                    e
            );
        }
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String fmt(Double value) {
        if (value == null) return "—";
        return String.format(Locale.FRANCE, "%.2f", value);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength) + "...";
    }

    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate are required");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be greater than or equal to startDate");
        }
    }

    private List<PerformanceReportRowDto> filterByScope(List<PerformanceReportRowDto> rows, String scope, String scopeValue) {
        String cleanScope = scope == null ? "ALL" : scope.trim().toUpperCase(Locale.ROOT);
        String value = scopeValue == null ? "" : scopeValue.trim();
        if (value.isBlank() || "ALL".equals(cleanScope)) {
            return rows;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        return rows.stream().filter(row -> switch (cleanScope) {
            case "ZONE" -> normalized.equalsIgnoreCase(nullTo(row.getZone(), "UNKNOWN"));
            case "GOVERNORATE" -> normalized.equalsIgnoreCase(nullTo(row.getGovernorate(), "UNKNOWN"));
            case "SITE" -> normalized.equalsIgnoreCase(nullTo(row.getSiteCode(), ""))
                    || normalized.equalsIgnoreCase(nullTo(row.getSiteName(), ""));
            default -> true;
        }).toList();
    }

    private List<AlarmDto> filterAlarmsByScope(List<AlarmDto> alarms, List<PerformanceReportRowDto> scopedRows, String scope, String scopeValue) {
        String cleanScope = scope == null ? "ALL" : scope.trim().toUpperCase(Locale.ROOT);
        String value = scopeValue == null ? "" : scopeValue.trim();
        if (value.isBlank() || "ALL".equals(cleanScope)) {
            return alarms;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        List<String> scopedSiteCodes = scopedRows.stream()
                .map(PerformanceReportRowDto::getSiteCode)
                .filter(Objects::nonNull)
                .toList();
        return alarms.stream().filter(alarm -> switch (cleanScope) {
            case "GOVERNORATE" -> normalized.equalsIgnoreCase(nullTo(alarm.getGovernorate(), "UNKNOWN"));
            case "SITE" -> normalized.equalsIgnoreCase(nullTo(alarm.getSiteCode(), ""))
                    || normalized.equalsIgnoreCase(nullTo(alarm.getSiteName(), ""));
            case "ZONE" -> scopedSiteCodes.contains(alarm.getSiteCode());
            default -> true;
        }).toList();
    }

    private ReportProductionSummaryDto buildProductionSummary(List<PerformanceReportRowDto> rows) {
        double displayed = sum(rows, PerformanceReportRowDto::getActualKwh);
        return ReportProductionSummaryDto.builder()
                .displayedKwh(round2(displayed))
                .bonusSites(0)
                .malusSites(0)
                .build();
    }

    private ReportGlobalKpisDto buildGlobalKpis(List<PerformanceReportRowDto> rows, ReportProductionSummaryDto production, int activeAlarms) {
        int healthy = countStatus(rows, "HEALTHY", "ONLINE");
        int faulty = countStatus(rows, "FAULTY", "FAULT", "ALARM");
        int disconnected = countStatus(rows, "DISCONNECTED", "OFFLINE");
        int missing = (int) rows.stream().filter(row -> nullTo(row.getDataStatus(), "OK").startsWith("MISSING")).count();
        return ReportGlobalKpisDto.builder()
                .totalSites(rows.size())
                .healthySites(healthy)
                .faultySites(faulty)
                .disconnectedSites(disconnected)
                .missingDataSites(missing)
                .totalInstalledPowerKw(round2(sum(rows, PerformanceReportRowDto::getInstalledPowerKw)))
                .activeAlarms(activeAlarms)
                .globalDifferencePercent(null)
                .globalStatus(globalStatus(null, activeAlarms, missing))
                .build();
    }

    private ReportDataQualityDto buildDataQuality(List<PerformanceReportRowDto> rows, ReportProductionSummaryDto production) {
        int productionDays = rows.stream().map(PerformanceReportRowDto::getProductionDaysCount).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        int capturedDays = rows.stream().map(PerformanceReportRowDto::getCapturedDaysCount).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        int estimatedDays = rows.stream().map(PerformanceReportRowDto::getEstimatedDaysCount).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        int missingReal = (int) rows.stream().filter(row -> "MISSING_REAL_KWH".equalsIgnoreCase(nullTo(row.getDataStatus(), ""))).count();
        int missingExpected = (int) rows.stream().filter(row -> "MISSING_EXPECTED_KWH".equalsIgnoreCase(nullTo(row.getDataStatus(), ""))).count();
        Double reliability = productionDays > 0 ? round2(capturedDays * 100.0 / productionDays) : null;
        double displayed = Optional.ofNullable(production.getDisplayedKwh()).orElse(0.0);
        double estimated = Optional.ofNullable(production.getEstimatedKwh()).orElse(0.0);
        Double estimatedShare = Math.abs(displayed) > 0.000001 ? round2(estimated * 100.0 / displayed) : null;
        return ReportDataQualityDto.builder()
                .expectedProductionDays(productionDays)
                .capturedDays(capturedDays)
                .estimatedDays(estimatedDays)
                .missingRealKwhSites(missingReal)
                .missingExpectedKwhSites(missingExpected)
                .reliabilityPercent(reliability)
                .estimatedSharePercent(estimatedShare)
                .status(dataQualityStatus(reliability, estimatedShare, missingReal + missingExpected))
                .build();
    }

    private List<ReportZoneAnalysisDto> buildZoneAnalysis(List<PerformanceReportRowDto> rows, List<AlarmDto> activeAlarms) {
        Map<String, List<PerformanceReportRowDto>> grouped = rows.stream()
                .collect(Collectors.groupingBy(row -> nullTo(row.getZone(), "UNKNOWN"), LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
                .map(entry -> {
                    List<PerformanceReportRowDto> groupRows = entry.getValue();
                    double actual = sum(groupRows, PerformanceReportRowDto::getActualKwh);
                    return ReportZoneAnalysisDto.builder()
                            .zone(entry.getKey())
                            .sitesCount(groupRows.size())
                            .installedPowerKw(round2(sum(groupRows, PerformanceReportRowDto::getInstalledPowerKw)))
                            .actualKwh(round2(actual))
                            .activeAlarms(countAlarmsForZone(activeAlarms, groupRows))
                            .build();
                })
                .sorted(Comparator.comparing(ReportZoneAnalysisDto::getZone))
                .toList();
    }

    private List<ReportGovernorateAnalysisDto> buildGovernorateAnalysis(List<PerformanceReportRowDto> rows, List<AlarmDto> activeAlarms) {
        Map<String, List<PerformanceReportRowDto>> grouped = rows.stream()
                .collect(Collectors.groupingBy(row -> nullTo(row.getGovernorate(), "UNKNOWN"), LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
                .map(entry -> {
                    List<PerformanceReportRowDto> groupRows = entry.getValue();
                    double actual = sum(groupRows, PerformanceReportRowDto::getActualKwh);
                    return ReportGovernorateAnalysisDto.builder()
                            .governorate(entry.getKey())
                            .zone(groupRows.stream().map(PerformanceReportRowDto::getZone).filter(Objects::nonNull).findFirst().orElse("UNKNOWN"))
                            .sitesCount(groupRows.size())
                            .installedPowerKw(round2(sum(groupRows, PerformanceReportRowDto::getInstalledPowerKw)))
                            .actualKwh(round2(actual))
                            .activeAlarms((int) activeAlarms.stream().filter(alarm -> entry.getKey().equalsIgnoreCase(nullTo(alarm.getGovernorate(), "UNKNOWN"))).count())
                            .build();
                })
                .sorted(Comparator.comparing(ReportGovernorateAnalysisDto::getGovernorate, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private List<ReportSiteRankingDto> buildBestSites(List<PerformanceReportRowDto> rows) {
        return rows.stream()
                .filter(row -> row.getActualKwh() != null && row.getActualKwh() > 0.0)
                .sorted(Comparator.comparing(this::productionPerKwp, Comparator.nullsLast(Double::compareTo)).reversed())
                .limit(RANKING_LIMIT)
                .map(row -> toRanking(row, "Observation", "Production totale élevée sur la période"))
                .toList();
    }

    private List<ReportSiteRankingDto> buildWorstSites(List<PerformanceReportRowDto> rows) {
        return rows.stream()
                .filter(row -> isProblemStatus(row.getSiteStatus()))
                .sorted(Comparator.comparing(PerformanceReportRowDto::getSiteName, Comparator.nullsLast(String::compareTo)))
                .limit(RANKING_LIMIT)
                .map(row -> toRanking(row, priorityForRow(row), reasonForRow(row)))
                .toList();
    }

    private ReportAlarmSummaryDto buildAlarmSummary(AlarmDashboardDto alarmDashboard, List<AlarmDto> activeAlarms) {
        int critical = (int) activeAlarms.stream().filter(alarm -> "CRITICAL".equalsIgnoreCase(nullTo(alarm.getSeverity(), ""))).count();
        int warning = (int) activeAlarms.stream().filter(alarm -> "WARNING".equalsIgnoreCase(nullTo(alarm.getSeverity(), ""))).count();
        List<ReportAlarmTypeDto> byType = alarmDashboard.getActiveSections() == null ? List.of() : alarmDashboard.getActiveSections().stream()
                .map(section -> ReportAlarmTypeDto.builder()
                        .type(section.getType())
                        .title(section.getTitle())
                        .count((int) activeAlarms.stream().filter(alarm -> nullTo(alarm.getType(), "").equalsIgnoreCase(nullTo(section.getType(), ""))).count())
                        .build())
                .toList();
        List<ReportAlarmItemDto> top = activeAlarms.stream()
                .sorted(Comparator.comparing((AlarmDto alarm) -> "CRITICAL".equalsIgnoreCase(nullTo(alarm.getSeverity(), "")) ? 0 : 1)
                        .thenComparing(AlarmDto::getFirstDetectedAt, Comparator.nullsLast(String::compareTo)))
                .limit(10)
                .map(alarm -> ReportAlarmItemDto.builder()
                        .type(alarm.getType())
                        .severity(alarm.getSeverity())
                        .siteCode(alarm.getSiteCode())
                        .siteName(alarm.getSiteName())
                        .governorate(alarm.getGovernorate())
                        .title(alarm.getTitle())
                        .message(alarm.getMessage())
                        .firstDetectedAt(alarm.getFirstDetectedAt())
                        .lastDetectedAt(alarm.getLastDetectedAt())
                        .build())
                .toList();
        return ReportAlarmSummaryDto.builder()
                .activeCount(activeAlarms.size())
                .criticalCount(critical)
                .warningCount(warning)
                .historyCount(alarmDashboard.getHistoryCount())
                .byType(byType)
                .topActiveAlarms(top)
                .build();
    }

    private List<ReportRecommendationDto> buildRecommendations(
            List<ReportSiteRankingDto> worstSites,
            ReportAlarmSummaryDto alarms,
            ReportDataQualityDto dataQuality,
            ReportProductionSummaryDto production
    ) {
        List<ReportRecommendationDto> recommendations = new ArrayList<>();
        if (alarms.getActiveCount() != null && alarms.getActiveCount() > 0) {
            recommendations.add(ReportRecommendationDto.builder()
                    .priority("Prioritaire")
                    .target("Points à vérifier")
                    .issue("Suivi opérationnel recommandé")
                    .action("Contrôler les sites concernés et confirmer l’état terrain si le point de suivi persiste.")
                    .justification("Le rapport garde une lecture synthétique et orientée action.")
                    .build());
        }
        for (ReportSiteRankingDto site : worstSites.stream().limit(3).toList()) {
            recommendations.add(ReportRecommendationDto.builder()
                    .priority(site.getPriority())
                    .target(site.getSiteName())
                    .issue(site.getReason())
                    .action("Comparer la production totale du site avec les sites proches du même gouvernorat et prioriser une vérification simple.")
                    .justification("Site retenu dans les points de suivi opérationnel.")
                    .build());
        }
        if (recommendations.isEmpty()) {
            recommendations.add(ReportRecommendationDto.builder()
                    .priority("Normale")
                    .target("Parc photovoltaïque")
                    .issue("Performance globale cohérente")
                    .action("Continuer le suivi périodique et conserver la même méthode de calcul de production totale.")
                    .justification("Les KPI globaux ne montrent pas de dérive significative.")
                    .build());
        }
        return recommendations;
    }

    private List<ReportTechnicalRowDto> buildTechnicalAppendix(List<PerformanceReportRowDto> rows, String type) {
        int limit = "SUMMARY".equalsIgnoreCase(type == null ? "STANDARD" : type)
                ? 20
                : TECHNICAL_PREVIEW_LIMIT;
        return rows.stream()
                .sorted(Comparator.comparing(PerformanceReportRowDto::getSiteName, Comparator.nullsLast(String::compareTo)))
                .limit(limit)
                .map(row -> ReportTechnicalRowDto.builder()
                        .brand(row.getBrand())
                        .siteCode(row.getSiteCode())
                        .siteName(row.getSiteName())
                        .status(row.getSiteStatus())
                        .zone(row.getZone())
                        .governorate(row.getGovernorate())
                        .plantType(row.getPlantType())
                        .installedPowerKw(row.getInstalledPowerKw())
                        .swapDate(row.getSwapDate())
                        .gridConnectionDate(row.getGridConnectionDate())
                        .filterDaysCount(row.getFilterDaysCount())
                        .productionDaysCount(row.getProductionDaysCount())
                        .capturedDaysCount(row.getCapturedDaysCount())
                        .estimatedDaysCount(row.getEstimatedDaysCount())
                        .displayedKwh(row.getActualKwh())
                        .differencePercent(null)
                        .bonusMalus(null)
                        .dataStatus(row.getDataStatus())
                        .build())
                .toList();
    }

    private List<AlarmDto> flattenActiveAlarms(AlarmDashboardDto dashboard) {
        if (dashboard == null || dashboard.getActiveSections() == null) {
            return List.of();
        }
        return dashboard.getActiveSections().stream()
                .map(AlarmSectionDto::getAlarms)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .toList();
    }

    private ReportSiteRankingDto toRanking(PerformanceReportRowDto row, String priority, String reason) {
        return ReportSiteRankingDto.builder()
                .brand(row.getBrand())
                .siteCode(row.getSiteCode())
                .siteName(row.getSiteName())
                .status(row.getSiteStatus())
                .zone(row.getZone())
                .governorate(row.getGovernorate())
                .installedPowerKw(row.getInstalledPowerKw())
                .actualKwh(row.getActualKwh())
                .differencePercent(null)
                .bonusMalus(null)
                .capturedDays(row.getCapturedDaysCount())
                .estimatedDays(row.getEstimatedDaysCount())
                .priority(priority)
                .reason(reason)
                .build();
    }

    private int priorityScore(PerformanceReportRowDto row) {
        String status = nullTo(row.getSiteStatus(), "").toUpperCase(Locale.ROOT);
        if (status.contains("FAULT") || status.contains("ALARM")) return 2;
        if (status.contains("OFFLINE") || status.contains("DISCONNECTED")) return 1;
        return 0;
    }

    private String priorityForRow(PerformanceReportRowDto row) {
        int score = priorityScore(row);
        if (score >= 2) return "Prioritaire";
        if (score == 1) return "À suivre";
        return "Observation";
    }

    private String reasonForRow(PerformanceReportRowDto row) {
        String status = nullTo(row.getSiteStatus(), "").toUpperCase(Locale.ROOT);
        if (status.contains("FAULT") || status.contains("ALARM")) return "Point de suivi opérationnel";
        if (status.contains("OFFLINE") || status.contains("DISCONNECTED")) return "Disponibilité à vérifier";
        return "Observation de suivi";
    }

    private Double productionPerKwp(PerformanceReportRowDto row) {
        Double actual = row.getActualKwh();
        Double power = row.getInstalledPowerKw();
        if (actual == null || power == null || Math.abs(power) < 0.000001) return null;
        return actual / power;
    }

    private boolean isProblemStatus(String status) {
        String value = nullTo(status, "").toUpperCase(Locale.ROOT);
        return value.contains("FAULT") || value.contains("ALARM") || value.contains("OFFLINE") || value.contains("DISCONNECTED");
    }

    private String globalStatus(Double differencePercent, int activeAlarms, int missing) {
        if (activeAlarms == 0) return "BON";
        return "À SUIVRE";
    }

    private String dataQualityStatus(Double reliability, Double estimatedShare, int missingSites) {
        double rel = reliability == null ? 0.0 : reliability;
        double share = estimatedShare == null ? 0.0 : estimatedShare;
        if (missingSites > 0 || rel < 70.0 || share > 30.0) return "À CONSOLIDER";
        if (rel < 90.0 || share > 15.0) return "CORRECTE";
        return "BONNE";
    }

    private int countStatus(List<PerformanceReportRowDto> rows, String... statuses) {
        List<String> accepted = List.of(statuses).stream().map(s -> s.toUpperCase(Locale.ROOT)).toList();
        return (int) rows.stream().filter(row -> accepted.contains(nullTo(row.getSiteStatus(), "").toUpperCase(Locale.ROOT))).count();
    }

    private int countAlarmsForZone(List<AlarmDto> alarms, List<PerformanceReportRowDto> zoneRows) {
        List<String> siteCodes = zoneRows.stream().map(PerformanceReportRowDto::getSiteCode).filter(Objects::nonNull).toList();
        return (int) alarms.stream().filter(alarm -> siteCodes.contains(alarm.getSiteCode())).count();
    }

    private double sum(List<PerformanceReportRowDto> rows, Function<PerformanceReportRowDto, Double> mapper) {
        return rows.stream().map(mapper).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
    }

    private Double percent(double diff, double expected) {
        return Math.abs(expected) > 0.000001 ? round2(diff * 100.0 / expected) : null;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String nullTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String signed(Double value) {
        if (value == null) return "—";
        return (value > 0 ? "+" : "") + String.format(Locale.FRANCE, "%.2f", value);
    }
}
