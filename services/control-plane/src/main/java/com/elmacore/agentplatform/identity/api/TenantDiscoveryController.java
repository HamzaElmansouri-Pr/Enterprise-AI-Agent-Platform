package com.elmacore.agentplatform.identity.api;

import com.elmacore.agentplatform.identity.application.TenantDiscovery;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Set;
import java.util.UUID;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TenantDiscoveryController {
    private final TenantDiscovery discovery;
    public TenantDiscoveryController(TenantDiscovery discovery) { this.discovery = discovery; }
    @GetMapping("/api/v1/me/tenants")
    public ResponseEntity<TenantDiscovery.Page> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            HttpServletRequest request) {
        if (request.getParameterMap().entrySet().stream().anyMatch(entry ->
                !Set.of("page", "size").contains(entry.getKey()) || entry.getValue().length != 1)) {
            throw new IllegalArgumentException("Only single page and size parameters are supported");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .body(discovery.execute(UUID.fromString(jwt.getSubject()), page, size));
    }
}
