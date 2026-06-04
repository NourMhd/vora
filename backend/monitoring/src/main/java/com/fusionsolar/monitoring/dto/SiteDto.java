package com.fusionsolar.monitoring.dto;

import lombok.Data;

@Data
public class SiteDto {

    private String siteCode;
    private String name;
    private String status;

    private Double installedPowerKw;
    private String commissionDate;


    private Double dayEnergyKwh;
    private Double monthEnergyKwh;
    private Double yearEnergyKwh;
    private Double totalEnergyKwh;
}