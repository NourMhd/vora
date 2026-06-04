package com.fusionsolar.monitoring.client.growatt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusionsolar.monitoring.config.ProvidersProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "providers.growatt", name = "enabled", havingValue = "true")
public class GrowattCloudClient {

    private static final int MAX_ATTEMPTS = 4;
    private static final long MIN_DELAY_MS = 3_000L;
    private static final long RATE_LIMIT_COOLDOWN_MS = 30_000L;

    private final ProvidersProperties providersProperties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    private final Map<String, Instant> lastCallAtByAccount = new HashMap<>();

    public JsonNode get(ProvidersProperties.HuaweiAccountProperties account, String path, Map<String, ?> params) {
        ProvidersProperties.ProviderProperties growatt = providersProperties.getGrowatt();
        if (growatt == null || !Boolean.TRUE.equals(growatt.getEnabled())) {
            throw new IllegalStateException("Growatt provider is disabled");
        }
        String baseUrl = firstNonBlank(account.getBaseUrl(), growatt.getBaseUrl());
        String token = firstNonBlank(account.getToken(), growatt.getToken());
        if (isBlank(baseUrl) || isBlank(token)) {
            throw new IllegalStateException("Growatt provider is not configured: base-url and token are required");
        }

        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                waitForSlot(account.getKey());
                URI uri = buildUri(baseUrl, normalizedPath, params);
                HttpHeaders headers = new HttpHeaders();
                headers.add("token", token);
                headers.add("Accept", "application/json");
                ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
                JsonNode root = objectMapper.readTree(response.getBody());
                throwIfGrowattError(root, normalizedPath);
                JsonNode data = root.path("data");
                return data.isMissingNode() ? root : data;
            } catch (GrowattCloudException e) {
                last = e;
                if (!e.isRetryable() || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                sleep(RATE_LIMIT_COOLDOWN_MS * attempt);
            } catch (HttpStatusCodeException e) {
                last = e;
                if (attempt == MAX_ATTEMPTS || e.getStatusCode().value() < 500 && e.getStatusCode().value() != 429) {
                    throw e;
                }
                sleep(RATE_LIMIT_COOLDOWN_MS * attempt);
            } catch (ResourceAccessException e) {
                last = e;
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                sleep(5_000L * attempt);
            } catch (Exception e) {
                throw new IllegalStateException("Growatt request failed for " + normalizedPath + ": " + e.getMessage(), e);
            }
        }
        throw last != null ? last : new IllegalStateException("Growatt request failed for " + normalizedPath);
    }

    private synchronized void waitForSlot(String accountKey) {
        String key = accountKey == null || accountKey.isBlank() ? "default" : accountKey.trim();
        Instant lastCallAt = lastCallAtByAccount.getOrDefault(key, Instant.EPOCH);
        long elapsed = Duration.between(lastCallAt, Instant.now()).toMillis();
        long wait = MIN_DELAY_MS - elapsed;
        if (wait > 0) {
            sleep(wait);
        }
        lastCallAtByAccount.put(key, Instant.now());
    }

    private URI buildUri(String baseUrl, String path, Map<String, ?> params) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(trimTrailingSlash(baseUrl) + path);
    Map<String, ?> safe = params != null ? params : new HashMap<>();
    safe.forEach((key, value) -> {
        if (value != null) {
            builder.queryParam(key, value);
        }
    });
    return builder.build(true).toUri();
}

    private void throwIfGrowattError(JsonNode root, String path) {
        int code = root.path("error_code").asInt(0);
        String msg = root.path("error_msg").asText("");
        if (code == 0 && (msg == null || msg.isBlank())) {
            return;
        }
        boolean retryable = (msg != null && msg.toLowerCase().contains("frequently")) || code == 407 || code == 429;
        throw new GrowattCloudException("Growatt error on " + path + ": code=" + code + ", message=" + msg, code, msg, retryable);
    }

    private String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first.trim() : (!isBlank(second) ? second.trim() : null);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class GrowattCloudException extends RuntimeException {
        private final int code;
        private final String businessMessage;
        private final boolean retryable;

        public GrowattCloudException(String message, int code, String businessMessage, boolean retryable) {
            super(message);
            this.code = code;
            this.businessMessage = businessMessage;
            this.retryable = retryable;
        }

        public int getCode() { return code; }
        public String getBusinessMessage() { return businessMessage; }
        public boolean isRetryable() { return retryable; }
    }
}
