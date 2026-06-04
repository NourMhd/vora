package com.fusionsolar.monitoring.dto;

import lombok.Data;

@Data
public class DeviceItemDto {
    private String id;
    private String devDn;
    private String devName;
    private String stationCode;
    private String esnCode;
    private Integer devTypeId;
    private String model;
    private String invType;
}