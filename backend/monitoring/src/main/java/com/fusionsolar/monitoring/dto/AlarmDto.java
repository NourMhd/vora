package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlarmDto {
    private String id;
    private String alarmKey;
    private String type;
    private Boolean active;

    private String brand;
    private String siteCode;
    private String siteName;
    private String siteStatus;
    private String governorate;

    private String title;
    private String message;
    private String severity;

    private Double metricValue;
    private Double thresholdValue;
    private String unit;

    private String bestSiteName;
    private String bestSiteCode;
    private Double bestValue;

    private String monitoredDate;
    private String lastMonitoringAt;
    private String firstDetectedAt;
    private String lastDetectedAt;
    private String clearedAt;
}
