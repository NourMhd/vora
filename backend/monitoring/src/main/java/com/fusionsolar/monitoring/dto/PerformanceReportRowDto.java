package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PerformanceReportRowDto {
    private String brand;
    private String siteCode;
    private String siteName;
    private String siteStatus;
    private Double installedPowerKw;

    /** Ancien champ conservé pour compatibilité front: nombre de jours du filtre. */
    private Integer daysCount;
    private Integer filterDaysCount;
    private Integer productionDaysCount;
    private Integer capturedDaysCount;
    private Integer estimatedDaysCount;

    private String filterStartDate;
    private String filterEndDate;
    private String swapDate;
    private String gridConnectionDate;
    private String productionStartDate;
    private String productionEndDate;

    private String plantType;
    private String zone;
    private String governorate;
    private Double expectedKwh;
    private Double capturedKwh;
    private Double estimatedMissingKwh;
    private Double actualKwh;
    private Double differenceKwh;
    private Double differencePercent;
    private String bonusMalus;
    private String dataStatus;
}
