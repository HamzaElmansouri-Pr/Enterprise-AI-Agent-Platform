package com.elmacore.agentplatform.execution.domain;

import java.nio.charset.StandardCharsets;

public record RunInput(String message,Integer topK) {
    public RunInput {
        if(message==null || message.indexOf(0)>=0 || message.codePointCount(0,message.length())>1000
                || message.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c))
                || !StandardCharsets.UTF_8.newEncoder().canEncode(message)) throw new IllegalArgumentException("Invalid message");
        topK=topK==null?5:topK;
        if(topK<1 || topK>6) throw new IllegalArgumentException("Invalid topK");
    }
    @Override public String toString(){return "RunInput[redacted]";}
}
