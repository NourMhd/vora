package com.fusionsolar.monitoring.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportAlarmItemDto {
    private String type;
    private String severity;
    private String siteCode;
    private String siteName;
    private String governorate;
    private String title;
    private String message;
    private String firstDetectedAt;
    private String lastDetectedAt;
}
