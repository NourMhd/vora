package com.fusionsolar.monitoring.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportDataQualityDto {
    private Integer expectedProductionDays;
    private Integer capturedDays;
    private Integer estimatedDays;
    private Integer missingRealKwhSites;
    private Integer missingExpectedKwhSites;
    private Double reliabilityPercent;
    private Double estimatedSharePercent;
    private String status;
}
