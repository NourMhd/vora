package com.fusionsolar.monitoring.util;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.dto.HistoryPointDto;
import java.util.List;

/** Presentation/calculation override. Provider data in MongoDB is preserved. */
public final class SiteProductionPolicy {
    private SiteProductionPolicy() {}

    public static boolean forcesZero(SiteDocument site) {
        return site != null && (matches(site.getSiteName()) || matches(site.getSiteCode())
                || matches(site.getProviderSiteId()));
    }

    private static boolean matches(String identity) {
        return identity != null && "SFA_0138".equalsIgnoreCase(identity.trim());
    }

    public static Double energy(SiteDocument site, Double value) {
        if (forcesZero(site)) return 0.0;
        return value;
    }

    public static List<HistoryPointDto> history(SiteDocument site, List<HistoryPointDto> points) {
        if (!forcesZero(site) || points == null) return points;
        return points.stream().filter(java.util.Objects::nonNull)
                .map(point -> new HistoryPointDto(point.getCollectTime(), 0.0, "FORCED_ZERO"))
                .toList();
    }
}
