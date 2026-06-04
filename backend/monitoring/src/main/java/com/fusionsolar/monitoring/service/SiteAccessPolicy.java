package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

public final class SiteAccessPolicy {

    public static final List<String> PROJECT_SITE_PREFIXES = List.of(
            "ARI_",
            "BAR_",
            "MAN_",
            "JEN_",
            "ZAG_",
            "BIZ_",
            "TUN_",
            "NAB_",
            "BEJ_",
            "KEF_",
            "KAI_",
            "SOU_",
            "MAH_",
            "KAS_",
            "SIL_",
            "MON_",
            "SBO_",
            "MED_",
            "TAT_",
            "GAB_",
            "SFA_",
            "TOZ_",
            "GAF_",
            "KEB_"
    );

    private SiteAccessPolicy() {
    }

    public static boolean isActive(SiteDocument site) {
        return site != null && !Boolean.FALSE.equals(site.getActive());
    }

    public static boolean isProjectSite(SiteDocument site) {
        if (site == null) {
            return false;
        }

        return startsWithProjectPrefix(site.getSiteName())
                || startsWithProjectPrefix(site.getSiteCode())
                || startsWithProjectPrefix(site.getProviderSiteId());
    }

    public static boolean isProjectSite(SiteSummaryDto site) {
        if (site == null) {
            return false;
        }

        return startsWithProjectPrefix(site.getSiteName())
                || startsWithProjectPrefix(site.getSiteCode());
    }

    public static boolean isVisibleForScope(SiteDocument site, SiteAccessScope scope) {
        if (!isActive(site)) {
            return false;
        }

        if (scope == SiteAccessScope.INDEPENDENT) {
            return !isProjectSite(site);
        }

        return isProjectSite(site);
    }

    public static String projectPrefixesLabel() {
        return String.join(", ", PROJECT_SITE_PREFIXES);
    }

    private static boolean startsWithProjectPrefix(String value) {
        String normalized = normalizePrefixSource(value);

        if (normalized.isBlank()) {
            return false;
        }

        String compact = normalized.replace("_", "");

        return PROJECT_SITE_PREFIXES.stream().anyMatch(prefix -> {
            String compactPrefix = prefix.replace("_", "");
            return normalized.startsWith(prefix)
                    || (compact.startsWith(compactPrefix)
                    && compact.length() > compactPrefix.length()
                    && Character.isDigit(compact.charAt(compactPrefix.length())));
        });
    }

    private static String normalizePrefixSource(String value) {
        if (value == null) {
            return "";
        }

        String withoutAccents = Normalizer
                .normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        return withoutAccents.toUpperCase(Locale.ROOT);
    }
}
