package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DailyComparisonReportDto {
    private List<DailyComparisonSiteDto> sites;
    private String startDate;
    private String endDate;
    private List<DailyComparisonRowDto> historicalRows;
    private List<DailyComparisonRowDto> estimatedRows;
    private DailyComparisonRowDto historicalTotals;
    private DailyComparisonRowDto estimatedTotals;
}
