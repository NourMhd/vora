package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MaxProductionReportDto {
    private String startDate;
    private String endDate;
    private Integer sitesCount;
    private Integer sitesWithMonitoredValueCount;
    private List<MaxProductionRowDto> rows;
}
