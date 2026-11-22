package com.elmacore.agentplatform.access.api;
import com.elmacore.agentplatform.access.application.TenantAccessManagement;
import com.elmacore.agentplatform.access.domain.AccessRecords.*;
import tools.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;
@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public class AccessController {
    private final TenantAccessManagement access;private final ObjectMapper mapper;private final AcceptanceProtection protection;
    public AccessController(TenantAccessManagement access,ObjectMapper mapper,AcceptanceProtection protection){this.access=access;this.mapper=mapper;this.protection=protection;}
    public record Invite(String email,String role){@Override public String toString(){return "Invite[redacted]";}}
    public record RoleChange(String role){}
    public record NewAccount(String token,String email,String password){@Override public String toString(){return "InvitationAcceptance[redacted]";}}
    public record ExistingAccount(String token){@Override public String toString(){return "InvitationAcceptance[redacted]";}}
    public record Changed(boolean completed){}
    private <T>T safe(java.util.function.Supplier<T> work){try{return work.get();}catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException error){throw new com.elmacore.agentplatform.access.domain.AccessFailure("access_persistence_failure",500);}}
    private UUID actor(Jwt jwt){return UUID.fromString(jwt.getSubject());}private UUID correlation(){return UUID.fromString(org.slf4j.MDC.get("correlation_id"));}
    private void query(HttpServletRequest request,String...allowed){var names=Set.of(allowed);for(var e:request.getParameterMap().entrySet())if(!names.contains(e.getKey())||e.getValue().length!=1)throw new IllegalArgumentException("Invalid query");}
    private <T>T body(HttpServletRequest request,Class<T> type,String...fields)throws IOException{
        query(request);byte[] bytes=request.getInputStream().readNBytes(4097);if(bytes.length>4096)throw new IllegalArgumentException("Access request too large");
        try{var json=mapper.readTree(bytes);if(json==null||!json.isObject()||json.size()!=fields.length)throw new IllegalArgumentException("Invalid access request");for(String field:fields)if(!json.path(field).isTextual())throw new IllegalArgumentException("Invalid access fields");return mapper.treeToValue(json,type);}
        catch(tools.jackson.core.JacksonException error){throw new IllegalArgumentException("Invalid access request");}
    }
    private void noBody(HttpServletRequest request)throws IOException{query(request);if(request.getInputStream().read()!=-1)throw new IllegalArgumentException("No body accepted");}
    private <T>ResponseEntity<T> response(T body,int code){return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(body);}
    @GetMapping("/tenants/{tenantId}/members")
    public ResponseEntity<Page<Member>> members(@PathVariable UUID tenantId,@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest request){query(request,"page","size");return safe(()->response(access.members(actor(jwt),tenantId,page,size),200));}
    @PutMapping(value="/tenants/{tenantId}/members/{userId}/role",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Changed> role(@PathVariable UUID tenantId,@PathVariable UUID userId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException{var input=body(request,RoleChange.class,"role");safe(()->{access.change(actor(jwt),tenantId,userId,input.role(),false,correlation());return null;});return response(new Changed(true),200);}
    @DeleteMapping("/tenants/{tenantId}/members/{userId}")
    public ResponseEntity<Changed> revoke(@PathVariable UUID tenantId,@PathVariable UUID userId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException{noBody(request);safe(()->{access.change(actor(jwt),tenantId,userId,null,true,correlation());return null;});return response(new Changed(true),200);}
    @GetMapping("/tenants/{tenantId}/invitations")
    public ResponseEntity<Page<Invitation>> invitations(@PathVariable UUID tenantId,@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest request){query(request,"page","size");return safe(()->response(access.invitations(actor(jwt),tenantId,page,size),200));}
    @PostMapping(value="/tenants/{tenantId}/invitations",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Issued> invite(@PathVariable UUID tenantId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException{var input=body(request,Invite.class,"email","role");return safe(()->response(access.invite(actor(jwt),tenantId,input.email(),input.role(),correlation()),201));}
    @PostMapping("/tenants/{tenantId}/invitations/{invitationId}/cancel")
    public ResponseEntity<Invitation> cancel(@PathVariable UUID tenantId,@PathVariable UUID invitationId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException{noBody(request);return safe(()->response(access.cancel(actor(jwt),tenantId,invitationId,correlation()),200));}
    @PostMapping(value="/invitations/accept-new",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Accepted> acceptNew(HttpServletRequest request)throws IOException{
        protection.enter(request.getRemoteAddr());try{var input=body(request,NewAccount.class,"token","email","password");return safe(()->response(access.acceptNew(input.token(),input.email(),input.password(),correlation()),201));}finally{protection.leave();}
    }
    @PostMapping(value="/invitations/accept-existing",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Accepted> acceptExisting(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException{
        protection.enter(request.getRemoteAddr());try{var input=body(request,ExistingAccount.class,"token");return safe(()->response(access.acceptExisting(input.token(),actor(jwt),correlation()),200));}finally{protection.leave();}
    }
}
