package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SiteDetailDto {
private String brand;
private String siteCode;
private String siteName;
private String status;
private Double installedPowerKw;
private String gridConnectionDate;
private Double dayEnergyKwh;
private Double monthEnergyKwh;
private Double yearEnergyKwh;
private Double totalEnergyKwh;
private String swapDate;
}
