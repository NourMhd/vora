package com.fusionsolar.monitoring.service.huawei;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralise les appels FusionSolar par vrai compte / entreprise FusionSolar.
 *
 * Les 5 identifiants Northbound d'un même compte FusionSolar partagent le même group
 * et donc le même throttle. Deux groupes différents, par exemple itscom et
 * global-engineering, ne se bloquent plus entre eux.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HuaweiApiRateLimiter {

    private final Object lock = new Object();
    private final Map<String, GroupState> statesByGroup = new ConcurrentHashMap<>();
    private final HuaweiAccountDirectory huaweiAccountDirectory;

    @Value("${app.huawei-api.group-min-request-interval-ms:${app.huawei-api.account-min-request-interval-ms:180000}}")
    private long groupMinRequestIntervalMs;

    @Value("${app.huawei-api.history-group-min-request-interval-ms:${app.huawei-api.history-account-min-request-interval-ms:240000}}")
    private long historyGroupMinRequestIntervalMs;

    @Value("${app.huawei-api.login-min-request-interval-ms:180000}")
    private long loginMinRequestIntervalMs;

    @Value("${app.huawei-api.initial-rate-limit-cooldown-ms:1800000}")
    private long initialRateLimitCooldownMs;

    @Value("${app.huawei-api.max-rate-limit-cooldown-ms:10800000}")
    private long maxRateLimitCooldownMs;

    public void awaitSlot(String accountKey, String endpoint) {
        String effectiveGroup = resolveGroup(accountKey);
        String effectiveEndpoint = normalizeEndpoint(endpoint);

        while (true) {
            long waitMs;

            synchronized (lock) {
                waitMs = computeWaitMsLocked(effectiveGroup, effectiveEndpoint, true);

                if (waitMs <= 0L) {
                    long reservedAt = System.currentTimeMillis();
                    GroupState state = stateOf(effectiveGroup);

                    state.lastRequestAt = reservedAt;
                    state.endpointLastRequestAt.put(effectiveEndpoint, reservedAt);

                    if (isHistoryEndpoint(effectiveEndpoint)) {
                        state.lastHistoryRequestAt = reservedAt;
                    }

                    return;
                }
            }

            log.info(
                    "FusionSolar group guard waiting {} ms before calling {} group={} sourceAccount={}",
                    waitMs,
                    effectiveEndpoint,
                    effectiveGroup,
                    normalizeKey(accountKey)
            );

            sleep(waitMs);
        }
    }

    public long waitMsBeforeNextSlot(String accountKey, String endpoint) {
        synchronized (lock) {
            return computeWaitMsLocked(resolveGroup(accountKey), normalizeEndpoint(endpoint), false);
        }
    }

    public boolean isAccountCoolingDown(String accountKey) {
        synchronized (lock) {
            GroupState state = stateOf(resolveGroup(accountKey));
            return state.nextAllowedAt > System.currentTimeMillis();
        }
    }

    public void registerSuccess(String accountKey, String endpoint) {
        String effectiveGroup = resolveGroup(accountKey);
        String effectiveEndpoint = normalizeEndpoint(endpoint);

        synchronized (lock) {
            GroupState state = stateOf(effectiveGroup);

            state.endpointCooldownMs.remove(effectiveEndpoint);

            long now = System.currentTimeMillis();
            Long endpointAllowedAt = state.endpointNextAllowedAt.get(effectiveEndpoint);
            if (endpointAllowedAt != null && endpointAllowedAt <= now) {
                state.endpointNextAllowedAt.remove(effectiveEndpoint);
            }

            if (state.nextAllowedAt <= now) {
                state.groupRateLimitCooldownMs = 0L;
            }
        }
    }

    public void registerRateLimit(String accountKey, String endpoint, int attempt) {
        String effectiveGroup = resolveGroup(accountKey);
        String effectiveEndpoint = normalizeEndpoint(endpoint);
        long cooldownToApply;

        synchronized (lock) {
            GroupState state = stateOf(effectiveGroup);
            long now = System.currentTimeMillis();

            long endpointCooldown = state.endpointCooldownMs.getOrDefault(effectiveEndpoint, 0L);
            endpointCooldown = nextCooldown(endpointCooldown);
            state.endpointCooldownMs.put(effectiveEndpoint, endpointCooldown);

            state.groupRateLimitCooldownMs = nextCooldown(state.groupRateLimitCooldownMs);

            cooldownToApply = Math.max(endpointCooldown, state.groupRateLimitCooldownMs);

            state.endpointNextAllowedAt.put(
                    effectiveEndpoint,
                    Math.max(state.endpointNextAllowedAt.getOrDefault(effectiveEndpoint, 0L), now + endpointCooldown)
            );

            state.nextAllowedAt = Math.max(state.nextAllowedAt, now + state.groupRateLimitCooldownMs);
        }

        log.warn(
                "FusionSolar rate limit registered on {} group={} sourceAccount={} at attempt {}. Group cooldown={} ms.",
                effectiveEndpoint,
                effectiveGroup,
                normalizeKey(accountKey),
                attempt,
                cooldownToApply
        );
    }

    private long computeWaitMsLocked(String groupKey, String endpoint, boolean includeCadence) {
        long now = System.currentTimeMillis();
        GroupState state = stateOf(groupKey);

        long allowedAt = state.nextAllowedAt;

        Long endpointAllowedAt = state.endpointNextAllowedAt.get(endpoint);
        if (endpointAllowedAt != null) {
            allowedAt = Math.max(allowedAt, endpointAllowedAt);
        }

        if (includeCadence) {
            allowedAt = Math.max(allowedAt, state.lastRequestAt + groupDelayMs(endpoint));

            Long endpointLastAt = state.endpointLastRequestAt.get(endpoint);
            if (endpointLastAt != null) {
                allowedAt = Math.max(allowedAt, endpointLastAt + endpointDelayMs(endpoint));
            }

            if (isHistoryEndpoint(endpoint)) {
                allowedAt = Math.max(allowedAt, state.lastHistoryRequestAt + Math.max(60_000L, historyGroupMinRequestIntervalMs));
            }
        }

        return Math.max(0L, allowedAt - now);
    }

    private GroupState stateOf(String groupKey) {
        return statesByGroup.computeIfAbsent(groupKey, key -> new GroupState());
    }

    private String resolveGroup(String accountKey) {
        String effectiveKey = normalizeKey(accountKey);

        for (HuaweiAccountDirectory.HuaweiAccount account : huaweiAccountDirectory.getEnabledAccounts()) {
            if (account.key().equals(effectiveKey)) {
                return normalizeKey(account.group());
            }
        }

        for (HuaweiAccountDirectory.HuaweiAccount account : huaweiAccountDirectory.getEnabledAccounts()) {
            if (account.group().equals(effectiveKey)) {
                return effectiveKey;
            }
        }

        return effectiveKey;
    }

    private long nextCooldown(long currentCooldownMs) {
        long initial = Math.max(60_000L, initialRateLimitCooldownMs);
        long max = Math.max(initial, maxRateLimitCooldownMs);

        if (currentCooldownMs <= 0L) {
            return initial;
        }

        return Math.min(currentCooldownMs * 2L, max);
    }

    private long groupDelayMs(String endpoint) {
        if ("/thirdData/login".equals(endpoint)) {
            return Math.max(60_000L, loginMinRequestIntervalMs);
        }

        if (isHistoryEndpoint(endpoint)) {
            return Math.max(60_000L, historyGroupMinRequestIntervalMs);
        }

        return Math.max(30_000L, groupMinRequestIntervalMs);
    }

    private long endpointDelayMs(String endpoint) {
        if ("/thirdData/login".equals(endpoint)) {
            return Math.max(60_000L, loginMinRequestIntervalMs);
        }

        if (isHistoryEndpoint(endpoint)) {
            return Math.max(60_000L, historyGroupMinRequestIntervalMs);
        }

        return Math.max(30_000L, groupMinRequestIntervalMs);
    }

    private boolean isHistoryEndpoint(String endpoint) {
        return "/thirdData/getKpiStationHour".equals(endpoint)
                || "/thirdData/getKpiStationDay".equals(endpoint)
                || "/thirdData/getKpiStationMonth".equals(endpoint)
                || "/thirdData/getKpiStationYear".equals(endpoint);
    }

    private String normalizeKey(String value) {
        return value == null || value.isBlank() ? "default" : value.trim();
    }

    private String normalizeEndpoint(String endpoint) {
        return endpoint == null || endpoint.isBlank() ? "unknown" : endpoint.trim();
    }

    private void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for FusionSolar API group guard", e);
        }
    }

    private static class GroupState {
        private long lastRequestAt = 0L;
        private long lastHistoryRequestAt = 0L;
        private long nextAllowedAt = 0L;
        private long groupRateLimitCooldownMs = 0L;
        private final Map<String, Long> endpointLastRequestAt = new ConcurrentHashMap<>();
        private final Map<String, Long> endpointNextAllowedAt = new ConcurrentHashMap<>();
        private final Map<String, Long> endpointCooldownMs = new ConcurrentHashMap<>();
    }
}
