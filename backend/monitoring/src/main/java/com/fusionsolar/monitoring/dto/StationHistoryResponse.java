package com.fusionsolar.monitoring.dto;

import lombok.Data;

import java.util.List;

@Data
public class StationHistoryResponse {
    private Boolean success;
    private Integer failCode;
    private List<StationHistoryItemDto> data;
    private String message;
}