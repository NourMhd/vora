package com.fusionsolar.monitoring.service.huawei;

/** Non-blocking signal: retry the Huawei call during a later scheduled cycle. */
public class HuaweiApiDeferredException extends RuntimeException {

    private final long retryAfterMs;

    public HuaweiApiDeferredException(String message, long retryAfterMs) {
        super(message);
        this.retryAfterMs = Math.max(1_000L, retryAfterMs);
    }

    public HuaweiApiDeferredException(String message, long retryAfterMs, Throwable cause) {
        super(message, cause);
        this.retryAfterMs = Math.max(1_000L, retryAfterMs);
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
