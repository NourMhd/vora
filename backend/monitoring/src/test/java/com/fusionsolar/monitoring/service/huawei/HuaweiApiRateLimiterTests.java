package com.fusionsolar.monitoring.service.huawei;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HuaweiApiRateLimiterTests {

    @Test
    void tryReserveSlotReturnsImmediatelyDuringCooldown() {
        HuaweiAccountDirectory directory = mock(HuaweiAccountDirectory.class);
        when(directory.getEnabledAccounts()).thenReturn(List.of(
                new HuaweiAccountDirectory.HuaweiAccount(
                        "api-01", "itscom", "https://example.test", "user", "password"
                )
        ));

        HuaweiApiRateLimiter limiter = new HuaweiApiRateLimiter(directory);
        ReflectionTestUtils.setField(limiter, "groupMinRequestIntervalMs", 300_000L);
        ReflectionTestUtils.setField(limiter, "historyGroupMinRequestIntervalMs", 60_000L);
        ReflectionTestUtils.setField(limiter, "loginMinRequestIntervalMs", 300_000L);

        assertThat(limiter.tryReserveSlot("api-01", "/thirdData/stations")).isZero();

        long startedAt = System.nanoTime();
        long retryAfterMs = limiter.tryReserveSlot("api-01", "/thirdData/stations");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(retryAfterMs).isGreaterThan(0L);
        assertThat(elapsedMs).isLessThan(1_000L);
    }
}
