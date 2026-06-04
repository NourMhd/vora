package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlarmDashboardDto {
    private String generatedAt;
    private Integer activeCount;
    private Integer historyCount;
    private List<AlarmSectionDto> activeSections;
    private List<AlarmDto> history;
}
