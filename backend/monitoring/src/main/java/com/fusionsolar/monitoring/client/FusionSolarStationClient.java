package com.fusionsolar.monitoring.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusionsolar.monitoring.dto.DeviceListRequest;
import com.fusionsolar.monitoring.dto.DeviceListResponse;
import com.fusionsolar.monitoring.dto.DeviceRealKpiRequest;
import com.fusionsolar.monitoring.dto.DeviceRealKpiResponse;
import com.fusionsolar.monitoring.dto.HuaweiApiEnvelope;
import com.fusionsolar.monitoring.dto.HuaweiSession;
import com.fusionsolar.monitoring.dto.StationHistoryRequest;
import com.fusionsolar.monitoring.dto.StationHistoryResponse;
import com.fusionsolar.monitoring.dto.StationListRequest;
import com.fusionsolar.monitoring.dto.StationListResponse;
import com.fusionsolar.monitoring.dto.StationRealKpiRequest;
import com.fusionsolar.monitoring.dto.StationRealKpiResponse;
import com.fusionsolar.monitoring.exception.FusionSolarApiException;
import com.fusionsolar.monitoring.service.HuaweiSessionManager;
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory;
import com.fusionsolar.monitoring.service.huawei.HuaweiApiRateLimiter;
import com.fusionsolar.monitoring.service.huawei.HuaweiApiDeferredException;
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory.HuaweiAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class FusionSolarStationClient {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_DEVICE_LIST_STATION_CODES = 100;
    private static final int MAX_STATION_REAL_KPI_CODES = 100;
    private static final int MAX_DEVICE_REAL_KPI_IDS = 100;


    private static final long NETWORK_RETRY_DELAY_MS = 3_000L;
    private static final long AUTH_RETRY_MIN_DELAY_MS = 60_000L;

    private final HuaweiAccountDirectory huaweiAccountDirectory;
    private final HuaweiSessionManager sessionManager;
    private final HuaweiApiRateLimiter rateLimiter;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(30000);
        return new RestTemplate(factory);
    }


    @org.springframework.beans.factory.annotation.Value("${app.history-batch-backfill.huawei-report-daily-call-limit:22}")
    private int reportDailyLimit;

    private final ThreadLocal<Boolean> backgroundCollection = ThreadLocal.withInitial(() -> false);

    public void prioritizeStateCollection(String accountKey) {
        rateLimiter.prioritizeStates(accountKey);
    }

    public StationListResponse getStationsPageForSync(String accountKey, int pageNo) {
        backgroundCollection.set(true);
        try { return getStationsPage(accountKey, pageNo); }
        finally { backgroundCollection.remove(); }
    }

    public StationRealKpiResponse getStationRealKpiForSync(String accountKey, List<String> codes) {
        backgroundCollection.set(true);
        try { return getStationRealKpi(accountKey, codes); }
        finally { backgroundCollection.remove(); }
    }

    public StationListResponse getStationsPage(int pageNo) {
        return getStationsPage(huaweiAccountDirectory.primaryAccountKey(), pageNo);
    }

    public StationListResponse getStationsPage(String accountKey, int pageNo) {
        String endpoint = "/thirdData/stations";
        StationListRequest body = new StationListRequest(pageNo, PAGE_SIZE);
        return postWithSessionRetry(accountKey, endpoint, body, StationListResponse.class);
    }

    public StationRealKpiResponse getStationRealKpi(List<String> stationCodes) {
        return getStationRealKpi(huaweiAccountDirectory.primaryAccountKey(), stationCodes);
    }

    public StationRealKpiResponse getStationRealKpi(String accountKey, List<String> stationCodes) {
        List<String> sanitized = sanitizeList(stationCodes);

        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("stationCodes must not be empty");
        }

        if (sanitized.size() > MAX_STATION_REAL_KPI_CODES) {
            throw new IllegalArgumentException(
                    "FusionSolar getStationRealKpi accepts at most "
                            + MAX_STATION_REAL_KPI_CODES
                            + " station codes per request"
            );
        }

        return getStationRealKpi(accountKey, String.join(",", sanitized));
    }

    public StationRealKpiResponse getStationRealKpi(String stationCodes) {
        return getStationRealKpi(huaweiAccountDirectory.primaryAccountKey(), stationCodes);
    }

    public StationRealKpiResponse getStationRealKpi(String accountKey, String stationCodes) {
        validateCsvLimit(stationCodes, MAX_STATION_REAL_KPI_CODES, "stationCodes");

        String endpoint = "/thirdData/getStationRealKpi";
        StationRealKpiRequest body = new StationRealKpiRequest(stationCodes);
        return postWithSessionRetry(accountKey, endpoint, body, StationRealKpiResponse.class);
    }

    public DeviceListResponse getDevicesByStationCodes(List<String> stationCodes) {
        return getDevicesByStationCodes(huaweiAccountDirectory.primaryAccountKey(), stationCodes);
    }

    public DeviceListResponse getDevicesByStationCodes(String accountKey, List<String> stationCodes) {
        List<String> sanitized = sanitizeList(stationCodes);

        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("stationCodes must not be empty");
        }

        return getDevicesByStationCodes(accountKey, String.join(",", sanitized));
    }

    public DeviceListResponse getDevicesByStationCodes(String stationCodes) {
        return getDevicesByStationCodes(huaweiAccountDirectory.primaryAccountKey(), stationCodes);
    }

    public DeviceListResponse getDevicesByStationCodes(String accountKey, String stationCodes) {
        if (stationCodes == null || stationCodes.isBlank()) {
            throw new IllegalArgumentException("stationCodes must not be empty");
        }

        validateCsvLimit(stationCodes, MAX_DEVICE_LIST_STATION_CODES, "stationCodes");

        String endpoint = "/thirdData/getDevList";
        DeviceListRequest body = new DeviceListRequest(stationCodes);
        return postWithSessionRetry(accountKey, endpoint, body, DeviceListResponse.class);
    }

    public DeviceRealKpiResponse getDeviceRealKpi(List<String> devIds, Integer devTypeId) {
        return getDeviceRealKpi(huaweiAccountDirectory.primaryAccountKey(), devIds, devTypeId);
    }

    public DeviceRealKpiResponse getDeviceRealKpi(String accountKey, List<String> devIds, Integer devTypeId) {
        List<String> sanitized = sanitizeList(devIds);

        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("devIds must not be empty");
        }

        if (sanitized.size() > MAX_DEVICE_REAL_KPI_IDS) {
            throw new IllegalArgumentException(
                    "FusionSolar getDeviceRealKpi accepts at most "
                            + MAX_DEVICE_REAL_KPI_IDS
                            + " device ids per request"
            );
        }

        return getDeviceRealKpi(accountKey, String.join(",", sanitized), devTypeId);
    }

    public DeviceRealKpiResponse getDeviceRealKpi(String devIds, Integer devTypeId) {
        return getDeviceRealKpi(huaweiAccountDirectory.primaryAccountKey(), devIds, devTypeId);
    }

    public DeviceRealKpiResponse getDeviceRealKpi(String accountKey, String devIds, Integer devTypeId) {
        validateCsvLimit(devIds, MAX_DEVICE_REAL_KPI_IDS, "devIds");

        if (devTypeId == null) {
            throw new IllegalArgumentException("devTypeId must not be null");
        }

        String endpoint = "/thirdData/getDevRealKpi";
        DeviceRealKpiRequest body = new DeviceRealKpiRequest(devIds, devTypeId);
        return postWithSessionRetry(accountKey, endpoint, body, DeviceRealKpiResponse.class);
    }

    public StationHistoryResponse getStationHourlyHistory(String stationCodes, Long collectTime) {
        return getStationHourlyHistory(huaweiAccountDirectory.primaryAccountKey(), stationCodes, collectTime);
    }

    public StationHistoryResponse getStationHourlyHistory(String accountKey, String stationCodes, Long collectTime) {
        String endpoint = "/thirdData/getKpiStationHour";
        StationHistoryRequest body = new StationHistoryRequest(stationCodes, collectTime);
        return postWithSessionRetry(accountKey, endpoint, body, StationHistoryResponse.class);
    }

    public StationHistoryResponse getStationDailyHistory(String stationCodes, Long collectTime) {
        return getStationDailyHistory(huaweiAccountDirectory.primaryAccountKey(), stationCodes, collectTime);
    }

    public StationHistoryResponse getStationDailyHistory(String accountKey, String stationCodes, Long collectTime) {
        String endpoint = "/thirdData/getKpiStationDay";
        StationHistoryRequest body = new StationHistoryRequest(stationCodes, collectTime);
        return postWithSessionRetry(accountKey, endpoint, body, StationHistoryResponse.class);
    }

    public StationHistoryResponse getStationMonthlyHistory(String stationCodes, Long collectTime) {
        return getStationMonthlyHistory(huaweiAccountDirectory.primaryAccountKey(), stationCodes, collectTime);
    }

    public StationHistoryResponse getStationMonthlyHistory(String accountKey, String stationCodes, Long collectTime) {
        String endpoint = "/thirdData/getKpiStationMonth";
        StationHistoryRequest body = new StationHistoryRequest(stationCodes, collectTime);
        return postWithSessionRetry(accountKey, endpoint, body, StationHistoryResponse.class);
    }

    public StationHistoryResponse getStationYearlyHistory(String stationCodes, Long collectTime) {
        return getStationYearlyHistory(huaweiAccountDirectory.primaryAccountKey(), stationCodes, collectTime);
    }

    public StationHistoryResponse getStationYearlyHistory(String accountKey, String stationCodes, Long collectTime) {
        String endpoint = "/thirdData/getKpiStationYear";
        StationHistoryRequest body = new StationHistoryRequest(stationCodes, collectTime);
        return postWithSessionRetry(accountKey, endpoint, body, StationHistoryResponse.class);
    }

    private <T, R> T postWithSessionRetry(String accountKey, String endpoint, R body, Class<T> responseType) {
        String effectiveAccountKey = effectiveAccountKey(accountKey);
        String url = buildAbsoluteUrl(effectiveAccountKey, endpoint);
        RuntimeException lastRuntimeException = null;
        HuaweiSession session = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                session = sessionManager.getSession(effectiveAccountKey);
                reserveProviderSlot(effectiveAccountKey, endpoint);

                if (isHistoryEndpoint(endpoint) && !inventoryBudget.tryConsumeDaily(
                        "HUAWEI", rateLimiter.budgetScope(effectiveAccountKey), endpoint,
                        reportDailyLimit, java.time.ZoneId.of("Africa/Tunis"))) {
                    throw new HuaweiApiDeferredException("Huawei daily report budget exhausted", 3600000L);
                }
                log.info("Calling FusionSolar URL: {} account={}", url, effectiveAccountKey);
                T response = doPost(url, endpoint, body, responseType, session);
                rateLimiter.registerSuccess(effectiveAccountKey, endpoint);
                return response;

            } catch (FusionSolarApiException e) {
                log.warn(
                        "FusionSolar business error on {} account={} failCode={} authError={} rateLimit={} body={}",
                        endpoint,
                        effectiveAccountKey,
                        e.getFailCode(),
                        e.isAuthError(),
                        e.isRateLimit(),
                        truncate(e.getRawBody())
                );

                if (e.isAuthError()) {
                    sessionManager.invalidateSession(effectiveAccountKey, session);
                    throw new HuaweiApiDeferredException(
                            "FusionSolar session refresh scheduled after auth error on "
                                    + endpoint + " account=" + effectiveAccountKey,
                            AUTH_RETRY_MIN_DELAY_MS,
                            e
                    );
                }

                if (e.isRateLimit()) {
                    lastRuntimeException = new RuntimeException(
                            "FusionSolar rate limit reached on " + endpoint + " account=" + effectiveAccountKey,
                            e
                    );

                    rateLimiter.registerRateLimit(effectiveAccountKey, endpoint, attempt);
                    throw lastRuntimeException;
                }

                throw e;

            } catch (HttpStatusCodeException e) {
                log.warn(
                        "FusionSolar HTTP error on {} account={} status={} body={}",
                        endpoint,
                        effectiveAccountKey,
                        e.getStatusCode().value(),
                        safeErrorBody(e)
                );

                if (isAuthError(e)) {
                    sessionManager.invalidateSession(effectiveAccountKey, session);
                    throw new HuaweiApiDeferredException(
                            "FusionSolar session refresh scheduled after HTTP auth error on "
                                    + endpoint + " account=" + effectiveAccountKey,
                            AUTH_RETRY_MIN_DELAY_MS,
                            e
                    );
                }

                if (isRateLimitError(e)) {
                    lastRuntimeException = new RuntimeException(
                            "FusionSolar rate limit reached (" + e.getStatusCode().value() + ") on " + endpoint
                                    + " account=" + effectiveAccountKey,
                            e
                    );

                    rateLimiter.registerRateLimit(effectiveAccountKey, endpoint, attempt);
                    throw lastRuntimeException;
                }

                throw new RuntimeException(
                        "FusionSolar HTTP error " + e.getStatusCode().value() + " on " + endpoint,
                        e
                );

            } catch (HuaweiApiDeferredException e) {
                log.info(
                        "FusionSolar call deferred without blocking endpoint={} account={} retryAfterMs={}",
                        endpoint,
                        effectiveAccountKey,
                        e.getRetryAfterMs()
                );
                throw e;

            } catch (ResourceAccessException e) {
                log.warn("FusionSolar network error on {} account={} attempt={}: {}", endpoint, effectiveAccountKey, attempt, e.getMessage());

                lastRuntimeException = new RuntimeException(
                        "FusionSolar is unreachable on " + endpoint,
                        e
                );

                if (attempt == MAX_ATTEMPTS) {
                    throw lastRuntimeException;
                }

                sleepQuietly(NETWORK_RETRY_DELAY_MS * attempt);

            } catch (Exception e) {
                log.error("Unexpected FusionSolar error on {} account={} attempt={}", endpoint, effectiveAccountKey, attempt, e);

                throw new RuntimeException(
                        "Unexpected error while calling FusionSolar on " + endpoint,
                        e
                );
            }
        }

        throw lastRuntimeException != null
                ? lastRuntimeException
                : new RuntimeException("Unable to call FusionSolar on " + endpoint);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.fusionsolar.monitoring.service.sync.PersistentRateBudgetService inventoryBudget;

    private void reserveProviderSlot(String accountKey, String endpoint) {
        if (backgroundCollection.get() && !isHistoryEndpoint(endpoint)) {
            rateLimiter.awaitSlot(accountKey, endpoint);
            reserveInventoryBudget(accountKey, endpoint);
            return;
        }

        long retryAfterMs = rateLimiter.tryReserveSlot(accountKey, endpoint);
        if (retryAfterMs > 0L) {
            throw new HuaweiApiDeferredException(
                    "FusionSolar live call deferred endpoint=" + endpoint
                            + " account=" + accountKey
                            + " retryAfterMs=" + retryAfterMs,
                    retryAfterMs
            );
        }
        reserveInventoryBudget(accountKey, endpoint);
    }

    private void reserveInventoryBudget(String accountKey, String endpoint) {
        if ("/thirdData/stations".equals(endpoint) && inventoryBudget != null &&
                !inventoryBudget.tryConsumeDaily("HUAWEI", accountKey, endpoint, 24, java.time.ZoneId.of("Africa/Tunis"))) {
            throw new HuaweiApiDeferredException("Huawei inventory daily budget exhausted", 86400000L);
        }

    }

    private boolean isHistoryEndpoint(String endpoint) {
        return "/thirdData/getKpiStationHour".equals(endpoint)
                || "/thirdData/getKpiStationDay".equals(endpoint)
                || "/thirdData/getKpiStationMonth".equals(endpoint)
                || "/thirdData/getKpiStationYear".equals(endpoint);
    }

    public long huaweiWaitMsBeforeNextHistoryCall(String accountKey) {
        return rateLimiter.waitMsBeforeNextSlot(effectiveAccountKey(accountKey), "/thirdData/getKpiStationHour");
    }

    public long huaweiWaitMsBeforeCall(String accountKey, String endpoint) {
        return rateLimiter.waitMsBeforeNextSlot(effectiveAccountKey(accountKey), endpoint);
    }

    public boolean hasUsableHuaweiSession(String accountKey) {
        return sessionManager.hasUsableSession(effectiveAccountKey(accountKey));
    }

    public boolean isHuaweiAccountCoolingDown(String accountKey) {
        return rateLimiter.isAccountCoolingDown(effectiveAccountKey(accountKey));
    }

    private String buildAbsoluteUrl(String accountKey, String endpoint) {
        HuaweiAccount account = huaweiAccountDirectory.getRequiredAccount(accountKey);
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

        return normalizedBaseUrl + endpoint;
    }

    private <T, R> T doPost(String url, String endpoint, R body, Class<T> responseType, HuaweiSession session) {
        HttpHeaders headers = buildHeaders(session);
        HttpEntity<R> request = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                request,
                String.class
        );

        String rawBody = response.getBody();

        if (rawBody == null || rawBody.isBlank()) {
            throw new RuntimeException("FusionSolar returned empty body for " + endpoint);
        }

        return parseSuccessfulBody(rawBody, endpoint, responseType);
    }

    private <T> T parseSuccessfulBody(String rawBody, String endpoint, Class<T> responseType) {
        try {
            HuaweiApiEnvelope envelope = objectMapper.readValue(rawBody, HuaweiApiEnvelope.class);

            if (isHuaweiBusinessError(envelope) || isUnexpectedTextualData(envelope)) {
                throw mapBusinessError(endpoint, envelope, rawBody);
            }

            return objectMapper.readValue(rawBody, responseType);

        } catch (FusionSolarApiException e) {
            throw e;

        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to parse FusionSolar response for " + endpoint + " body=" + truncate(rawBody),
                    e
            );
        }
    }

    private boolean isHuaweiBusinessError(HuaweiApiEnvelope envelope) {
        if (envelope == null) {
            return false;
        }

        if (Boolean.FALSE.equals(envelope.getSuccess())) {
            return true;
        }

        Integer failCode = envelope.getFailCode();

        return failCode != null && failCode != 0;
    }

    private boolean isUnexpectedTextualData(HuaweiApiEnvelope envelope) {
        if (envelope == null) {
            return false;
        }

        JsonNode data = envelope.getData();

        return data != null && data.isTextual();
    }

    private boolean isHuaweiAuthBusinessError(Integer failCode, String message, HuaweiApiEnvelope envelope) {
        String normalized = normalizeBusinessText(message, envelope);

        return failCode != null && (failCode == 305 || failCode == 401 || failCode == 403)
                || normalized.contains("USER_MUST_RELOGIN")
                || normalized.contains("MUST_RELOGIN")
                || normalized.contains("RELOGIN")
                || normalized.contains("LOGIN")
                || normalized.contains("SESSION")
                || normalized.contains("TOKEN")
                || normalized.contains("AUTH");
    }

    private boolean isHuaweiRateLimitBusinessError(Integer failCode, String message, HuaweiApiEnvelope envelope) {
        String normalized = normalizeBusinessText(message, envelope);

        return failCode != null && (failCode == 407 || failCode == 429)
                || normalized.contains("ACCESS_FREQUENCY_IS_TOO_HIGH")
                || normalized.contains("FREQUENCY")
                || normalized.contains("HIGH FREQUENCY")
                || normalized.contains("HIGH_FREQUENCY")
                || normalized.contains("TOO_HIGH")
                || normalized.contains("RATE LIMIT")
                || normalized.contains("TOO MANY");
    }

    private String normalizeBusinessText(String message, HuaweiApiEnvelope envelope) {
        StringBuilder builder = new StringBuilder();

        if (message != null) {
            builder.append(message).append(' ');
        }

        if (envelope != null && envelope.getData() != null && envelope.getData().isTextual()) {
            builder.append(envelope.getData().asText()).append(' ');
        }

        return builder.toString().toUpperCase(Locale.ROOT);
    }

    private FusionSolarApiException mapBusinessError(
            String endpoint,
            HuaweiApiEnvelope envelope,
            String rawBody
    ) {
        Integer failCode = envelope != null ? envelope.getFailCode() : null;
        String message = envelope != null ? envelope.getMessage() : null;

        boolean authError = isHuaweiAuthBusinessError(failCode, message, envelope);
        boolean rateLimit = isHuaweiRateLimitBusinessError(failCode, message, envelope);

        String finalMessage;

        if (message != null && !message.isBlank()) {
            finalMessage = "FusionSolar business error on " + endpoint + ": " + message;

        } else if (isUnexpectedTextualData(envelope) && envelope.getData() != null) {
            finalMessage = "FusionSolar returned textual data instead of structured payload on "
                    + endpoint
                    + ": "
                    + envelope.getData().asText();

        } else if (failCode != null) {
            finalMessage = "FusionSolar business error on " + endpoint + " with failCode=" + failCode;

        } else {
            finalMessage = "FusionSolar returned invalid business payload on " + endpoint;
        }

        return new FusionSolarApiException(
                finalMessage,
                endpoint,
                failCode,
                authError,
                rateLimit,
                rawBody
        );
    }

    private HttpHeaders buildHeaders(HuaweiSession session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("xsrf-token", session.getXsrfToken());

        String cookieHeader = buildCookieHeader(session.getCookies());

        if (cookieHeader != null && !cookieHeader.isBlank()) {
            headers.set(HttpHeaders.COOKIE, cookieHeader);
        }

        return headers;
    }

    private String buildCookieHeader(List<String> rawSetCookies) {
        if (rawSetCookies == null || rawSetCookies.isEmpty()) {
            return "";
        }

        return rawSetCookies.stream()
                .filter(cookie -> cookie != null && !cookie.isBlank())
                .map(cookie -> {
                    int separatorIndex = cookie.indexOf(';');
                    return separatorIndex >= 0 ? cookie.substring(0, separatorIndex) : cookie;
                })
                .map(String::trim)
                .filter(cookie -> !cookie.isBlank())
                .distinct()
                .collect(Collectors.joining("; "));
    }

    private boolean isAuthError(HttpStatusCodeException e) {
        int status = e.getStatusCode().value();

        return status == 401 || status == 403;
    }

    private boolean isRateLimitError(HttpStatusCodeException e) {
        int status = e.getStatusCode().value();

        return status == 407 || status == 429;
    }

    private String safeErrorBody(HttpStatusCodeException e) {
        try {
            String body = e.getResponseBodyAsString();

            if (body == null || body.isBlank()) {
                return "<empty>";
            }

            return truncate(body);

        } catch (Exception ex) {
            return "<unavailable>";
        }
    }

    private List<String> sanitizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private void validateCsvLimit(String csv, int maxItems, String fieldName) {
        if (csv == null || csv.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }

        long count = splitCsv(csv).size();

        if (count == 0) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }

        if (count > maxItems) {
            throw new IllegalArgumentException(
                    fieldName + " accepts at most " + maxItems + " items per request"
            );
        }
    }

    private List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }

        return List.of(csv.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String truncate(String body) {
        if (body == null) {
            return "<null>";
        }

        return body.length() > 500
                ? body.substring(0, 500) + "...(truncated)"
                : body;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String effectiveAccountKey(String accountKey) {
        return accountKey == null || accountKey.isBlank()
                ? huaweiAccountDirectory.primaryAccountKey()
                : accountKey;
    }


}
