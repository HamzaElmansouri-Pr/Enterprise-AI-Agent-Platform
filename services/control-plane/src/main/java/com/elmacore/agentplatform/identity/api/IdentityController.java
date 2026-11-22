package com.elmacore.agentplatform.identity.api;

import com.elmacore.agentplatform.identity.application.Login;
import com.elmacore.agentplatform.identity.application.TenantContext;
import com.elmacore.agentplatform.identity.domain.Email;
import com.elmacore.agentplatform.identity.domain.TenantAccess;
import com.elmacore.agentplatform.identity.application.AccessTokens;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.io.IOException;
import java.util.UUID;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public class IdentityController {
    private final Login login;
    private final TenantContext context;
    private final LoginProtection protection;
    private final ObjectMapper mapper;
    private final Validator validator;
    public IdentityController(Login login, TenantContext context, LoginProtection protection,
            ObjectMapper mapper, Validator validator) {
        this.login = login; this.context = context; this.protection = protection;
        this.mapper = mapper; this.validator = validator;
    }
    public record LoginRequest(@NotBlank @Size(max = 254) String email,
                               @NotBlank @Size(max = 128) String password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
        @Override public String toString() { return "TokenResponse[redacted]"; }
    }
    @PostMapping(value = "/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponse> login(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw new IllegalArgumentException("Query parameters forbidden");
        protection.attempt();
        // Bound bytes before JSON deserialization, including chunked requests.
        byte[] body = request.getInputStream().readNBytes(4097);
        if (body.length > 4096) throw new IllegalArgumentException("Request too large");
        var json = mapper.readTree(body);
        if (json == null || !json.isObject() || json.size() != 2 || !json.path("email").isTextual()
                || !json.path("password").isTextual()) throw new IllegalArgumentException("Invalid credentials input");
        var credentials = mapper.treeToValue(json, LoginRequest.class);
        if (credentials == null || !validator.validate(credentials).isEmpty()) throw new IllegalArgumentException("Invalid credentials input");
        var email = new Email(credentials.email());
        try (var permit = protection.enter(email)) {
            String token = login.execute(email, credentials.password());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                    .body(new TokenResponse(token, "Bearer", AccessTokens.LIFETIME_SECONDS));
        }
    }

    @GetMapping("/tenants/{tenantId}/context")
    public TenantAccess tenant(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) {
        return context.execute(UUID.fromString(jwt.getSubject()), tenantId);
    }
}
