package com.elmacore.agentplatform.identity.infrastructure;

import com.elmacore.agentplatform.identity.application.Passwords;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import java.util.UUID;

public final class ArgonPasswords implements Passwords {
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    private final String dummy = encoder.encode(UUID.randomUUID().toString());
    @Override public String encode(String password) { return encoder.encode(password); }
    @Override public boolean matches(String password, String hash) { return encoder.matches(password, hash); }
    @Override public String dummyHash() { return dummy; }
}
