package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DailyComparisonSiteDto {
    private String brand;
    private String siteCode;
    private String siteName;
    private String swapDate;
    private String gridConnectionDate;
    private Double installedPowerKw;
    private String governorate;
    private String zone;
}
