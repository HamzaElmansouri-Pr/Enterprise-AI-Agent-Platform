package com.elmacore.agentplatform.identity.api;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;
import com.elmacore.agentplatform.identity.application.IdentityFailure;
import static com.elmacore.agentplatform.identity.application.IdentityFailure.Reason.LIMITED;

/** Bounded single-process development protection; deliberately does not trust forwarded headers. */
@Component
public final class LoginProtection {
    private final Clock clock;
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Semaphore hashing = new Semaphore(2);
    private long window;
    private int total;
    public LoginProtection() { this(Clock.systemUTC()); }
    public LoginProtection(Clock clock) { this.clock = clock; }
    public synchronized void enter(String address) {
        long current = clock.millis() / 60000;
        if (current != window) { window = current; attempts.clear(); total = 0; }
        int count = attempts.getOrDefault(address, 0);
        if (total >= 20 || count >= 5 || (attempts.size() >= 1024 && count == 0)) throw new IdentityFailure(LIMITED);
        total++; attempts.put(address, count + 1);
        if (!hashing.tryAcquire()) throw new IdentityFailure(LIMITED);
    }
    public void leave() { hashing.release(); }
}
