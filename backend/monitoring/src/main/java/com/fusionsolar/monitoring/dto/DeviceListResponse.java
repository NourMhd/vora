package com.fusionsolar.monitoring.dto;

import lombok.Data;
import java.util.List;

@Data
public class DeviceListResponse {
    private Boolean success;
    private Integer failCode;
    private List<DeviceItemDto> data;
}