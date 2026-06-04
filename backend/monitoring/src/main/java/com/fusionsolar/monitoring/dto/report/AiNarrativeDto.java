package com.fusionsolar.monitoring.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiNarrativeDto {
    private String executiveSummary;
    private List<String> mainFindings;
    private String riskAnalysis;
    private List<String> recommendedActions;
    private String conclusion;
}
