package com.fusionsolar.monitoring.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportGlobalKpisDto {
    private Integer totalSites;
    private Integer healthySites;
    private Integer faultySites;
    private Integer disconnectedSites;
    private Integer missingDataSites;
    private Double totalInstalledPowerKw;
    private Integer activeAlarms;
    private Double globalDifferencePercent;
    private String globalStatus;
}
