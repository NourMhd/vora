package com.fusionsolar.monitoring.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class HistoryPointDto {
    private Long collectTime;
    private Double value;
    private String status;

    public HistoryPointDto(Long collectTime, Double value) {
        this.collectTime = collectTime;
        this.value = value;
        this.status = value == null ? "MISSING" : "OK";
    }

    public HistoryPointDto(Long collectTime, Double value, String status) {
        this.collectTime = collectTime;
        this.value = value;
        this.status = status;
    }
}
