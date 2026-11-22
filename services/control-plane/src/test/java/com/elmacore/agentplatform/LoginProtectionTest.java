package com.elmacore.agentplatform;

import com.elmacore.agentplatform.identity.api.LoginProtection;
import com.elmacore.agentplatform.identity.application.IdentityFailure;
import com.elmacore.agentplatform.identity.domain.Email;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LoginProtectionTest {
    @Test void normalizedIdentityCannotMultiplyConcurrentHashSlots() {
        var guard = new LoginProtection();
        try (var first = guard.enter(new Email(" User@Example.COM "))) {
            assertThatThrownBy(() -> guard.enter(new Email("user@example.com"))).isInstanceOf(IdentityFailure.class);
            try (var other = guard.enter(new Email("other@example.com"))) {
                assertThatThrownBy(() -> guard.enter(new Email("third@example.com"))).isInstanceOf(IdentityFailure.class);
            }
        }
        // A wrong-password attempt cannot create an account cooldown after verification finishes.
        try (var next = guard.enter(new Email("user@example.com"))) { assertThat(next).isNotNull(); }
    }
    @Test void permitsAreIdempotentAndExceptionalExitReleasesIdentity() {
        var guard = new LoginProtection();
        var first = guard.enter(new Email("one@example.com"));
        first.close(); first.close();
        try (var second = guard.enter(new Email("two@example.com")); var third = guard.enter(new Email("three@example.com"))) {
            assertThatThrownBy(() -> guard.enter(new Email("four@example.com"))).isInstanceOf(IdentityFailure.class);
        }
        assertThatThrownBy(() -> {try (var slot = guard.enter(new Email("one@example.com"))) {throw new IllegalArgumentException();}}).isInstanceOf(IllegalArgumentException.class);
        guard.enter(new Email("one@example.com")).close();
    }
    @Test void fixedGlobalWindowCountsFailuresAndCannotBeResetBySuccess() {
        var now = new AtomicLong(60000);
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        var guard = new LoginProtection(clock);
        for (int n=0;n<20;n++) {guard.attempt(); guard.enter(new Email("one@example.com")).close();}
        assertThatThrownBy(guard::attempt).isInstanceOf(IdentityFailure.class);
        now.set(119999); assertThatThrownBy(guard::attempt).isInstanceOf(IdentityFailure.class);
        now.set(120000); guard.attempt();
    }
    @Test void globalAdmissionIsAtomicUnderContention() throws Exception {
        var guard = new LoginProtection(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        var accepted = new AtomicInteger();
        try (var threads = Executors.newFixedThreadPool(12)) {
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int n=0;n<100;n++) jobs.add(threads.submit(() -> {
                try {guard.attempt();accepted.incrementAndGet();} catch (IdentityFailure expected) { }
            }));
            for (var job:jobs) job.get(5,TimeUnit.SECONDS);
        }
        assertThat(accepted.get()).isEqualTo(20);
    }
    @Test void freshProcessBudgetHasNoDurableLockout() {
        var old = new LoginProtection();
        for (int n=0;n<20;n++) old.attempt();
        assertThatThrownBy(old::attempt).isInstanceOf(IdentityFailure.class);
        new LoginProtection().attempt();
    }
}
