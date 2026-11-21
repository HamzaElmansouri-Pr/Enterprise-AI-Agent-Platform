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
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing bootstrap input: " + key);
        return value;
    }
    @Bean ApplicationRunner bootstrapIdentity(Environment env, DataSource source) {
        return args -> {
            try {
                new IdentityBootstrap(source).execute(UUID.fromString(required(env, "BOOTSTRAP_TENANT_ID")),
                        required(env, "BOOTSTRAP_TENANT_NAME"), UUID.fromString(required(env, "BOOTSTRAP_USER_ID")),
                        new Email(required(env, "BOOTSTRAP_EMAIL")), required(env, "BOOTSTRAP_PASSWORD"));
            } catch (RuntimeException error) {
                throw new IllegalStateException("Identity bootstrap failed; check operator configuration and inputs");
            }
        };
    }
}
