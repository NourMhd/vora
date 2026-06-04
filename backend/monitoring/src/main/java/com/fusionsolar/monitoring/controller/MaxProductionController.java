package com.fusionsolar.monitoring.controller;

import com.fusionsolar.monitoring.dto.MaxProductionReportDto;
import com.fusionsolar.monitoring.service.MaxProductionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/max-production")
public class MaxProductionController {

    private final MaxProductionService maxProductionService;

    @GetMapping
    public MaxProductionReportDto getMaxProductionReport() {
        return maxProductionService.getMaxProductionReport();
    }
}
