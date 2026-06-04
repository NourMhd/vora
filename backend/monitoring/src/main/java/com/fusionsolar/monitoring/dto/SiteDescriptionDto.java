package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SiteDescriptionDto {
    private String brand;
    private String siteCode;
    private String siteName;

    private String notice;
    private String comparisonPeriod;
    private String governorate;
    private Integer comparableSitesCount;
    private String bestSiteName;
    private String bestSiteCode;
    private Double currentSpecificYieldKwhPerKwp;
    private Double bestSpecificYieldKwhPerKwp;
    private Double gapPercent;
    private Double comparisonPercent;

    private String performanceIndexDate;
    private Double performanceIndexPercent;
    private Double performanceActualKwh;
    private Double performanceTheoreticalKwh;
    private String performanceStatus;

    private String swapDate;
    private String gridConnectionDate;
    private Double totalEnergyKwh;

    private String note;
    private Double orientationDeg;

    private Boolean hasPhoto;
    private String photoDataUrl;
    private String photoFilename;
    private String photoUpdatedAt;
    private java.util.List<SiteDescriptionPhotoDto> photos;
}
