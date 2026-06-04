package com.fusionsolar.monitoring.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportGovernorateAnalysisDto {
    private String governorate;
    private String zone;
    private Integer sitesCount;
    private Double installedPowerKw;
    private Double expectedKwh;
    private Double actualKwh;
    private Double estimatedKwh;
    private Double differencePercent;
    private Integer activeAlarms;
}
