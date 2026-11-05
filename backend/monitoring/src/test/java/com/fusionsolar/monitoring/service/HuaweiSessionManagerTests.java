package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.client.FusionSolarAuthClient;
import com.fusionsolar.monitoring.dto.HuaweiSession;
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory;
import com.fusionsolar.monitoring.service.huawei.HuaweiApiDeferredException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HuaweiSessionManagerTests {

    @Test
    void authFailureDefersReloginWithoutSleeping() {
        FusionSolarAuthClient authClient = mock(FusionSolarAuthClient.class);
        HuaweiAccountDirectory directory = mock(HuaweiAccountDirectory.class);
        HuaweiSessionManager manager = new HuaweiSessionManager(authClient, directory);

        ReflectionTestUtils.setField(manager, "minLoginIntervalMillis", 300_000L);
        when(authClient.login("api-01")).thenReturn(
                new FusionSolarAuthClient.LoginResult(null, "token", List.of("cookie=value"))
        );

        HuaweiSession session = manager.getSession("api-01");
        manager.invalidateSession("api-01", session);

        long startedAt = System.nanoTime();
        Throwable thrown = catchThrowable(() -> manager.getSession("api-01"));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(thrown)
                .isInstanceOf(HuaweiApiDeferredException.class)
                .hasMessageContaining("relogin pending");
        assertThat(elapsedMs).isLessThan(1_000L);

        verify(authClient, times(1)).login("api-01");
    }
}
