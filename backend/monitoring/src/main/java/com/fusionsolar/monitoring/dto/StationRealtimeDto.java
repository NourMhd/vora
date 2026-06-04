package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StationRealtimeDto {
    private String stationCode;
    private String status;
    private Double dayPower;
    private Double monthPower;
    private Double totalPower;
}