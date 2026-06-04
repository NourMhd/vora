package com.fusionsolar.monitoring.exception;

public class UpstreamServiceException extends RuntimeException {
    public UpstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    public UpstreamServiceException(String message) {
        super(message);
    }
}
