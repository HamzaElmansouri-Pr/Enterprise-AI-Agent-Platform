package com.elmacore.agentplatform.config;

import com.elmacore.agentplatform.identity.application.IdentityStore;
import com.elmacore.agentplatform.identity.api.SecurityRequestFilter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import com.elmacore.agentplatform.identity.infrastructure.JwtTokens;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import java.util.List;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    private static void error(jakarta.servlet.http.HttpServletResponse response, int status, String code)
            throws java.io.IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokens tokens, IdentityStore identities) throws Exception {
        return http
                .addFilterBefore(new SecurityRequestFilter(), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/tenants/*/context").authenticated()
                        .anyRequest().denyAll())
                // Only JSON login is excluded: no cookies/session credentials, query input rejected.
                // Tenant GET requires bearer Authorization; no state-changing bearer APIs exist yet.
                .csrf(csrf -> csrf.ignoringRequestMatchers(request -> "POST".equals(request.getMethod())
                        && (request.getContextPath() + "/api/v1/auth/login").equals(request.getRequestURI())))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(tokens).jwtAuthenticationConverter(token -> {
                    if (!identities.enabled(UUID.fromString(token.getSubject()))) {
                        throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
                    }
                    // Caller-supplied roles/scopes never become authorities.
                    return new JwtAuthenticationToken(token, List.of(), token.getSubject());
                })).authenticationEntryPoint((request, response, exception) -> error(response, 401, "unauthorized")))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            boolean tenant = request.getRequestURI().startsWith("/api/v1/tenants/");
                            error(response, tenant ? 401 : 403, tenant ? "unauthorized" : "access_denied");
                        })
                        .accessDeniedHandler((request, response, exception) -> error(response, 403, "access_denied")))
                .build();
    }
}
