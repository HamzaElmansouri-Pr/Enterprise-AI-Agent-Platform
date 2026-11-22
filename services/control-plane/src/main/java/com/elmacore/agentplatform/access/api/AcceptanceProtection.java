package com.elmacore.agentplatform.access.api;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;
import com.elmacore.agentplatform.identity.application.IdentityFailure;
import static com.elmacore.agentplatform.identity.application.IdentityFailure.Reason.LIMITED;

/** Existing independent invitation peer budget; forwarding headers are never trusted. */
@Component
public final class AcceptanceProtection {
    private final Clock clock;
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Semaphore hashing = new Semaphore(2);
    private long window;
    private int total;
    public AcceptanceProtection() { this(Clock.systemUTC()); }
    public AcceptanceProtection(Clock clock) { this.clock = clock; }
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
