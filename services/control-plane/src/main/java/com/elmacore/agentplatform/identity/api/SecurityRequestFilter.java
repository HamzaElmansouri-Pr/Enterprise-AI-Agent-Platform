package com.elmacore.agentplatform.identity.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

public final class SecurityRequestFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityRequestFilter.class);
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String correlation = UUID.randomUUID().toString();
        long started = System.nanoTime();
        response.setHeader("X-Request-ID", correlation);
        try (var ignored = MDC.putCloseable("correlation_id", correlation)) {
            try { chain.doFilter(request, response); }
            catch (DataAccessException error) {
                LOG.error("identity_database_failure correlation_id={}", correlation);
                response.setStatus(500); response.setContentType("application/json");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"code\":\"internal_error\"}");
            } finally {
                LOG.info("request_completed correlation_id={} status={} duration_ms={}",
                        correlation, response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            }
        }
    }
}
