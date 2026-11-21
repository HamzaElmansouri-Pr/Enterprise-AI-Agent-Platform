package com.elmacore.agentplatform.identity.application;

import java.util.UUID;

public interface AccessTokens {
    long LIFETIME_SECONDS = 900;
    boolean available();
    String issue(UUID userId);
}
