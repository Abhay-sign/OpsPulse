package com.opspulse.config;

import com.opspulse.security.ApiKeyAuthFilter;
import com.opspulse.security.JsonSecurityHandlers;
import com.opspulse.security.JwtAuthFilter;
import com.opspulse.security.JwtService;
import com.opspulse.service.ApiKeyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two independent filter chains, picked by URL. Spring Security tries chains
 * in @Order and uses the FIRST whose securityMatcher matches -- so a request
 * is handled by exactly one of them.
 *
 *   Chain 1  /api/v1/alerts/**   machines   X-API-Key   ROLE_INGEST
 *   Chain 2  everything else     humans     JWT Bearer  ROLE_ADMIN/RESPONDER/VIEWER
 *
 * Why two: a Prometheus Alertmanager or a cron script cannot log in and
 * refresh a 15-minute JWT, and a human should never be handed a permanent
 * key. Keeping them in separate chains also means a JWT is simply ignored on
 * the ingest path and an API key is ignored everywhere else -- neither
 * credential can be used outside its lane.
 *
 * Coarse rules (who may reach a URL at all) live here. Fine-grained rules
 * (ADMIN-only writes) live on the controller methods via @PreAuthorize, next
 * to the code they protect.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain ingestChain(HttpSecurity http, ApiKeyService apiKeyService,
                                           JsonSecurityHandlers handlers) throws Exception {
        http.securityMatcher("/api/v1/alerts/**");
        stateless(http, handlers);
        http.addFilterBefore(new ApiKeyAuthFilter(apiKeyService), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INGEST"));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain userChain(HttpSecurity http, JwtService jwtService,
                                         JsonSecurityHandlers handlers) throws Exception {
        stateless(http, handlers);
        http.addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/api/v1/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll());
        return http.build();
    }

    /** Shared settings: no sessions, no CSRF (no cookies => nothing to forge), JSON errors. */
    private static void stateless(HttpSecurity http, JsonSecurityHandlers handlers) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(handlers.entryPoint())
                .accessDeniedHandler(handlers.accessDenied()));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
