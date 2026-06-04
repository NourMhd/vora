package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MaxProductionRowDto {
    private String brand;
    private String siteCode;
    private String siteName;
    private Double installedPowerKw;
    private Double maxProductionKwh;
    private String maxProductionDate;
    private Double kwhPerKwc;
    private String dataStatus;
}
