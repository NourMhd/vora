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
public class AlarmSectionDto {
    private String type;
    private String title;
    private String description;
    private Integer count;
    private List<AlarmDto> alarms;
}
