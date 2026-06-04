package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SiteSwapDatePageDto {

    private List<SiteSwapDateDto> items;

    private int page;

    private int size;

    private long totalElements;

    private int totalPages;
}