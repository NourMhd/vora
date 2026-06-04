package com.fusionsolar.monitoring.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportRecommendationDto {
    private String priority;
    private String target;
    private String issue;
    private String action;
    private String justification;
}
