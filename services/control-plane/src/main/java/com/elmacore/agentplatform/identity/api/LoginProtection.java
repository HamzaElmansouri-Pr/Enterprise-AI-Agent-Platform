package com.elmacore.agentplatform.identity.api;

import com.elmacore.agentplatform.identity.application.IdentityFailure;
import com.elmacore.agentplatform.identity.domain.Email;
import com.elmacore.agentplatform.operations.OperationalSignals;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import static com.elmacore.agentplatform.identity.application.IdentityFailure.Reason.LIMITED;

/** Process-local resource admission. Nginx owns TCP-peer throttling in the pilot. */
@Component
public final class LoginProtection {
    private final Clock clock;
    private final Set<String> active = new HashSet<>();
    private final byte[] pepper = new byte[32];
    private long window = Long.MIN_VALUE;
    private int total;
    public LoginProtection() { this(Clock.systemUTC()); }
    public LoginProtection(Clock clock) { this.clock = clock; new SecureRandom().nextBytes(pepper); }

    /** All syntactically routable JSON login attempts count, including malformed bodies and busy retries. */
    public synchronized void attempt() {
        long current = Math.floorDiv(clock.millis(), 60000);
        if (current != window) { window = current; total = 0; }
        if (total >= 20) limited(OperationalSignals.Event.LOGIN_GLOBAL_LIMITED);
        total++;
    }

    /** No queue and no account cooldown: concurrent work for one normalized identity is redundant. */
    public synchronized Permit enter(Email email) {
        String key = key(email.value());
        if (active.contains(key)) limited(OperationalSignals.Event.LOGIN_IDENTITY_BUSY);
        if (active.size() >= 2) limited(OperationalSignals.Event.LOGIN_HASH_BUSY);
        active.add(key);
        return new Permit(key);
    }
    private String key(String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException("Identity admission unavailable"); }
    }
    private static void limited(OperationalSignals.Event event) {
        OperationalSignals.record(event, 1);
        throw new IdentityFailure(LIMITED);
    }
    public final class Permit implements AutoCloseable {
        private String key;
        private Permit(String key) { this.key = key; }
        @Override public void close() {
            synchronized (LoginProtection.this) {
                if (key != null) { active.remove(key); key = null; }
            }
        }
    }
}
