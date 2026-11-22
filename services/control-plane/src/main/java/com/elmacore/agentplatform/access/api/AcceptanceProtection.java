package com.elmacore.agentplatform.access.api;
import com.elmacore.agentplatform.identity.api.LoginProtection;
import org.springframework.stereotype.Component;
/** Dedicated bounded acceptance window, separate from login; same accepted 2-slot/5-peer/20-process policy. */
@Component
public final class AcceptanceProtection {
    private final LoginProtection protection=new LoginProtection();
    public void enter(String peer){protection.enter(peer);}public void leave(){protection.leave();}
}
