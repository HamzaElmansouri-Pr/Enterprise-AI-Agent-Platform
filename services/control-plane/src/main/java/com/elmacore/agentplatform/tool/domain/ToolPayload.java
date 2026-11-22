package com.elmacore.agentplatform.tool.domain;

import java.nio.charset.StandardCharsets;

public record ToolPayload(String toolName,Arguments arguments,String rationale) {
    public static final String NAME="admissions.create_followup_task";
    public static final int VERSION=1;
    public enum Priority {LOW,NORMAL,HIGH}
    public record Arguments(String title,String description,Priority priority) {
        public Arguments {
            text(title,120);text(description,1000);java.util.Objects.requireNonNull(priority);
            var url=java.util.regex.Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://|\\b(?:mailto|file|data):|\\bwww\\.");
            if(url.matcher(title).find() || url.matcher(description).find())throw new IllegalArgumentException("URLs are not tool arguments");
            if(title.codePoints().anyMatch(c->Character.isISOControl(c) || (c>=0x202A&&c<=0x202E) || (c>=0x2066&&c<=0x2069) || c==0x2028 || c==0x2029))throw new IllegalArgumentException("Unsafe title");
        }
        @Override public String toString(){return "ToolArguments[redacted]";}
    }
    public ToolPayload {
        if(!NAME.equals(toolName))throw new IllegalArgumentException("Unknown tool");
        java.util.Objects.requireNonNull(arguments);text(rationale,500);
    }
    public static void text(String text,int max) {
        if(text==null || text.indexOf(0)>=0 || text.codePointCount(0,text.length())>max
            || text.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c))
            || !StandardCharsets.UTF_8.newEncoder().canEncode(text))throw new IllegalArgumentException("Invalid tool text");
    }
    @Override public String toString(){return "ToolPayload[redacted]";}
}
