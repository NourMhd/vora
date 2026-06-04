package com.fusionsolar.monitoring.client;

import com.fusionsolar.monitoring.dto.LoginRequest;
import com.fusionsolar.monitoring.dto.LoginResponse;
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory;
import com.fusionsolar.monitoring.service.huawei.HuaweiApiRateLimiter;
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory.HuaweiAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
@RequiredArgsConstructor
public class FusionSolarAuthClient {

    private final HuaweiAccountDirectory huaweiAccountDirectory;
    private final HuaweiApiRateLimiter rateLimiter;
    private final RestTemplate restTemplate = new RestTemplate();

    public LoginResult login() {
        return login(huaweiAccountDirectory.primaryAccountKey());
    }

    public LoginResult login(String accountKey) {
        HuaweiAccount account = huaweiAccountDirectory.getRequiredAccount(accountKey);
        String url = buildLoginUrl(account);

        LoginRequest requestBody = new LoginRequest(
                account.username(),
                account.password()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<LoginRequest> request = new HttpEntity<>(requestBody, headers);

        String endpoint = "/thirdData/login";
        rateLimiter.awaitSlot(account.key(), endpoint);

        try {
            ResponseEntity<LoginResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    LoginResponse.class
            );

            rateLimiter.registerSuccess(account.key(), endpoint);

            String xsrfToken = response.getHeaders().getFirst("xsrf-token");
            List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);

            return new LoginResult(response.getBody(), xsrfToken, cookies);

        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status == 407 || status == 429) {
                rateLimiter.registerRateLimit(account.key(), endpoint, 1);
            }
            throw e;
        }
    }

    public record LoginResult(
            LoginResponse body,
            String xsrfToken,
            List<String> cookies
    ) {}

    private String buildLoginUrl(HuaweiAccount account) {
        String baseUrl = account.baseUrl();

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RuntimeException("Huawei FusionSolar base-url is missing in configuration");
        }

        String normalizedBaseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;

        if (!normalizedBaseUrl.startsWith("http://") && !normalizedBaseUrl.startsWith("https://")) {
            throw new RuntimeException(
                    "FusionSolar base-url must start with http:// or https:// : " + normalizedBaseUrl
            );
        }

        return normalizedBaseUrl + "/thirdData/login";
    }
}
