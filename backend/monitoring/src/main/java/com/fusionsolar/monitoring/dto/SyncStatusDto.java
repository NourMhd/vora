package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncStatusDto {
    private String brand;
    private String type;
    private String status;
    private String message;
    private Instant startedAt;
    private Instant finishedAt;
    private Map<String, Object> stats;
}
