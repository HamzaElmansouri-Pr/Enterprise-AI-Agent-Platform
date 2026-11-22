package com.elmacore.agentplatform.operations;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.actuate.endpoint.annotation.*;
import org.springframework.boot.health.contributor.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods=false)
@Profile("pilot")
public class PilotOperations {
    @Bean HealthIndicator pilotDatabaseHealthIndicator(DataSource source) {
        return () -> {
            try(var c=source.getConnection();var s=c.createStatement()) {
                s.setQueryTimeout(1);try(var r=s.executeQuery("SELECT 1")) {
                    return r.next()?Health.up().build():Health.down().build();
                }
            } catch(Exception ignored) { return Health.down().build(); }
        };
    }
    @Bean @Order(0) SecurityFilterChain operationsSecurity(HttpSecurity http,Environment env)throws Exception {
        byte[] secret=env.getRequiredProperty("OPERATIONS_SECRET").getBytes(StandardCharsets.UTF_8);
        return http.securityMatcher(request->request.getLocalPort()==9090)
            .authorizeHttpRequests(auth->auth
                .requestMatchers(request->"GET".equals(request.getMethod()) && Set.of("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").contains(request.getRequestURI())).permitAll()
                .requestMatchers(request->"GET".equals(request.getMethod()) && request.getRequestURI().equals("/actuator/pilot"))
                .access((authentication,context)->new AuthorizationDecision(MessageDigest.isEqual(secret,Objects.toString(context.getRequest().getHeader("X-Operations-Secret"),"").getBytes(StandardCharsets.UTF_8))))
                .anyRequest().denyAll())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c->c.disable()).build();
    }
    @Bean PilotEndpoint pilotEndpoint(Environment env) {return new PilotEndpoint(env.getRequiredProperty("EAAP_RELEASE_SHA"));}
    @Endpoint(id="pilot") public static final class PilotEndpoint {
        private final String revision;private final long started=System.nanoTime();
        PilotEndpoint(String revision){this.revision=revision;}
        @ReadOperation public Map<String,Object> read(){return Map.of("revision",revision,"uptimeSeconds",(System.nanoTime()-started)/1_000_000_000,"counters",OperationalSignals.snapshot());}
    }
}
