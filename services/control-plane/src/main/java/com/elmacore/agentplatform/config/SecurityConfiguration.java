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
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
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
        var paths = PathPatternRequestMatcher.withDefaults();
        var toolWrites = new OrRequestMatcher(
                paths.matcher(HttpMethod.POST,"/api/v1/tenants/*/agents/*/tool-proposals"),
                paths.matcher(HttpMethod.POST,"/api/v1/tenants/*/agents/*/tool-proposals/generate"),
                paths.matcher(HttpMethod.POST,"/api/v1/tenants/*/tool-proposals/*/approve"),
                paths.matcher(HttpMethod.POST,"/api/v1/tenants/*/tool-proposals/*/reject"),
                paths.matcher(HttpMethod.POST,"/api/v1/tenants/*/tool-proposals/*/execute"));
        var runWrites = paths.matcher(HttpMethod.POST, "/api/v1/tenants/*/agents/*/runs");
        var knowledgeWrites = new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, "/api/v1/tenants/*/knowledge/documents"),
                paths.matcher(HttpMethod.POST, "/api/v1/tenants/*/knowledge/documents/*/retry"),
                paths.matcher(HttpMethod.POST, "/api/v1/tenants/*/knowledge/documents/*/agents/*"),
                paths.matcher(HttpMethod.DELETE, "/api/v1/tenants/*/knowledge/documents/*/agents/*"),
                paths.matcher(HttpMethod.POST, "/api/v1/tenants/*/agents/*/knowledge/search"));
        var agentWrites = new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, "/api/v1/tenants/*/agents"),
                paths.matcher(HttpMethod.PUT, "/api/v1/tenants/*/agents/*"),
                paths.matcher(HttpMethod.POST, "/api/v1/tenants/*/agents/*/archive"));
        return http
                .addFilterBefore(new SecurityRequestFilter(), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/tenants/*/context").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/tenants/*/agents", "/api/v1/tenants/*/agents/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants/*/agents", "/api/v1/tenants/*/agents/*/archive").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/tenants/*/agents/*").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/tenants/*/knowledge/documents", "/api/v1/tenants/*/knowledge/documents/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants/*/knowledge/documents", "/api/v1/tenants/*/knowledge/documents/*/retry", "/api/v1/tenants/*/knowledge/documents/*/agents/*", "/api/v1/tenants/*/agents/*/knowledge/search").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/tenants/*/knowledge/documents/*/agents/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants/*/agents/*/runs").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/tenants/*/agents/*/runs/*").authenticated()
                        .requestMatchers(HttpMethod.POST,"/api/v1/tenants/*/agents/*/tool-proposals","/api/v1/tenants/*/agents/*/tool-proposals/generate","/api/v1/tenants/*/tool-proposals/*/approve","/api/v1/tenants/*/tool-proposals/*/reject","/api/v1/tenants/*/tool-proposals/*/execute").authenticated()
                        .requestMatchers(HttpMethod.GET,"/api/v1/tenants/*/tool-proposals","/api/v1/tenants/*/tool-proposals/*","/api/v1/tenants/*/admissions/followup-tasks").authenticated()
                        .anyRequest().denyAll())
                // Only approved stateless login/agent/knowledge writes are exempt: authentication uses bearer
                // Authorization headers, never cookies or sessions. All other routes retain CSRF.
                .csrf(csrf -> csrf.ignoringRequestMatchers(request -> "POST".equals(request.getMethod())
                        && (request.getContextPath() + "/api/v1/auth/login").equals(request.getRequestURI()), agentWrites, knowledgeWrites, runWrites, toolWrites))
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
