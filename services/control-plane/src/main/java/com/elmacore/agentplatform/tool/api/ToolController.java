package com.elmacore.agentplatform.tool.api;

import com.elmacore.agentplatform.tool.application.ToolGateway;
import com.elmacore.agentplatform.tool.domain.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;
import java.time.Instant;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/tenants/{tenantId}")
public class ToolController {
    private final ToolGateway gateway;private final ObjectMapper mapper;
    public ToolController(ToolGateway gateway,ObjectMapper mapper){this.gateway=gateway;this.mapper=mapper;}
    public record Response(UUID id,UUID tenantId,UUID agentId,String toolName,int toolVersion,ToolPayload.Arguments arguments,String rationale,String payloadDigest,Proposal.Status status,UUID createdBy,Instant createdAt,Proposal.Decision decision,UUID executedBy,UUID taskId,String failureCode,Instant startedAt,Instant completedAt) {
        static Response from(Proposal p){return new Response(p.id(),p.tenantId(),p.agentId(),p.payload().toolName(),p.toolVersion(),p.payload().arguments(),p.payload().rationale(),p.payloadDigest(),p.status(),p.createdBy(),p.createdAt(),p.decision(),p.executedBy(),p.taskId(),p.failureCode(),p.startedAt(),p.completedAt());}
        @Override public String toString(){return "ToolProposalResponse[redacted]";}
    }
    public record Intent(String intent){@Override public String toString(){return "ToolIntent[redacted]";}}
    public record Approval(String expectedPayloadDigest){}
    public record Rejection(String expectedPayloadDigest,String reason){@Override public String toString(){return "ToolRejection[redacted]";}}
    private <T>T call(java.util.function.Supplier<T> work) {
        try{return work.get();}
        catch(org.springframework.dao.CannotAcquireLockException busy){throw new ToolFailure("proposal_busy",409);}
        catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException failure){
            // SQL exception details can contain private failed-row values under DEBUG.
            org.slf4j.LoggerFactory.getLogger(ToolController.class).error("tool_persistence_failed correlation_id={}",org.slf4j.MDC.get("correlation_id"));
            throw new ToolFailure("tool_persistence_failure",500);
        }
    }
    private UUID user(Jwt jwt){return UUID.fromString(jwt.getSubject());}
    private UUID correlation(){return UUID.fromString(org.slf4j.MDC.get("correlation_id"));}
    private void query(HttpServletRequest request,String... allowed){
        var keys=Set.of(allowed);for(var entry:request.getParameterMap().entrySet())if(!keys.contains(entry.getKey()) || entry.getValue().length!=1)throw new IllegalArgumentException("Invalid query");
    }
    private JsonNode body(HttpServletRequest request)throws IOException {
        query(request);byte[] bytes=request.getInputStream().readNBytes(16385);if(bytes.length>16384)throw new IllegalArgumentException("Tool request too large");
        try{var json=mapper.readTree(bytes);if(json==null || !json.isObject())throw new IllegalArgumentException("Invalid tool request");return json;}
        catch(com.fasterxml.jackson.core.JacksonException error){throw new IllegalArgumentException("Invalid tool request");}
    }
    private <T>T convert(JsonNode json,Class<T> type){try{return mapper.treeToValue(json,type);}catch(com.fasterxml.jackson.core.JacksonException error){throw new IllegalArgumentException("Invalid tool request");}}
    private static void strings(JsonNode node,String... names){for(String name:names)if(!node.path(name).isTextual())throw new IllegalArgumentException("Invalid tool fields");}
    private ResponseEntity<Response> response(Proposal p,int code){return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(Response.from(p));}
    @PostMapping(value="/agents/{agentId}/tool-proposals",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Response> submit(@PathVariable UUID tenantId,@PathVariable UUID agentId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException {
        var json=body(request);strings(json,"toolName","rationale");var args=json.path("arguments");if(!args.isObject())throw new IllegalArgumentException("Arguments required");strings(args,"title","description","priority");
        return response(call(()->gateway.submit(user(jwt),tenantId,agentId,convert(json,ToolPayload.class),correlation())),201);
    }
    @PostMapping(value="/agents/{agentId}/tool-proposals/generate",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Response> generate(@PathVariable UUID tenantId,@PathVariable UUID agentId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException {
        var json=body(request);strings(json,"intent");return response(call(()->gateway.generate(user(jwt),tenantId,agentId,convert(json,Intent.class).intent(),correlation())),201);
    }
    @GetMapping("/tool-proposals")
    public ResponseEntity<ToolGateway.Page<Response>> list(@PathVariable UUID tenantId,@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest request){
        query(request,"page","size");var rows=call(()->gateway.list(user(jwt),tenantId,page,size));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ToolGateway.Page<>(rows.items().stream().map(Response::from).toList(),page,size,rows.hasNext()));
    }
    @GetMapping("/tool-proposals/{proposalId}")
    public ResponseEntity<Response> get(@PathVariable UUID tenantId,@PathVariable UUID proposalId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request){query(request);return response(call(()->gateway.get(user(jwt),tenantId,proposalId)),200);}
    @PostMapping(value="/tool-proposals/{proposalId}/approve",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Response> approve(@PathVariable UUID tenantId,@PathVariable UUID proposalId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException {
        var json=body(request);strings(json,"expectedPayloadDigest");return response(call(()->gateway.decide(user(jwt),tenantId,proposalId,convert(json,Approval.class).expectedPayloadDigest(),true,null,correlation())),200);
    }
    @PostMapping(value="/tool-proposals/{proposalId}/reject",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Response> reject(@PathVariable UUID tenantId,@PathVariable UUID proposalId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException {
        var json=body(request);strings(json,"expectedPayloadDigest","reason");var rejection=convert(json,Rejection.class);return response(call(()->gateway.decide(user(jwt),tenantId,proposalId,rejection.expectedPayloadDigest(),false,rejection.reason(),correlation())),200);
    }
    @PostMapping("/tool-proposals/{proposalId}/execute")
    public ResponseEntity<Response> execute(@PathVariable UUID tenantId,@PathVariable UUID proposalId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException {
        query(request);if(request.getInputStream().read()!=-1)throw new IllegalArgumentException("Execute accepts no body");return response(call(()->gateway.execute(user(jwt),tenantId,proposalId,correlation())),200);
    }
    @GetMapping("/admissions/followup-tasks")
    public ResponseEntity<ToolGateway.Page<FollowupTask>> tasks(@PathVariable UUID tenantId,@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest request){query(request,"page","size");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(call(()->gateway.tasks(user(jwt),tenantId,page,size)));}
}
