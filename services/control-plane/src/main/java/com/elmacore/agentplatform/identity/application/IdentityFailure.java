package com.elmacore.agentplatform.identity.application;

public class IdentityFailure extends RuntimeException {
    public enum Reason { UNAUTHENTICATED, FORBIDDEN, UNAVAILABLE, LIMITED }
    private final Reason reason;
    public IdentityFailure(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}
