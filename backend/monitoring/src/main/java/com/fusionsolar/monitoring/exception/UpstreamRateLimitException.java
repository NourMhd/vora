package com.fusionsolar.monitoring.exception;

public class UpstreamRateLimitException extends RuntimeException {
    public UpstreamRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }

    public UpstreamRateLimitException(String message) {
        super(message);
    }
}
