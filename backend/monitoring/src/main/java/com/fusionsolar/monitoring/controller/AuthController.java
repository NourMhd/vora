package com.fusionsolar.monitoring.controller;

import com.fusionsolar.monitoring.dto.AppAuthResponse;
import com.fusionsolar.monitoring.dto.AppLoginRequest;
import com.fusionsolar.monitoring.service.PlatformAuthService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final PlatformAuthService platformAuthService;

    @PostMapping("/login")
    public AppAuthResponse login(@RequestBody AppLoginRequest request, HttpSession session) {
        return platformAuthService.login(request, session);
    }

    @GetMapping("/me")
    public AppAuthResponse me(HttpSession session) {
        return platformAuthService.me(session);
    }

    @PostMapping("/logout")
    public void logout(HttpSession session) {
        platformAuthService.logout(session);
    }
}