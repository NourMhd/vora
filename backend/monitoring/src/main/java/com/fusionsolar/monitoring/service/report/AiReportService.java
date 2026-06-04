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
        double expected = sum(rows, PerformanceReportRowDto::getExpectedKwh);
        double captured = sum(rows, PerformanceReportRowDto::getCapturedKwh);
        double estimated = sum(rows, PerformanceReportRowDto::getEstimatedMissingKwh);
        double displayed = sum(rows, PerformanceReportRowDto::getActualKwh);
        double diff = displayed - expected;
        Double diffPct = Math.abs(expected) > 0.000001 ? round2(diff * 100.0 / expected) : null;
        return ReportProductionSummaryDto.builder()
                .expectedKwh(round2(expected))
                .capturedKwh(round2(captured))
                .estimatedKwh(round2(estimated))
                .displayedKwh(round2(displayed))
                .differenceKwh(round2(diff))
                .differencePercent(diffPct)
                .bonusSites((int) rows.stream().filter(row -> "BONUS".equalsIgnoreCase(nullTo(row.getBonusMalus(), ""))).count())
                .malusSites((int) rows.stream().filter(row -> "MALUS".equalsIgnoreCase(nullTo(row.getBonusMalus(), ""))).count())
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
                .globalDifferencePercent(production.getDifferencePercent())
                .globalStatus(globalStatus(production.getDifferencePercent(), activeAlarms, missing))
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
                    double expected = sum(groupRows, PerformanceReportRowDto::getExpectedKwh);
                    double actual = sum(groupRows, PerformanceReportRowDto::getActualKwh);
                    double estimated = sum(groupRows, PerformanceReportRowDto::getEstimatedMissingKwh);
                    return ReportZoneAnalysisDto.builder()
                            .zone(entry.getKey())
                            .sitesCount(groupRows.size())
                            .installedPowerKw(round2(sum(groupRows, PerformanceReportRowDto::getInstalledPowerKw)))
                            .expectedKwh(round2(expected))
                            .actualKwh(round2(actual))
                            .estimatedKwh(round2(estimated))
                            .differencePercent(percent(actual - expected, expected))
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
                    double expected = sum(groupRows, PerformanceReportRowDto::getExpectedKwh);
                    double actual = sum(groupRows, PerformanceReportRowDto::getActualKwh);
                    double estimated = sum(groupRows, PerformanceReportRowDto::getEstimatedMissingKwh);
                    return ReportGovernorateAnalysisDto.builder()
                            .governorate(entry.getKey())
                            .zone(groupRows.stream().map(PerformanceReportRowDto::getZone).filter(Objects::nonNull).findFirst().orElse("UNKNOWN"))
                            .sitesCount(groupRows.size())
                            .installedPowerKw(round2(sum(groupRows, PerformanceReportRowDto::getInstalledPowerKw)))
                            .expectedKwh(round2(expected))
                            .actualKwh(round2(actual))
                            .estimatedKwh(round2(estimated))
                            .differencePercent(percent(actual - expected, expected))
                            .activeAlarms((int) activeAlarms.stream().filter(alarm -> entry.getKey().equalsIgnoreCase(nullTo(alarm.getGovernorate(), "UNKNOWN"))).count())
                            .build();
                })
                .sorted(Comparator.comparing(ReportGovernorateAnalysisDto::getDifferencePercent, Comparator.nullsLast(Double::compareTo)))
                .toList();
    }

    private List<ReportSiteRankingDto> buildBestSites(List<PerformanceReportRowDto> rows) {
        return rows.stream()
                .filter(row -> row.getDifferencePercent() != null)
                .sorted(Comparator.comparing(PerformanceReportRowDto::getDifferencePercent, Comparator.nullsLast(Double::compareTo)).reversed())
                .limit(RANKING_LIMIT)
                .map(row -> toRanking(row, "Observation", "Site performant sur la période"))
                .toList();
    }

    private List<ReportSiteRankingDto> buildWorstSites(List<PerformanceReportRowDto> rows) {
        return rows.stream()
                .filter(row -> row.getDifferencePercent() != null || isProblemStatus(row.getSiteStatus()) || nullTo(row.getDataStatus(), "OK").startsWith("MISSING"))
                .sorted(Comparator
                        .comparing((PerformanceReportRowDto row) -> priorityScore(row), Comparator.reverseOrder())
                        .thenComparing(row -> Optional.ofNullable(row.getDifferencePercent()).orElse(999999.0)))
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
        if (alarms.getCriticalCount() != null && alarms.getCriticalCount() > 0) {
            recommendations.add(ReportRecommendationDto.builder()
                    .priority("Critique")
                    .target("Sites avec alarmes critiques")
                    .issue("Défaut, offline ou absence de monitoring longue")
                    .action("Vérifier la connectivité, le statut fournisseur et les équipements avant toute analyse fine de performance.")
                    .justification("Une alarme critique rend les KPI de production moins fiables.")
                    .build());
        }
        if (dataQuality.getReliabilityPercent() != null && dataQuality.getReliabilityPercent() < 85.0) {
            recommendations.add(ReportRecommendationDto.builder()
                    .priority("Élevée")
                    .target("Qualité monitoring")
                    .issue("Fiabilité des données inférieure à 85%")
                    .action("Compléter l’historique manquant et contrôler les sites avec jours estimés.")
                    .justification("La part estimée peut masquer une vraie sous-performance.")
                    .build());
        }
        for (ReportSiteRankingDto site : worstSites.stream().limit(4).toList()) {
            recommendations.add(ReportRecommendationDto.builder()
                    .priority(site.getPriority())
                    .target(site.getSiteName())
                    .issue(site.getReason())
                    .action("Comparer le site avec les meilleurs sites du même gouvernorat puis vérifier l’état onduleur, compteur et communication.")
                    .justification("Écart période: " + signed(site.getDifferencePercent()) + " %.")
                    .build());
        }
        if (recommendations.isEmpty()) {
            recommendations.add(ReportRecommendationDto.builder()
                    .priority("Normale")
                    .target("Parc photovoltaïque")
                    .issue("Aucun risque majeur détecté")
                    .action("Continuer le suivi quotidien et conserver la même règle de calcul réel + estimé pour le reporting.")
                    .justification("Les KPI globaux ne montrent pas de dérive critique.")
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
                        .expectedKwh(row.getExpectedKwh())
                        .capturedKwh(row.getCapturedKwh())
                        .estimatedKwh(row.getEstimatedMissingKwh())
                        .displayedKwh(row.getActualKwh())
                        .differencePercent(row.getDifferencePercent())
                        .bonusMalus(row.getBonusMalus())
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
                .expectedKwh(row.getExpectedKwh())
                .actualKwh(row.getActualKwh())
                .estimatedKwh(row.getEstimatedMissingKwh())
                .differencePercent(row.getDifferencePercent())
                .bonusMalus(row.getBonusMalus())
                .capturedDays(row.getCapturedDaysCount())
                .estimatedDays(row.getEstimatedDaysCount())
                .priority(priority)
                .reason(reason)
                .build();
    }

    private int priorityScore(PerformanceReportRowDto row) {
        if (isProblemStatus(row.getSiteStatus())) return 4;
        if (nullTo(row.getDataStatus(), "OK").startsWith("MISSING")) return 3;
        Double diff = row.getDifferencePercent();
        if (diff != null && diff <= -20.0) return 2;
        if (diff != null && diff < -10.0) return 1;
        return 0;
    }

    private String priorityForRow(PerformanceReportRowDto row) {
        int score = priorityScore(row);
        if (score >= 4) return "Critique";
        if (score >= 2) return "Élevée";
        if (score == 1) return "Moyenne";
        return "Faible";
    }

    private String reasonForRow(PerformanceReportRowDto row) {
        if (isProblemStatus(row.getSiteStatus())) return "Statut fournisseur non sain: " + row.getSiteStatus();
        if (nullTo(row.getDataStatus(), "OK").startsWith("MISSING")) return "Données de calcul manquantes: " + row.getDataStatus();
        Double diff = row.getDifferencePercent();
        if (diff != null && diff <= -20.0) return "Sous-performance importante par rapport au prévu";
        if (diff != null && diff < -10.0) return "Sous-performance modérée à surveiller";
        return "Écart négatif léger";
    }

    private boolean isProblemStatus(String status) {
        String value = nullTo(status, "").toUpperCase(Locale.ROOT);
        return value.contains("FAULT") || value.contains("ALARM") || value.contains("OFFLINE") || value.contains("DISCONNECTED");
    }

    private String globalStatus(Double differencePercent, int activeAlarms, int missing) {
        double diff = differencePercent == null ? 0.0 : differencePercent;
        if (activeAlarms > 0 || diff <= -20.0) return "CRITIQUE";
        if (missing > 0 || diff <= -10.0) return "SURVEILLANCE";
        if (diff >= 0) return "BON";
        return "NORMAL";
    }

    private String dataQualityStatus(Double reliability, Double estimatedShare, int missingSites) {
        double rel = reliability == null ? 0.0 : reliability;
        double share = estimatedShare == null ? 0.0 : estimatedShare;
        if (missingSites > 0 || rel < 70.0 || share > 30.0) return "FAIBLE";
        if (rel < 90.0 || share > 15.0) return "MOYENNE";
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
