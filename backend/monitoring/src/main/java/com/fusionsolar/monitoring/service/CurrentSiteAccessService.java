package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CurrentSiteAccessService {

    public static final String ROLE_PROJECT_SITES = "ROLE_PROJECT_SITES";
    public static final String ROLE_OOREDOO_SITES = "ROLE_OOREDOO_SITES";
    public static final String ROLE_INDEPENDENT_SITES = "ROLE_INDEPENDENT_SITES";

    public SiteAccessScope currentScope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return SiteAccessScope.PROJECT;
        }

        boolean ooredoo = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ROLE_OOREDOO_SITES::equals);
        if (ooredoo) {
            return SiteAccessScope.OOREDOO;
        }

        boolean independent = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ROLE_INDEPENDENT_SITES::equals);

        return independent ? SiteAccessScope.INDEPENDENT : SiteAccessScope.PROJECT;
    }

    public boolean canAccess(SiteDocument site) {
        return SiteAccessPolicy.isVisibleForScope(site, currentScope());
    }
}
