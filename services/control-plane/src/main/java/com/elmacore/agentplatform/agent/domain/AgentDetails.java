package com.elmacore.agentplatform.agent.domain;

import java.util.regex.Pattern;

public record AgentDetails(String name, String description, String systemInstructions) {
    private static final Pattern EDGE_SPACE = Pattern.compile("^\\p{IsWhite_Space}+|\\p{IsWhite_Space}+$");
    private static final Pattern BLANK = Pattern.compile("\\p{IsWhite_Space}*");
    public AgentDetails {
        text(name);
        name = EDGE_SPACE.matcher(name).replaceAll("");
        if (length(name) < 1 || length(name) > 120 || name.codePoints().anyMatch(AgentDetails::unsafeName)) {
            throw new IllegalArgumentException("Invalid agent name");
        }
        if (description != null) {
            text(description);
            if (length(description) > 1000) throw new IllegalArgumentException("Invalid agent description");
        }
        text(systemInstructions);
        if (length(systemInstructions) < 1 || length(systemInstructions) > 10000 || BLANK.matcher(systemInstructions).matches()) {
            throw new IllegalArgumentException("Invalid system instructions");
        }
    }
    private static int length(String value) { return value.codePointCount(0, value.length()); }
    private static boolean unsafeName(int cp) {
        return Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029
                || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069);
    }
    private static void text(String value) {
        if (value == null || value.indexOf(0) >= 0) throw new IllegalArgumentException("Invalid configuration text");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("Invalid configuration Unicode");
                }
            } else if (Character.isLowSurrogate(ch)) throw new IllegalArgumentException("Invalid configuration Unicode");
        }
    }
    @Override public String toString() { return "AgentDetails[redacted]"; }
}
