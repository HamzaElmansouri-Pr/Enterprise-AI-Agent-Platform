package com.elmacore.agentplatform;

import com.elmacore.agentplatform.identity.api.LoginProtection;
import com.elmacore.agentplatform.identity.application.*;
import com.elmacore.agentplatform.identity.domain.Email;
import com.elmacore.agentplatform.identity.infrastructure.JwtTokens;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.BadJwtException;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdentityUnitTest {
    @Test void emailNormalizationIsConsistentAndInvalidIdentifiersFail() {
        assertThat(new Email(" User@Example.COM ").value()).isEqualTo("user@example.com");
        for (String email : new String[]{"", "  ", "missing-domain", "x@y", ".a@b.com", "a..b@c.com", "a@b..com", "a@-b.com", "x".repeat(65) + "@example.com", "x@a.com\nextra", "x".repeat(255)}) {
            assertThrows(IllegalArgumentException.class, () -> new Email(email));
        }
    }
    @Test void missingSigningKeysDisableIssuanceAndValidationWithoutFallback() {
        var tokens = new JwtTokens(new MockEnvironment(), new DefaultResourceLoader());
        assertThat(tokens.available()).isFalse();
        assertThat(assertThrows(IdentityFailure.class, () -> tokens.issue(UUID.randomUUID())).reason())
                .isEqualTo(IdentityFailure.Reason.UNAVAILABLE);
        assertThrows(BadJwtException.class, () -> tokens.decode("anything"));
    }
    @Test void partialSigningConfigurationFailsClosed() {
        var env = new MockEnvironment().withProperty("AUTH_PUBLIC_KEY", "file:/missing-test-public.pem");
        assertThrows(IllegalStateException.class, () -> new JwtTokens(env, new DefaultResourceLoader()));
    }
    @Test void localLimiterBoundsGlobalAttemptsAndHashingConcurrency() {
        var limiter = new LoginProtection(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        var one = limiter.enter(new Email("one@example.com"));
        var two = limiter.enter(new Email("two@example.com"));
        assertThrows(IdentityFailure.class, () -> limiter.enter(new Email("three@example.com")));
        one.close(); two.close();
        for (int i = 0; i < 20; i++) limiter.attempt();
        assertThrows(IdentityFailure.class, limiter::attempt);
    }
}
