package com.fusionsolar.monitoring.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiReportPreviewDto {
    private ReportPeriodDto period;
    private String scope;
    private String scopeValue;
    private String reportType;
    private String generatedAt;
    private ReportGlobalKpisDto globalKpis;
    private ReportProductionSummaryDto productionSummary;
    private ReportDataQualityDto dataQuality;
    private List<ReportZoneAnalysisDto> zoneAnalysis;
    private List<ReportGovernorateAnalysisDto> governorateAnalysis;
    private List<ReportSiteRankingDto> bestSites;
    private List<ReportSiteRankingDto> worstSites;
    private ReportAlarmSummaryDto alarms;
    private List<ReportRecommendationDto> recommendations;
    private List<ReportTechnicalRowDto> technicalAppendix;
    private AiNarrativeDto aiNarrative;
    private Boolean aiEnabled;
    private String aiStatus;
}
