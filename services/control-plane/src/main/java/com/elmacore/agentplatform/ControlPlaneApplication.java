package com.elmacore.agentplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// No generated development user; authentication uses the explicit identity boundary.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ControlPlaneApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(ControlPlaneApplication.class, args);
        // Migration mode is a one-shot process with separate credentials and no HTTP server.
        if (context.getEnvironment().matchesProfiles("migration", "identity-bootstrap")) {
            context.close();
        }
    }
}
