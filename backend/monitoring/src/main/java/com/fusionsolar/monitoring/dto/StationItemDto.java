package com.fusionsolar.monitoring.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class StationItemDto {
    private String plantCode;
    private String plantName;
    private Double capacity;
    private String gridConnectionDate;

    private Double dayEnergyKwh;


  
    private String swapDate;
}