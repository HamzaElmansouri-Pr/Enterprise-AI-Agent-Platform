package com.elmacore.agentplatform.identity.infrastructure;

import com.elmacore.agentplatform.identity.application.AccessTokens;
import com.elmacore.agentplatform.identity.application.IdentityFailure;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import java.time.Duration;
import java.time.Instant;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

public final class JwtTokens implements AccessTokens, JwtDecoder {
    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final String issuer;
    private final String audience;
    private final String keyId;
    public JwtTokens(Environment env, ResourceLoader resources) {
        String privatePath = env.getProperty("AUTH_PRIVATE_KEY", "");
        String publicPath = env.getProperty("AUTH_PUBLIC_KEY", "");
        issuer = env.getProperty("AUTH_ISSUER", "");
        audience = env.getProperty("AUTH_AUDIENCE", "");
        keyId = env.getProperty("AUTH_KEY_ID", "");
        // Health can run without authentication configuration; no tokens can be issued or accepted.
        if (privatePath.isBlank() && publicPath.isBlank()) { encoder = null; decoder = null; return; }
        if (privatePath.isBlank() || publicPath.isBlank() || issuer.isBlank() || audience.isBlank() || keyId.isBlank()) {
            throw new IllegalStateException("Incomplete authentication key configuration");
        }
        try (var privateStream = resources.getResource(privatePath).getInputStream();
             var publicStream = resources.getResource(publicPath).getInputStream()) {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(privateStream);
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(publicStream);
            if (privateKey == null || publicKey == null || publicKey.getModulus().bitLength() < 2048
                    || !privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalArgumentException("Invalid RSA key pair");
            }
            var key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
            encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
            decoder = NimbusJwtDecoder.withPublicKey(publicKey).signatureAlgorithm(SignatureAlgorithm.RS256).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(issuer), this::claims));
        } catch (Exception error) {
            // Do not include resource contents or provider exception messages in startup diagnostics.
            throw new IllegalStateException("Authentication key configuration is invalid");
        }
    }
    private OAuth2TokenValidatorResult claims(Jwt jwt) {
        try {
            UUID user = UUID.fromString(jwt.getSubject());
            Instant issued = jwt.getIssuedAt();
            Instant expires = jwt.getExpiresAt();
            boolean valid = user.toString().equals(jwt.getSubject()) && jwt.getAudience().contains(audience)
                    && issued != null && expires != null && !issued.isAfter(Instant.now())
                    && expires.isAfter(issued) && Duration.between(issued, expires).getSeconds() <= LIFETIME_SECONDS
                    && jwt.getId() != null && !jwt.getId().isBlank()
                    && keyId.equals(jwt.getHeaders().get("kid"));
            if (valid) return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException ignored) { /* Missing or wrong claim types fail closed. */ }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token", null));
    }
    @Override public boolean available() { return encoder != null; }
    @Override public String issue(UUID userId) {
        if (!available()) throw new IdentityFailure(IdentityFailure.Reason.UNAVAILABLE);
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(java.util.List.of(audience))
                .subject(userId.toString()).issuedAt(now).expiresAt(now.plusSeconds(LIFETIME_SECONDS))
                .id(UUID.randomUUID().toString()).build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
    @Override public Jwt decode(String token) throws JwtException {
        if (decoder == null) throw new BadJwtException("Authentication unavailable");
        return decoder.decode(token);
    }
}
