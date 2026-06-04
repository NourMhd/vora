package com.fusionsolar.monitoring.controller;

import com.fusionsolar.monitoring.dto.PerformanceReportRowDto;
import com.fusionsolar.monitoring.service.PerformanceReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/performance")
public class PerformanceReportController {

    private final PerformanceReportService performanceReportService;

    @GetMapping
    public List<PerformanceReportRowDto> getPerformanceReport(
            @RequestParam(defaultValue = "MONTH") String period,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer decade,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate
    ) {
        return performanceReportService.getReport(period, year, month, decade, startDate, endDate);
    }
}
