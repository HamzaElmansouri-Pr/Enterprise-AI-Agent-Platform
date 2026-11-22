package com.elmacore.agentplatform.knowledge.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class Upload {
    public static final int MAX_BYTES=2*1024*1024;
    private final String filename, contentType, sha256;
    private final byte[] bytes;
    public Upload(String filename,String contentType,byte[] bytes) {
        if(filename==null || contentType==null || bytes==null || bytes.length<1 || bytes.length>MAX_BYTES) throw new IllegalArgumentException("Invalid upload");
        filename=filename.strip();
        if(!StandardCharsets.UTF_8.newEncoder().canEncode(filename) || filename.isBlank() || filename.equals(".") || filename.equals("..") || filename.codePointCount(0,filename.length())>160
                || filename.codePoints().anyMatch(c->Character.isISOControl(c) || c=='/' || c=='\\' || c==0x2028 || c==0x2029 || (c>=0x202A&&c<=0x202E) || (c>=0x2066&&c<=0x2069))) throw new IllegalArgumentException("Invalid filename");
        String lower=filename.toLowerCase(Locale.ROOT);
        if(contentType.equals("text/plain") && lower.endsWith(".txt")) { utf8(bytes); }
        else if(contentType.equals("application/pdf") && lower.endsWith(".pdf")) {
            if(bytes.length<5 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) throw new IllegalArgumentException("Invalid PDF signature");
        } else throw new IllegalArgumentException("Unsupported document type");
        this.filename=filename; this.contentType=contentType; this.bytes=bytes.clone();
        try { sha256=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static String utf8(byte[] bytes) {
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(text.indexOf(0)>=0 || text.codePointCount(0,text.length())>120000) throw new IllegalArgumentException("Invalid text limits");
            return text;
        } catch(CharacterCodingException error) { throw new IllegalArgumentException("Invalid UTF-8"); }
    }
    public String filename() { return filename; }
    public String contentType() { return contentType; }
    public String sha256() { return sha256; }
    public byte[] bytes() { return bytes.clone(); }
    public int size() { return bytes.length; }
    @Override public String toString() { return "Upload[redacted]"; }
}
