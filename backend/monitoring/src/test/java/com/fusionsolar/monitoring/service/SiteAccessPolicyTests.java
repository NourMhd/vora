package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SiteAccessPolicyTests {

    @Test
    void orangeSeesPrefixedSitesOutsideGlobalEngineeringOnly() {
        assertThat(SiteAccessPolicy.isVisibleForScope(site("ARI_0001", "itscom", true), SiteAccessScope.PROJECT))
                .isTrue();
        assertThat(SiteAccessPolicy.isVisibleForScope(site("ARI_0002", "global-engineering", true), SiteAccessScope.PROJECT))
                .isFalse();
        assertThat(SiteAccessPolicy.isVisibleForScope(site("CLIENT_NORMAL", "itscom", true), SiteAccessScope.PROJECT))
                .isFalse();
    }

    @Test
    void ooredooSeesOnlyPrefixedGlobalEngineeringSites() {
        assertThat(SiteAccessPolicy.isVisibleForScope(site("BAR_0001", "global-engineering", true), SiteAccessScope.OOREDOO))
                .isTrue();
        assertThat(SiteAccessPolicy.isVisibleForScope(site("BAR_0002", "itscom", true), SiteAccessScope.OOREDOO))
                .isFalse();
        assertThat(SiteAccessPolicy.isVisibleForScope(site("CLIENT_NORMAL", "global-engineering", true), SiteAccessScope.OOREDOO))
                .isFalse();
    }

    @Test
    void independentClientSeesOnlyNonPrefixedSitesFromEveryProviderGroup() {
        assertThat(SiteAccessPolicy.isVisibleForScope(site("CLIENT_A", "itscom", true), SiteAccessScope.INDEPENDENT))
                .isTrue();
        assertThat(SiteAccessPolicy.isVisibleForScope(site("CLIENT_B", "global-engineering", true), SiteAccessScope.INDEPENDENT))
                .isTrue();
        assertThat(SiteAccessPolicy.isVisibleForScope(site("SFA_0001", "global-engineering", true), SiteAccessScope.INDEPENDENT))
                .isFalse();
    }

    @Test
    void inactiveSitesAreHiddenForEveryScope() {
        SiteDocument inactive = site("ARI_0001", "global-engineering", false);

        assertThat(SiteAccessPolicy.isVisibleForScope(inactive, SiteAccessScope.PROJECT)).isFalse();
        assertThat(SiteAccessPolicy.isVisibleForScope(inactive, SiteAccessScope.OOREDOO)).isFalse();
        assertThat(SiteAccessPolicy.isVisibleForScope(inactive, SiteAccessScope.INDEPENDENT)).isFalse();
    }

    private SiteDocument site(String siteCode, String providerGroup, boolean active) {
        return SiteDocument.builder()
                .siteCode(siteCode)
                .siteName(siteCode)
                .providerSiteId(siteCode)
                .providerAccountGroup(providerGroup)
                .active(active)
                .build();
    }
}
