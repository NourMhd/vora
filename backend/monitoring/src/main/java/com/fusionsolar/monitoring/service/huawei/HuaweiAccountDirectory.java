package com.fusionsolar.monitoring.service.huawei;

import com.fusionsolar.monitoring.config.ProvidersProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HuaweiAccountDirectory {

    public static final String DEFAULT_ACCOUNT_KEY = "primary";
    public static final String DEFAULT_ACCOUNT_GROUP = "primary";

    private final ProvidersProperties providersProperties;

    public List<HuaweiAccount> getEnabledAccounts() {
        ProvidersProperties.ProviderProperties huawei = requireHuawei();
        List<HuaweiAccount> accounts = new ArrayList<>();

        if (huawei.getAccounts() != null && !huawei.getAccounts().isEmpty()) {
            for (ProvidersProperties.HuaweiAccountProperties raw : huawei.getAccounts()) {
                HuaweiAccount account = toAccount(raw, huawei);
                if (account != null) {
                    accounts.add(account);
                }
            }
        }

        if (accounts.isEmpty()) {
            HuaweiAccount fallback = toPrimaryAccount(huawei);
            if (fallback != null) {
                accounts.add(fallback);
            }
        }

        Map<String, HuaweiAccount> deduped = new LinkedHashMap<>();
        for (HuaweiAccount account : accounts) {
            deduped.putIfAbsent(account.key(), account);
        }

        return List.copyOf(deduped.values());
    }

    public Map<String, List<HuaweiAccount>> getEnabledAccountsByGroup() {
        Map<String, List<HuaweiAccount>> grouped = new LinkedHashMap<>();
        for (HuaweiAccount account : getEnabledAccounts()) {
            grouped.computeIfAbsent(account.group(), key -> new ArrayList<>()).add(account);
        }
        return grouped;
    }

    public HuaweiAccount getRequiredAccount(String accountKey) {
        String effectiveKey = isBlank(accountKey) ? primaryAccountKey() : accountKey;

        return getEnabledAccounts().stream()
                .filter(account -> account.key().equals(effectiveKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Huawei API account not configured or disabled: " + effectiveKey));
    }

    public String primaryAccountKey() {
        List<HuaweiAccount> accounts = getEnabledAccounts();
        return accounts.isEmpty() ? DEFAULT_ACCOUNT_KEY : accounts.get(0).key();
    }

    public String primaryAccountGroup() {
        List<HuaweiAccount> accounts = getEnabledAccounts();
        return accounts.isEmpty() ? DEFAULT_ACCOUNT_GROUP : accounts.get(0).group();
    }

    private HuaweiAccount toAccount(
            ProvidersProperties.HuaweiAccountProperties raw,
            ProvidersProperties.ProviderProperties defaults
    ) {
        if (raw == null || Boolean.FALSE.equals(raw.getEnabled())) {
            return null;
        }

        String key = blankToNull(raw.getKey());
        String username = firstNonBlank(raw.getUsername(), defaults.getUsername());
        String password = firstNonBlank(raw.getPassword(), defaults.getPassword());
        String baseUrl = firstNonBlank(raw.getBaseUrl(), defaults.getBaseUrl());

        if (isBlank(key)) {
            key = username;
        }

        String group = firstNonBlank(raw.getGroup(), key, username, DEFAULT_ACCOUNT_GROUP);

        if (isBlank(key) || isBlank(username) || isBlank(password) || isBlank(baseUrl) || isBlank(group)) {
            return null;
        }

        return new HuaweiAccount(key, group, baseUrl, username, password);
    }

    private HuaweiAccount toPrimaryAccount(ProvidersProperties.ProviderProperties huawei) {
        if (huawei == null || Boolean.FALSE.equals(huawei.getEnabled())) {
            return null;
        }

        String username = blankToNull(huawei.getUsername());
        String password = blankToNull(huawei.getPassword());
        String baseUrl = blankToNull(huawei.getBaseUrl());

        if (isBlank(username) || isBlank(password) || isBlank(baseUrl)) {
            return null;
        }

        return new HuaweiAccount(DEFAULT_ACCOUNT_KEY, DEFAULT_ACCOUNT_GROUP, baseUrl, username, password);
    }

    private ProvidersProperties.ProviderProperties requireHuawei() {
        ProvidersProperties.ProviderProperties huawei = providersProperties.getHuawei();

        if (huawei == null || Boolean.FALSE.equals(huawei.getEnabled())) {
            throw new IllegalStateException("Huawei provider is disabled or missing");
        }

        return huawei;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }

        return null;
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record HuaweiAccount(
            String key,
            String group,
            String baseUrl,
            String username,
            String password
    ) {
    }
}
