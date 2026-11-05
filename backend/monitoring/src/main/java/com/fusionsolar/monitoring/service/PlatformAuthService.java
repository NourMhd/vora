package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.AppAuthResponse;
import com.fusionsolar.monitoring.dto.AppLoginRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlatformAuthService {

    @Value("${app.auth.username:technicien}")
    private String configuredUsername;

    @Value("${app.auth.password:}")
    private String configuredPassword;

    @Value("${app.auth.orange-local-username:vora.orange.local}")
    private String orangeLocalUsername;

    @Value("${app.auth.orange-local-password:}")
    private String orangeLocalPassword;

    @Value("${app.auth.independent-username:client}")
    private String independentUsername;

    @Value("${app.auth.independent-password:}")
    private String independentPassword;

    @Value("${app.auth.ooredoo-username:vora.ooredoo}")
    private String ooredooUsername;

    @Value("${app.auth.ooredoo-password:}")
    private String ooredooPassword;

    public AppAuthResponse login(AppLoginRequest request, HttpSession session) {
        AuthenticatedPlatformUser authenticatedUser = authenticate(request);

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM_USER"));
        authorities.add(new SimpleGrantedAuthority(roleForScope(authenticatedUser.scope())));
        if (authenticatedUser.planningAccess()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_FIELD_PLANNING"));
        }

        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                authenticatedUser.username(),
                null,
                authorities
        );

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                context
        );

        return new AppAuthResponse(true, authenticatedUser.username(), authenticatedUser.scope().name(), authenticatedUser.planningAccess());
    }

    public AppAuthResponse me(HttpSession session) {
        Object contextObject = session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
        );

        if (!(contextObject instanceof SecurityContext context)
                || context.getAuthentication() == null
                || !context.getAuthentication().isAuthenticated()) {
            return new AppAuthResponse(false, null, null, false);
        }

        Authentication authentication = context.getAuthentication();
        return new AppAuthResponse(
                true,
                authentication.getName(),
                scopeFromAuthentication(authentication).name(),
                authentication.getAuthorities().stream().anyMatch(a -> "ROLE_FIELD_PLANNING".equals(a.getAuthority()))
        );
    }

    public void logout(HttpSession session) {
        SecurityContextHolder.clearContext();
        session.invalidate();
    }

    private AuthenticatedPlatformUser authenticate(AppLoginRequest request) {
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            throw new RuntimeException("Invalid username or password");
        }

        String username = request.getUsername().trim();
        String password = request.getPassword();

        if (!configuredPassword.isBlank() && configuredUsername.equals(username) && configuredPassword.equals(password)) {
            return new AuthenticatedPlatformUser(configuredUsername, SiteAccessScope.PROJECT, false);
        }

        if (!orangeLocalPassword.isBlank() && orangeLocalUsername.equals(username) && orangeLocalPassword.equals(password)) {
            return new AuthenticatedPlatformUser(orangeLocalUsername, SiteAccessScope.PROJECT, true);
        }

        if (!ooredooPassword.isBlank() && ooredooUsername.equals(username) && ooredooPassword.equals(password)) {
            return new AuthenticatedPlatformUser(ooredooUsername, SiteAccessScope.OOREDOO, false);
        }

        if (!independentPassword.isBlank() && independentUsername.equals(username) && independentPassword.equals(password)) {
            return new AuthenticatedPlatformUser(independentUsername, SiteAccessScope.INDEPENDENT, false);
        }

        throw new RuntimeException("Invalid username or password");
    }

    private SiteAccessScope scopeFromAuthentication(Authentication authentication) {
        boolean ooredoo = authentication.getAuthorities().stream()
                .anyMatch(authority -> CurrentSiteAccessService.ROLE_OOREDOO_SITES.equals(authority.getAuthority()));
        if (ooredoo) {
            return SiteAccessScope.OOREDOO;
        }

        boolean independent = authentication.getAuthorities().stream()
                .anyMatch(authority -> CurrentSiteAccessService.ROLE_INDEPENDENT_SITES.equals(authority.getAuthority()));

        return independent ? SiteAccessScope.INDEPENDENT : SiteAccessScope.PROJECT;
    }

    private String roleForScope(SiteAccessScope scope) {
        return switch (scope) {
            case OOREDOO -> CurrentSiteAccessService.ROLE_OOREDOO_SITES;
            case INDEPENDENT -> CurrentSiteAccessService.ROLE_INDEPENDENT_SITES;
            case PROJECT -> CurrentSiteAccessService.ROLE_PROJECT_SITES;
        };
    }

    private record AuthenticatedPlatformUser(String username, SiteAccessScope scope, boolean planningAccess) {
    }
}
