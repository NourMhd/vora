package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HealthDto {
    private String status;
    private Instant checkedAt;
    private Map<String, Object> checks;
}
