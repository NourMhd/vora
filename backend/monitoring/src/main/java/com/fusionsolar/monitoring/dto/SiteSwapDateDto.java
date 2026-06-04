package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SiteSwapDateDto {

    private String brand;

    private String siteCode;

    private String siteName;

    private String gridConnectionDate;

    private String swapDate;
}