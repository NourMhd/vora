package com.fusionsolar.monitoring.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class TestAuthController {

    @GetMapping("/test/login")
    public Map<String, Object> login() {
        return Map.of(
                "disabled", true,
                "message", "Direct Huawei login test is disabled to avoid API account lock/rate-limit."
        );
    }
}
