package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.client.FusionSolarAuthClient;
import com.fusionsolar.monitoring.dto.AuthResultDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FusionSolarAuthService {

    private final FusionSolarAuthClient authClient;

    public AuthResultDto login() {

        FusionSolarAuthClient.LoginResult result = authClient.login();

        return new AuthResultDto(
                result.body() != null ? result.body().getSuccess() : null,
                result.body() != null ? result.body().getData() : null,
                result.body() != null ? result.body().getFailCode() : null,
                result.body() != null ? result.body().getMessage() : null,
                result.xsrfToken(),
                result.cookies()
        );
    }
}