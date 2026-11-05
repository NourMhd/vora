package com.fusionsolar.monitoring.service.sync;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import java.time.Instant;

public final class SiteMissingPolicy {
    private SiteMissingPolicy() {}

    public static boolean confirmMissing(SiteDocument site, Instant now) {
        int count = site.getMissingConfirmations() == null ? 0 : site.getMissingConfirmations();
        site.setMissingConfirmations(count + 1);
        site.setMissingSince(site.getMissingSince() == null ? now : site.getMissingSince());
        site.setUpdatedAt(now);
        if (count + 1 < 2) return false;
        site.setActive(false);
        site.setDeletedAt(now);
        return true;
    }
}
