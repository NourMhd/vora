package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CachedSiteDevicesDto {
    private String stationCode;
    private long cachedAt;
    private SiteDevicesResponseDto data;
}