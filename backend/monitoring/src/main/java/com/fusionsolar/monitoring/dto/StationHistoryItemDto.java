package com.fusionsolar.monitoring.dto;

import lombok.Data;

import java.util.Map;

@Data
public class StationHistoryItemDto {
    private String stationCode;
    private Long collectTime;
    private Map<String, Object> dataItemMap;
}