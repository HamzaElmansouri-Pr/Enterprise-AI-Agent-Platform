package com.elmacore.agentplatform.identity.application;

public interface Passwords {
    boolean matches(String password, String hash);
    String encode(String password);
    String dummyHash();
}
