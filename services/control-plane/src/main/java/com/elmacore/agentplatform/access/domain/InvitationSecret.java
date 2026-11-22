package com.elmacore.agentplatform.access.domain;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
public final class InvitationSecret {
    private static final SecureRandom RANDOM=new SecureRandom();
    private InvitationSecret(){}
    public static String generate(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public static byte[] hash(String token){
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw new AccessFailure("invitation_unavailable",410);
        byte[] decoded;try{decoded=Base64.getUrlDecoder().decode(token);}catch(IllegalArgumentException error){throw new AccessFailure("invitation_unavailable",410);}
        String canonical=Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
        if(decoded.length!=32 || !MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),canonical.getBytes(StandardCharsets.US_ASCII)))throw new AccessFailure("invitation_unavailable",410);
        try{return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("SHA-256 unavailable");}
    }
    public static void newPassword(String password){if(password==null || password.isBlank() || password.length()<16 || password.length()>128 || password.indexOf(0)>=0 || !StandardCharsets.UTF_8.newEncoder().canEncode(password))throw new IllegalArgumentException("Password must contain 16-128 characters");}
    public static String invitedRole(String role){if(!"ADMIN".equals(role)&&!"MEMBER".equals(role))throw new IllegalArgumentException("Unsupported invited role");return role;}
}
