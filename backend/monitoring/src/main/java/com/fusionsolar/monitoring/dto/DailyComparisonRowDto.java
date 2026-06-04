package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DailyComparisonRowDto {
    private String date;
    private String label;
    private List<DailyComparisonCellDto> cells;
}
