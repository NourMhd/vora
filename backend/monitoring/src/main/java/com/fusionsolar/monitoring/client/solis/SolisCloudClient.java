package com.fusionsolar.monitoring.client.solis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusionsolar.monitoring.config.ProvidersProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SolisCloudClient {

    private static final String CONTENT_TYPE = "application/json";
    private static final String SIGN_CONTENT_TYPE = "application/json";

    private static final String STATION_LIST = "/v1/api/userStationList";
    private static final String STATION_DETAIL = "/v1/api/stationDetail";
    private static final String STATION_DAY_ENERGY = "/v1/api/stationDayEnergyList";
    private static final String STATION_MONTH = "/v1/api/stationMonth";
    private static final String STATION_YEAR = "/v1/api/stationYear";
    private static final String STATION_MONTH_ENERGY = "/v1/api/stationMonthEnergyList";
    private static final String STATION_YEAR_ENERGY = "/v1/api/stationYearEnergyList";
    private static final String INVERTER_LIST = "/v1/api/inverterList";

    private static final DateTimeFormatter SOLIS_DATE_FORMATTER =
            DateTimeFormatter.RFC_1123_DATE_TIME
                    .withLocale(Locale.US)
                    .withZone(ZoneOffset.UTC);

    /*
     * IMPORTANT:
     * Même si la documentation annonce parfois 10 req/sec,
     * ton log réel SolisCloud dit:
     * "too many request 1 times in 1000 milliseconds"
     *
     * Donc on protège globalement à ~1 requête/seconde.
     */
    private static final long SOLIS_GLOBAL_MIN_DELAY_MS = 1_500L;

    /*
     * Délais par endpoint.
     * stationYearEnergyList est volontairement plus lent car c'est lui qui provoque ton 429.
     */
    private static final long SOLIS_SAFE_DELAY_MS = 1_500L;

    private static final int SOLIS_MAX_ATTEMPTS = 4;
    private static final long SOLIS_429_BASE_COOLDOWN_MS = 5_000L;
    private static final long SOLIS_429_MAX_COOLDOWN_MS = 60_000L;
    private static final long SOLIS_AUTH_RETRY_DELAY_MS = 1_500L;
    private static final long SOLIS_TRANSIENT_RETRY_DELAY_MS = 3_000L;

    private static final long SOLIS_YEAR_PRE_CALL_DELAY_MS = 10_000L;
    private static final long SOLIS_YEAR_ENDPOINT_DELAY_MS = 20_000L;

    private final ProvidersProperties providersProperties;
    private final ObjectMapper objectMapper;

    private final RestTemplate restTemplate = createRestTemplate();
    private static RestTemplate createRestTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(30000);
        return new RestTemplate(factory);
    }

    private final Map<String, Long> lastEndpointCallAt = new HashMap<>();
    private final Map<String, Long> endpointBlockedUntil = new HashMap<>();

    private long lastSolisRequestAt = 0L;
    private long solisBlockedUntil = 0L;

    public JsonNode post(String path, Map<String, Object> body) {
        ProvidersProperties.ProviderProperties solis = providersProperties.getSolis();

        if (solis == null || !Boolean.TRUE.equals(solis.getEnabled())) {
            throw new IllegalStateException("Solis provider is disabled");
        }

        if (isBlank(solis.getBaseUrl()) || isBlank(solis.getKeyId()) || isBlank(solis.getKeySecret())) {
            throw new IllegalStateException(
                    "Solis provider is not configured: base-url, key-id and key-secret are required"
            );
        }

        String normalizedPath = normalizePath(path);
        String payload = toJson(body != null ? body : Map.of());
        RuntimeException lastException = null;

        for (int attempt = 1; attempt <= SOLIS_MAX_ATTEMPTS; attempt++) {
            try {
                ResponseEntity<String> response = exchangeWithRateLimit(solis, normalizedPath, payload);

                JsonNode root = parse(response.getBody());
                throwIfSolisReturnedError(root, normalizedPath);

                JsonNode data = root.path("data");
                return data.isMissingNode() ? root : data;

            } catch (SolisCloudBusinessException e) {
                lastException = e;

                if (!e.isRetryable() || attempt == SOLIS_MAX_ATTEMPTS) {
                    throw e;
                }

                long cooldownMs = retryCooldownMs(normalizedPath, attempt, e);

                log.warn(
                        "SolisCloud business error on {} attempt {}/{}. Retry in {}ms. code={} message={}",
                        normalizedPath,
                        attempt,
                        SOLIS_MAX_ATTEMPTS,
                        cooldownMs,
                        e.getCode(),
                        e.getBusinessMessage()
                );

                sleep(cooldownMs);
            }
        }

        throw lastException != null
                ? lastException
                : new IllegalStateException("SolisCloud request failed for " + normalizedPath);
    }

    private synchronized ResponseEntity<String> exchangeWithRateLimit(
            ProvidersProperties.ProviderProperties solis,
            String normalizedPath,
            String payload
    ) {
        RuntimeException lastException = null;
        URI uri = URI.create(trimTrailingSlash(solis.getBaseUrl()) + normalizedPath);

        for (int attempt = 1; attempt <= SOLIS_MAX_ATTEMPTS; attempt++) {
            waitForSolisSlot(normalizedPath);

            /*
             * Les headers Solis sont reconstruits à chaque tentative.
             * Donc si Solis refuse la signature/date, la tentative suivante est déjà une reconnexion logique:
             * nouvelle date RFC1123 + nouvelle signature HMAC + nouveau Authorization header.
             */
            HttpHeaders headers = buildHeaders(solis, normalizedPath, payload);

            try {
                return restTemplate.exchange(
                        uri,
                        HttpMethod.POST,
                        new HttpEntity<>(payload, headers),
                        String.class
                );

            } catch (HttpStatusCodeException e) {
                lastException = e;

                boolean rateLimit = isHttpRateLimit(e);
                boolean authOrTransient = isHttpAuthError(e) || isHttpTransientError(e);

                if (!rateLimit && !authOrTransient) {
                    throw e;
                }

                if (attempt == SOLIS_MAX_ATTEMPTS) {
                    log.warn(
                            "SolisCloud HTTP error on {} after {} attempts. status={} body={}",
                            normalizedPath,
                            SOLIS_MAX_ATTEMPTS,
                            e.getStatusCode().value(),
                            oneLine(e.getResponseBodyAsString())
                    );
                    throw e;
                }

                long cooldownMs = rateLimit
                        ? registerTooManyRequests(normalizedPath, attempt, e)
                        : httpRetryCooldownMs(attempt, e);

                log.warn(
                        "SolisCloud HTTP error on {} attempt {}/{}. status={} cooldown={}ms body={}",
                        normalizedPath,
                        attempt,
                        SOLIS_MAX_ATTEMPTS,
                        e.getStatusCode().value(),
                        cooldownMs,
                        oneLine(e.getResponseBodyAsString())
                );

                sleep(cooldownMs);

            } catch (ResourceAccessException e) {
                lastException = e;

                if (attempt == SOLIS_MAX_ATTEMPTS) {
                    throw e;
                }

                long cooldownMs = SOLIS_TRANSIENT_RETRY_DELAY_MS * attempt;
                log.warn(
                        "SolisCloud network error on {} attempt {}/{}. Retry in {}ms. Cause={}",
                        normalizedPath,
                        attempt,
                        SOLIS_MAX_ATTEMPTS,
                        cooldownMs,
                        e.getMessage()
                );
                sleep(cooldownMs);
            }
        }

        throw lastException != null
                ? lastException
                : new IllegalStateException("SolisCloud request failed for " + normalizedPath);
    }

    private HttpHeaders buildHeaders(
            ProvidersProperties.ProviderProperties solis,
            String normalizedPath,
            String payload
    ) {
        String contentMd5 = contentMd5(payload);
        String date = SOLIS_DATE_FORMATTER.format(Instant.now());
        String signature = signature(solis.getKeySecret(), normalizedPath, contentMd5, date);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE);
        headers.add(HttpHeaders.DATE, date);
        headers.add("Content-MD5", contentMd5);
        headers.add(HttpHeaders.AUTHORIZATION, "API " + solis.getKeyId() + ":" + signature);

        return headers;
    }

    private void waitForSolisSlot(String normalizedPath) {
        while (true) {
            long now = System.currentTimeMillis();

            long globalRateWaitMs = waitRemaining(
                    now,
                    lastSolisRequestAt,
                    globalDelayMs(normalizedPath)
            );

            long endpointLastCallAt = lastEndpointCallAt.getOrDefault(normalizedPath, 0L);
            long endpointRateWaitMs = waitRemaining(
                    now,
                    endpointLastCallAt,
                    endpointDelayMs(normalizedPath)
            );

            long globalCooldownWaitMs = Math.max(0L, solisBlockedUntil - now);

            long endpointCooldownUntil = endpointBlockedUntil.getOrDefault(normalizedPath, 0L);
            long endpointCooldownWaitMs = Math.max(0L, endpointCooldownUntil - now);

            long waitMs = max(
                    globalRateWaitMs,
                    endpointRateWaitMs,
                    globalCooldownWaitMs,
                    endpointCooldownWaitMs
            );

            if (waitMs <= 0L) {
                long reservedAt = System.currentTimeMillis();
                lastSolisRequestAt = reservedAt;
                lastEndpointCallAt.put(normalizedPath, reservedAt);
                return;
            }

            sleep(waitMs);
        }
    }

    private long globalDelayMs(String path) {
        if (STATION_YEAR.equals(path) || STATION_YEAR_ENERGY.equals(path)) {
            return SOLIS_YEAR_PRE_CALL_DELAY_MS;
        }

        return SOLIS_GLOBAL_MIN_DELAY_MS;
    }

    private long registerTooManyRequests(
            String normalizedPath,
            int attempt,
            HttpStatusCodeException e
    ) {
        long retryAfterMs = retryAfterMs(e);
        long computedCooldownMs = SOLIS_429_BASE_COOLDOWN_MS * attempt;
        long cooldownMs = Math.max(retryAfterMs, computedCooldownMs);
        cooldownMs = Math.min(cooldownMs, SOLIS_429_MAX_COOLDOWN_MS);

        blockSolisEndpoint(normalizedPath, cooldownMs);
        return cooldownMs;
    }

    private long registerBusinessTooManyRequests(String normalizedPath, int attempt) {
        long computedCooldownMs = SOLIS_429_BASE_COOLDOWN_MS * attempt;
        long cooldownMs = Math.min(computedCooldownMs, SOLIS_429_MAX_COOLDOWN_MS);

        blockSolisEndpoint(normalizedPath, cooldownMs);
        return cooldownMs;
    }

    private void blockSolisEndpoint(String normalizedPath, long cooldownMs) {
        long blockedUntil = System.currentTimeMillis() + cooldownMs;

        solisBlockedUntil = Math.max(solisBlockedUntil, blockedUntil);

        long currentEndpointBlockedUntil = endpointBlockedUntil.getOrDefault(normalizedPath, 0L);
        endpointBlockedUntil.put(normalizedPath, Math.max(currentEndpointBlockedUntil, blockedUntil));
    }

    private long endpointDelayMs(String path) {
        return switch (path) {
            case STATION_YEAR, STATION_YEAR_ENERGY -> SOLIS_YEAR_ENDPOINT_DELAY_MS;
            case STATION_LIST,
                 STATION_DETAIL,
                 STATION_DAY_ENERGY,
                 STATION_MONTH,
                 STATION_MONTH_ENERGY,
                 INVERTER_LIST -> SOLIS_SAFE_DELAY_MS;
            default -> SOLIS_SAFE_DELAY_MS;
        };
    }

    private long retryCooldownMs(String normalizedPath, int attempt, SolisCloudBusinessException e) {
        if (e.isRateLimit()) {
            return registerBusinessTooManyRequests(normalizedPath, attempt);
        }

        if (e.isAuthError()) {
            return SOLIS_AUTH_RETRY_DELAY_MS * attempt;
        }

        return SOLIS_TRANSIENT_RETRY_DELAY_MS * attempt;
    }

    private long httpRetryCooldownMs(int attempt, HttpStatusCodeException e) {
        long retryAfterMs = retryAfterMs(e);
        long fallbackMs = isHttpAuthError(e)
                ? SOLIS_AUTH_RETRY_DELAY_MS * attempt
                : SOLIS_TRANSIENT_RETRY_DELAY_MS * attempt;

        return Math.max(retryAfterMs, fallbackMs);
    }

    private boolean isHttpRateLimit(HttpStatusCodeException e) {
        return e.getStatusCode().value() == 429;
    }

    private boolean isHttpAuthError(HttpStatusCodeException e) {
        int status = e.getStatusCode().value();
        return status == 401 || status == 403;
    }

    private boolean isHttpTransientError(HttpStatusCodeException e) {
        int status = e.getStatusCode().value();
        return status == 408 || status == 425 || status == 502 || status == 503 || status == 504 || status >= 500;
    }

    private long waitRemaining(long now, long lastCallAt, long minDelayMs) {
        if (lastCallAt <= 0L) {
            return 0L;
        }

        long elapsed = now - lastCallAt;
        long remaining = minDelayMs - elapsed;

        return Math.max(0L, remaining);
    }

    private long retryAfterMs(HttpStatusCodeException e) {
        HttpHeaders headers = e.getResponseHeaders();

        if (headers == null) {
            return 0L;
        }

        String retryAfter = headers.getFirst("Retry-After");

        if (isBlank(retryAfter)) {
            return 0L;
        }

        try {
            return Math.max(0L, Long.parseLong(retryAfter.trim()) * 1_000L);
        } catch (NumberFormatException ignored) {
            // Retry-After peut aussi être une date HTTP.
        }

        try {
            Instant retryAt = DateTimeFormatter.RFC_1123_DATE_TIME
                    .withLocale(Locale.US)
                    .parse(retryAfter, Instant::from);

            return Math.max(0L, retryAt.toEpochMilli() - System.currentTimeMillis());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long max(long... values) {
        long result = 0L;

        for (long value : values) {
            result = Math.max(result, value);
        }

        return result;
    }

    private void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while throttling SolisCloud requests", e);
        }
    }

    private String signature(String keySecret, String path, String contentMd5, String date) {
        try {
            String signString = "POST\n"
                    + contentMd5 + "\n"
                    + SIGN_CONTENT_TYPE + "\n"
                    + date + "\n"
                    + path;

            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(keySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));

            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(signString.getBytes(StandardCharsets.UTF_8)));

        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign SolisCloud request", e);
        }
    }

    private String contentMd5(String payload) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(payload.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(digest);

        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute SolisCloud Content-MD5", e);
        }
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize SolisCloud request body", e);
        }
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body != null ? body : "{}");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to parse SolisCloud response", e);
        }
    }

    private void throwIfSolisReturnedError(JsonNode root, String path) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new SolisCloudBusinessException(
                    "SolisCloud empty response for " + path,
                    null,
                    "empty response",
                    false,
                    false,
                    true
            );
        }

        JsonNode success = root.get("success");
        String code = code(root);
        String message = message(root);

        if (success != null && success.isBoolean() && !success.asBoolean()) {
            throw solisBusinessException(path, code, message);
        }

        if (!isBlank(code)) {
            boolean ok = "0".equals(code)
                    || "200".equals(code)
                    || "success".equalsIgnoreCase(code);

            if (!ok && root.path("data").isMissingNode()) {
                throw solisBusinessException(path, code, message);
            }
        }
    }

    private SolisCloudBusinessException solisBusinessException(String path, String code, String message) {
        boolean authError = isSolisAuthBusinessError(code, message);
        boolean rateLimit = isSolisRateLimitBusinessError(code, message);
        boolean transientError = isSolisTransientBusinessError(code, message);

        return new SolisCloudBusinessException(
                "SolisCloud request failed for " + path + " with code " + code + ": " + message,
                code,
                message,
                authError,
                rateLimit,
                transientError
        );
    }

    private boolean isSolisAuthBusinessError(String code, String message) {
        String normalized = normalizeBusinessText(code, message);

        return "401".equals(code)
                || "403".equals(code)
                || normalized.contains("UNAUTHORIZED")
                || normalized.contains("FORBIDDEN")
                || normalized.contains("SIGN")
                || normalized.contains("SIGNATURE")
                || normalized.contains("AUTH")
                || normalized.contains("TOKEN")
                || normalized.contains("KEY")
                || normalized.contains("DATE")
                || normalized.contains("TIMESTAMP");
    }

    private boolean isSolisRateLimitBusinessError(String code, String message) {
        String normalized = normalizeBusinessText(code, message);

        return "429".equals(code)
                || normalized.contains("TOO MANY")
                || normalized.contains("RATE")
                || normalized.contains("LIMIT")
                || normalized.contains("FREQUENCY")
                || normalized.contains("HIGH FREQUENCY")
                || normalized.contains("HIGH_FREQUENCY")
                || normalized.contains("FREQUENT");
    }

    private boolean isSolisTransientBusinessError(String code, String message) {
        String normalized = normalizeBusinessText(code, message);

        return "408".equals(code)
                || "500".equals(code)
                || "502".equals(code)
                || "503".equals(code)
                || "504".equals(code)
                || normalized.contains("TIMEOUT")
                || normalized.contains("TEMPORARY")
                || normalized.contains("BUSY")
                || normalized.contains("SERVER ERROR");
    }

    private String normalizeBusinessText(String code, String message) {
        return ((code != null ? code : "") + " " + (message != null ? message : ""))
                .toUpperCase(Locale.ROOT);
    }

    private String code(JsonNode root) {
        JsonNode code = firstExisting(root, "code", "resultCode", "errorCode");
        return code != null && !code.isMissingNode() && !code.isNull()
                ? code.asText()
                : null;
    }

    private String message(JsonNode root) {
        JsonNode msg = firstExisting(root, "message", "msg", "resultMsg", "errorMsg");

        return msg != null && !msg.isMissingNode() && !msg.isNull()
                ? msg.asText()
                : "unknown error";
    }

    private JsonNode firstExisting(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);

            if (value != null) {
                return value;
            }
        }

        return null;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("SolisCloud path is required");
        }

        return path.startsWith("/") ? path : "/" + path;
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value.trim();

        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String oneLine(String value) {
        if (value == null) {
            return "";
        }

        String cleaned = value
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("\t", " ")
                .trim();

        if (cleaned.length() > 500) {
            return cleaned.substring(0, 500) + "...";
        }

        return cleaned;
    }

    private static class SolisCloudBusinessException extends RuntimeException {
        private final String code;
        private final String businessMessage;
        private final boolean authError;
        private final boolean rateLimit;
        private final boolean transientError;

        SolisCloudBusinessException(
                String message,
                String code,
                String businessMessage,
                boolean authError,
                boolean rateLimit,
                boolean transientError
        ) {
            super(message);
            this.code = code;
            this.businessMessage = businessMessage;
            this.authError = authError;
            this.rateLimit = rateLimit;
            this.transientError = transientError;
        }

        String getCode() {
            return code;
        }

        String getBusinessMessage() {
            return businessMessage;
        }

        boolean isAuthError() {
            return authError;
        }

        boolean isRateLimit() {
            return rateLimit;
        }

        boolean isRetryable() {
            return authError || rateLimit || transientError;
        }
    }
}
