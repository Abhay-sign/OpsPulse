package com.opspulse.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads "Authorization: Bearer <access token>" and, if valid, puts an AuthUser
 * into the SecurityContext. An invalid or missing token is NOT rejected here --
 * the request simply continues unauthenticated, and the authorization rules
 * decide whether that is allowed (e.g. /auth/login is public).
 *
 * Not a @Component on purpose: Spring Boot auto-registers every Filter bean as
 * a global servlet filter, so it would ALSO run on the API-key chain.
 * SecurityConfig instantiates it and adds it to one chain only.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = null;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        } else if (request.getRequestURI().endsWith("/stream")) {
            // Browsers' EventSource cannot set headers, so the SSE endpoint
            // (phase 6) accepts the access token as a query parameter.
            token = request.getParameter("access_token");
        }
        if (token != null) {
            jwtService.parseAccessToken(token).ifPresent(user -> {
                var auth = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }
}
