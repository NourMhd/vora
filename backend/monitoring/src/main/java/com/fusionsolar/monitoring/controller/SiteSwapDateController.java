package com.fusionsolar.monitoring.controller;

import com.fusionsolar.monitoring.dto.SiteSwapDateBulkUpdateRequest;
import com.fusionsolar.monitoring.dto.SiteSwapDateBulkUpdateResponse;
import com.fusionsolar.monitoring.dto.SiteSwapDatePageDto;
import com.fusionsolar.monitoring.service.SiteSwapDateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sites/swap-dates")
public class SiteSwapDateController {

    private final SiteSwapDateService siteSwapDateService;

    @GetMapping
    public SiteSwapDatePageDto getSwapDates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "all") String swapFilter
    ) {
        return siteSwapDateService.getSwapDates(page, size, search, swapFilter);
    }

    @PutMapping
    public SiteSwapDateBulkUpdateResponse updateSwapDates(
            @RequestBody SiteSwapDateBulkUpdateRequest request
    ) {
        return siteSwapDateService.updateSwapDates(request);
    }
}