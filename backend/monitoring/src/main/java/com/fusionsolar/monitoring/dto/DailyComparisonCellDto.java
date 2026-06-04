package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DailyComparisonCellDto {
    private String siteCode;
    private Double value;
    private String displayValue;
    private Boolean estimated;
    private String status;
}
