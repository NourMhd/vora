package com.fusionsolar.monitoring.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportProductionSummaryDto {
    private Double expectedKwh;
    private Double capturedKwh;
    private Double estimatedKwh;
    private Double displayedKwh;
    private Double differenceKwh;
    private Double differencePercent;
    private Integer bonusSites;
    private Integer malusSites;
}
