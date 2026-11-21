package com.elmacore.agentplatform.identity.infrastructure;

import com.elmacore.agentplatform.identity.domain.Email;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@Profile("identity-bootstrap")
public class BootstrapConfiguration {
    private static String required(Environment env, String key) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank()) throw new IdentityBootstrap.InputFailure("Missing required input: " + key);
        return value;
    }
    private static UUID uuid(Environment env, String key) {
        String value = required(env, key);
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException error) { throw new IdentityBootstrap.InputFailure(key + " must be a UUID"); }
    }
    private static Email email(Environment env) {
        String value = required(env, "BOOTSTRAP_EMAIL");
        try { return new Email(value); }
        catch (IllegalArgumentException error) { throw new IdentityBootstrap.InputFailure("BOOTSTRAP_EMAIL must be a valid email"); }
    }
    @Bean ApplicationRunner bootstrapIdentity(Environment env, DataSource source) {
        return args -> {
            try {
                new IdentityBootstrap(source).execute(uuid(env, "BOOTSTRAP_TENANT_ID"),
                        required(env, "BOOTSTRAP_TENANT_NAME"), uuid(env, "BOOTSTRAP_USER_ID"),
                        email(env), required(env, "BOOTSTRAP_PASSWORD"));
            } catch (IdentityBootstrap.InputFailure error) {
                throw new IllegalStateException("Identity bootstrap failed: " + error.getMessage());
            } catch (RuntimeException error) {
                throw new IllegalStateException("Identity bootstrap failed; check operator configuration and inputs");
            }
        };
    }
}
