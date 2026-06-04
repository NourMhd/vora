package com.fusionsolar.monitoring.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class StationRealKpiResponse {

    private Boolean success;
    private Integer failCode;
    private List<DataItem> data;

    @Data
    public static class DataItem {
        private String stationCode;
        private Map<String, Object> dataItemMap;
    }

   
}