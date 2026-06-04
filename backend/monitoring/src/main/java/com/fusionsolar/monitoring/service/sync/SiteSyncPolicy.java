package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.dto.SiteSummaryDto;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.service.SiteAccessPolicy;

public final class SiteSyncPolicy {

    private SiteSyncPolicy() {
    }

    public static boolean isVisibleSite(SiteDocument site) {
        return SiteAccessPolicy.isActive(site);
    }

    /**
     * Ancienne compatibilité: les préfixes projet ne servent plus à filtrer la synchronisation.
     * Ils servent seulement à séparer les données par login/session.
     */
    public static boolean isAllowedSolisSite(SiteSummaryDto site) {
        return site != null;
    }

    public static String solisAllowedPrefixesLabel() {
        return SiteAccessPolicy.projectPrefixesLabel();
    }
}
