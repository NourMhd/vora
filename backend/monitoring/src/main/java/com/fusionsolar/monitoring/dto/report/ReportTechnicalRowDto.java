package com.fusionsolar.monitoring.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportTechnicalRowDto {
    private String brand;
    private String siteCode;
    private String siteName;
    private String status;
    private String zone;
    private String governorate;
    private String plantType;
    private Double installedPowerKw;
    private String swapDate;
    private String gridConnectionDate;
    private Integer filterDaysCount;
    private Integer productionDaysCount;
    private Integer capturedDaysCount;
    private Integer estimatedDaysCount;
    private Double expectedKwh;
    private Double capturedKwh;
    private Double estimatedKwh;
    private Double displayedKwh;
    private Double differencePercent;
    private String bonusMalus;
    private String dataStatus;
}
