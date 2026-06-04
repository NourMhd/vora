package com.fusionsolar.monitoring.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportSiteRankingDto {
    private String brand;
    private String siteCode;
    private String siteName;
    private String status;
    private String zone;
    private String governorate;
    private Double installedPowerKw;
    private Double expectedKwh;
    private Double actualKwh;
    private Double estimatedKwh;
    private Double differencePercent;
    private String bonusMalus;
    private Integer capturedDays;
    private Integer estimatedDays;
    private String priority;
    private String reason;
}
