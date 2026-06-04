package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DashboardSummaryDto {
    private Integer totalSites;
    private Double totalInstalledPowerKw;
    
    private Double totalDayEnergyKwh;
    private Double totalMonthEnergyKwh;
    private Double totalYearEnergyKwh;
    private Double totalEnergyKwh;
    private Integer healthySites;
    private Integer faultySites;
    private Integer disconnectedSites;
}