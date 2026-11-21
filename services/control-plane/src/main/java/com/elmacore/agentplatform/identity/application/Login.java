package com.elmacore.agentplatform.identity.application;

import com.elmacore.agentplatform.identity.domain.Email;
import static com.elmacore.agentplatform.identity.application.IdentityFailure.Reason.UNAUTHENTICATED;

public final class Login {
    private final IdentityStore identities;
    private final Passwords passwords;
    private final AccessTokens tokens;
    public Login(IdentityStore identities, Passwords passwords, AccessTokens tokens) {
        this.identities = identities; this.passwords = passwords; this.tokens = tokens;
    }
    public String execute(Email email, String password) {
        if (!tokens.available()) throw new IdentityFailure(IdentityFailure.Reason.UNAVAILABLE);
        var found = identities.credentials(email);
        // Unknown and disabled accounts still incur password verification work.
        boolean matches = passwords.matches(password, found.map(IdentityStore.Credentials::passwordHash)
                .orElseGet(passwords::dummyHash));
        if (found.isEmpty() || !matches || !found.get().enabled()) throw new IdentityFailure(UNAUTHENTICATED);
        return tokens.issue(found.get().id());
    }
}
