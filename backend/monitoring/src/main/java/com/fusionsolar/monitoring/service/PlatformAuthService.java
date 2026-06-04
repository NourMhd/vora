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

    @Value("${app.auth.password:technicien123}")
    private String configuredPassword;

    @Value("${app.auth.independent-username:client}")
    private String independentUsername;

    @Value("${app.auth.independent-password:client123}")
    private String independentPassword;

    public AppAuthResponse login(AppLoginRequest request, HttpSession session) {
        AuthenticatedPlatformUser authenticatedUser = authenticate(request);

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM_USER"));
        authorities.add(new SimpleGrantedAuthority(
                authenticatedUser.scope() == SiteAccessScope.INDEPENDENT
                        ? CurrentSiteAccessService.ROLE_INDEPENDENT_SITES
                        : CurrentSiteAccessService.ROLE_PROJECT_SITES
        ));

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

        return new AppAuthResponse(true, authenticatedUser.username(), authenticatedUser.scope().name());
    }

    public AppAuthResponse me(HttpSession session) {
        Object contextObject = session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
        );

        if (!(contextObject instanceof SecurityContext context)
                || context.getAuthentication() == null
                || !context.getAuthentication().isAuthenticated()) {
            return new AppAuthResponse(false, null, null);
        }

        Authentication authentication = context.getAuthentication();
        return new AppAuthResponse(
                true,
                authentication.getName(),
                scopeFromAuthentication(authentication).name()
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

        if (configuredUsername.equals(username) && configuredPassword.equals(password)) {
            return new AuthenticatedPlatformUser(configuredUsername, SiteAccessScope.PROJECT);
        }

        if (independentUsername.equals(username) && independentPassword.equals(password)) {
            return new AuthenticatedPlatformUser(independentUsername, SiteAccessScope.INDEPENDENT);
        }

        throw new RuntimeException("Invalid username or password");
    }

    private SiteAccessScope scopeFromAuthentication(Authentication authentication) {
        boolean independent = authentication.getAuthorities().stream()
                .anyMatch(authority -> CurrentSiteAccessService.ROLE_INDEPENDENT_SITES.equals(authority.getAuthority()));

        return independent ? SiteAccessScope.INDEPENDENT : SiteAccessScope.PROJECT;
    }

    private record AuthenticatedPlatformUser(String username, SiteAccessScope scope) {
    }
}
