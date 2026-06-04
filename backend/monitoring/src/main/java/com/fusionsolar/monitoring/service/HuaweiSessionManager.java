package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.client.FusionSolarAuthClient;
import com.fusionsolar.monitoring.dto.HuaweiSession;
import com.fusionsolar.monitoring.service.huawei.HuaweiAccountDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class HuaweiSessionManager {

    private static final long SESSION_TTL_MILLIS = 25 * 60 * 1000L;

    private final FusionSolarAuthClient authClient;
    private final HuaweiAccountDirectory huaweiAccountDirectory;

    @Value("${app.huawei-api.login-min-request-interval-ms:180000}")
    private long minLoginIntervalMillis;

    private final Map<String, AccountSessionState> sessionsByAccount = new ConcurrentHashMap<>();

    public HuaweiSession getSession() {
        return getSession(huaweiAccountDirectory.primaryAccountKey());
    }

    public HuaweiSession getSession(String accountKey) {
        String effectiveKey = effectiveAccountKey(accountKey);
        AccountSessionState state = sessionsByAccount.computeIfAbsent(effectiveKey, key -> new AccountSessionState());

        HuaweiSession session = state.currentSession;

        if (session == null || isExpired(state)) {
            synchronized (state.lock) {
                session = state.currentSession;

                if (session == null || isExpired(state)) {
                    awaitLoginWindow(state);
                    state.currentSession = loginAndBuildSession(effectiveKey);
                    state.lastLoginTime = System.currentTimeMillis();
                }

                return state.currentSession;
            }
        }

        return session;
    }

    public HuaweiSession forceRefreshSession() {
        return forceRefreshSession(huaweiAccountDirectory.primaryAccountKey());
    }

    public HuaweiSession forceRefreshSession(String accountKey) {
        String effectiveKey = effectiveAccountKey(accountKey);
        AccountSessionState state = sessionsByAccount.computeIfAbsent(effectiveKey, key -> new AccountSessionState());

        synchronized (state.lock) {
            awaitLoginWindow(state);
            state.currentSession = loginAndBuildSession(effectiveKey);
            state.lastLoginTime = System.currentTimeMillis();
            return state.currentSession;
        }
    }

    public void invalidateSession(HuaweiSession sessionToInvalidate) {
        invalidateSession(huaweiAccountDirectory.primaryAccountKey(), sessionToInvalidate);
    }

    public void invalidateSession(String accountKey, HuaweiSession sessionToInvalidate) {
        String effectiveKey = effectiveAccountKey(accountKey);
        AccountSessionState state = sessionsByAccount.computeIfAbsent(effectiveKey, key -> new AccountSessionState());

        synchronized (state.lock) {
            if (state.currentSession == null || sessionToInvalidate == null) {
                return;
            }

            if (sameSession(state.currentSession, sessionToInvalidate)) {
                state.currentSession = null;
                state.lastLoginTime = 0L;
            }
        }
    }

    public void invalidateCurrentSession() {
        invalidateCurrentSession(huaweiAccountDirectory.primaryAccountKey());
    }

    public void invalidateCurrentSession(String accountKey) {
        String effectiveKey = effectiveAccountKey(accountKey);
        AccountSessionState state = sessionsByAccount.computeIfAbsent(effectiveKey, key -> new AccountSessionState());

        synchronized (state.lock) {
            state.currentSession = null;
            state.lastLoginTime = 0L;
        }
    }


    private void awaitLoginWindow(AccountSessionState state) {
        long now = System.currentTimeMillis();
        long elapsed = now - state.lastLoginAttemptAt;
        long waitMs = Math.max(60_000L, minLoginIntervalMillis) - elapsed;

        if (state.lastLoginAttemptAt > 0L && waitMs > 0L) {
            sleepQuietly(waitMs);
        }

        state.lastLoginAttemptAt = System.currentTimeMillis();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private HuaweiSession loginAndBuildSession(String accountKey) {
        FusionSolarAuthClient.LoginResult login = authClient.login(accountKey);

        if (login == null) {
            throw new RuntimeException("FusionSolar login failed for account " + accountKey + ": empty login result");
        }

        if (login.xsrfToken() == null || login.xsrfToken().isBlank()) {
            throw new RuntimeException("FusionSolar login failed for account " + accountKey + ": missing xsrf-token");
        }

        if (login.cookies() == null || login.cookies().isEmpty()) {
            throw new RuntimeException("FusionSolar login failed for account " + accountKey + ": missing cookies");
        }

        return new HuaweiSession(
                login.xsrfToken(),
                login.cookies()
        );
    }

    private boolean isExpired(AccountSessionState state) {
        return state.currentSession == null
                || System.currentTimeMillis() - state.lastLoginTime >= SESSION_TTL_MILLIS;
    }

    private boolean sameSession(HuaweiSession a, HuaweiSession b) {
        if (a == null || b == null) {
            return false;
        }

        boolean sameToken = safeEquals(a.getXsrfToken(), b.getXsrfToken());
        boolean sameCookies = a.getCookies() != null
                && b.getCookies() != null
                && a.getCookies().equals(b.getCookies());

        return sameToken && sameCookies;
    }

    private boolean safeEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    private String effectiveAccountKey(String accountKey) {
        return accountKey == null || accountKey.isBlank()
                ? huaweiAccountDirectory.primaryAccountKey()
                : accountKey;
    }

    private static class AccountSessionState {
        private final Object lock = new Object();
        private volatile HuaweiSession currentSession;
        private volatile long lastLoginTime = 0L;
        private volatile long lastLoginAttemptAt = 0L;
    }
}
