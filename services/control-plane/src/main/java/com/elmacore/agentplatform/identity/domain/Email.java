package com.elmacore.agentplatform.identity.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public record Email(String value) {
    private static final Pattern VALID = Pattern.compile("[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*\\.[a-z]{2,63}");
    public Email {
        if (value == null || value.length() > 254) throw new IllegalArgumentException("Invalid email");
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(value).matches() || value.indexOf('@') > 64) throw new IllegalArgumentException("Invalid email");
    }
}
