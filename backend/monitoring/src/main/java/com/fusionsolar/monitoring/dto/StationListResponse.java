package com.fusionsolar.monitoring.dto;

import lombok.Data;
import java.util.List;

@Data
public class StationListResponse {
    private Boolean success;
    private Integer failCode;
    private DataContainer data;

    @Data
    public static class DataContainer {
        private List<StationItemDto> list;
        private Integer total;
        private Integer totalCount;
    }
}