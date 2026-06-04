package com.fusionsolar.monitoring.util;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class SiteNameNormalizer {

    private static final Set<String> UPPERCASE_TOKENS = Set.of(
            "BT", "HT", "MT", "TGBT", "PV", "AC", "DC", "HV", "LV", "MV"
    );

    private SiteNameNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }

        String cleaned = value.trim().replaceAll("\\s+", " ");
        if (cleaned.isBlank()) {
            return cleaned;
        }

        return Arrays.stream(cleaned.split(" "))
                .map(SiteNameNormalizer::normalizeToken)
                .collect(Collectors.joining(" "));
    }

    private static String normalizeToken(String token) {
        if (token == null || token.isBlank()) {
            return token;
        }

        if (token.matches(".*\\d.*")) {
            return token.toUpperCase(Locale.ROOT);
        }

        if (UPPERCASE_TOKENS.contains(token.toUpperCase(Locale.ROOT))) {
            return token.toUpperCase(Locale.ROOT);
        }

        String lower = token.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}