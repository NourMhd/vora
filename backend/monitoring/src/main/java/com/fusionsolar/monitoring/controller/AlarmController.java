package com.fusionsolar.monitoring.controller;

import com.fusionsolar.monitoring.dto.AlarmDashboardDto;
import com.fusionsolar.monitoring.service.AlarmService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmService alarmService;

    @GetMapping
    public AlarmDashboardDto getAlarms() {
        return alarmService.getDashboard();
    }
}
