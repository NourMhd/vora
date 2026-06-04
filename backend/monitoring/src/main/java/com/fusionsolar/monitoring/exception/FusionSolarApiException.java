package com.fusionsolar.monitoring.exception;

public class FusionSolarApiException extends RuntimeException {

    private final Integer failCode;
    private final String endpoint;
    private final boolean authError;
    private final boolean rateLimit;
    private final String rawBody;

    public FusionSolarApiException(
            String message,
            String endpoint,
            Integer failCode,
            boolean authError,
            boolean rateLimit,
            String rawBody
    ) {
        super(message);
        this.endpoint = endpoint;
        this.failCode = failCode;
        this.authError = authError;
        this.rateLimit = rateLimit;
        this.rawBody = rawBody;
    }

    public Integer getFailCode() {
        return failCode;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public boolean isAuthError() {
        return authError;
    }

    public boolean isRateLimit() {
        return rateLimit;
    }

    public String getRawBody() {
        return rawBody;
    }
}