package com.elmacore.agentplatform.conversation.api;
import com.elmacore.agentplatform.conversation.application.Conversations;
import com.elmacore.agentplatform.conversation.domain.ConversationRecords.*;
import com.elmacore.agentplatform.execution.domain.RunFailure;
import tools.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.util.*;
import java.io.IOException;
@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/tenants/{tenantId}/agents/{agentId}/conversations")
public class ConversationController {
 private final Conversations conversations;private final ObjectMapper mapper;
 public ConversationController(Conversations conversations,ObjectMapper mapper){this.conversations=conversations;this.mapper=mapper;}
 private UUID actor(Jwt jwt){return UUID.fromString(jwt.getSubject());}
 private void query(HttpServletRequest r,String...allowed){var names=Set.of(allowed);for(var e:r.getParameterMap().entrySet())if(!names.contains(e.getKey())||e.getValue().length!=1)throw new IllegalArgumentException("Invalid conversation query");}
 private JsonNode body(HttpServletRequest r,String...fields)throws IOException{query(r);byte[] bytes=r.getInputStream().readNBytes(16385);if(bytes.length>16384)throw new IllegalArgumentException("Conversation body too large");try{var json=mapper.readTree(bytes);if(json==null||!json.isObject())throw new IllegalArgumentException("Invalid conversation body");var names=Set.of(fields);var keys=json.propertyNames().iterator();while(keys.hasNext())if(!names.contains(keys.next()))throw new IllegalArgumentException("Invalid conversation field");for(var node:json)if(!node.isTextual())throw new IllegalArgumentException("Invalid conversation value");return json;}catch(tools.jackson.core.JacksonException e){throw new IllegalArgumentException("Invalid conversation JSON");}}
 private String required(JsonNode json,String field){if(!json.path(field).isTextual())throw new IllegalArgumentException("Missing conversation field");return json.path(field).asText();}
 private void empty(HttpServletRequest r)throws IOException{query(r);if(r.getInputStream().read()!=-1)throw new IllegalArgumentException("No body accepted");}
 private <T>ResponseEntity<T> result(int code,java.util.function.Supplier<T> work){try{return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(work.get());}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException e){throw new RunFailure("conversation_persistence_failure",500);}}
 @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<Conversation> create(@PathVariable UUID tenantId,@PathVariable UUID agentId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest r)throws IOException{var json=body(r,"title");return result(201,()->conversations.create(actor(jwt),tenantId,agentId,json.has("title")?required(json,"title"):null));}
 @GetMapping
 public ResponseEntity<Page<Conversation>> list(@PathVariable UUID tenantId,@PathVariable UUID agentId,@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest r){query(r,"page","size");return result(200,()->conversations.list(actor(jwt),tenantId,agentId,page,size));}
 @GetMapping("/{conversationId}")
 public ResponseEntity<Conversation> get(@PathVariable UUID tenantId,@PathVariable UUID agentId,@PathVariable UUID conversationId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest r){query(r);return result(200,()->conversations.get(actor(jwt),tenantId,agentId,conversationId));}
 @PutMapping(value="/{conversationId}",consumes=MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<Conversation> rename(@PathVariable UUID tenantId,@PathVariable UUID agentId,@PathVariable UUID conversationId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest r)throws IOException{var json=body(r,"title");return result(200,()->conversations.change(actor(jwt),tenantId,agentId,conversationId,required(json,"title"),false));}
 @PostMapping("/{conversationId}/close")
 public ResponseEntity<Conversation> close(@PathVariable UUID tenantId,@PathVariable UUID agentId,@PathVariable UUID conversationId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest r)throws IOException{empty(r);return result(200,()->conversations.change(actor(jwt),tenantId,agentId,conversationId,null,true));}
 @GetMapping("/{conversationId}/messages")
 public ResponseEntity<Page<Message>> messages(@PathVariable UUID tenantId,@PathVariable UUID agentId,@PathVariable UUID conversationId,@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,HttpServletRequest r){query(r,"page","size");return result(200,()->conversations.messages(actor(jwt),tenantId,agentId,conversationId,page,size));}
 @PostMapping(value="/{conversationId}/messages",consumes=MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<Turn> send(@PathVariable UUID tenantId,@PathVariable UUID agentId,@PathVariable UUID conversationId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest r)throws IOException{var json=body(r,"message","requestId");String message=required(json,"message");UUID request=UUID.fromString(required(json,"requestId"));return result(200,()->conversations.send(actor(jwt),tenantId,agentId,conversationId,request,message,UUID.fromString(org.slf4j.MDC.get("correlation_id"))));}
 @GetMapping("/{conversationId}/requests/{requestId}")
 public ResponseEntity<Turn> outcome(@PathVariable UUID tenantId,@PathVariable UUID agentId,@PathVariable UUID conversationId,@PathVariable UUID requestId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest r){query(r);return result(200,()->conversations.outcome(actor(jwt),tenantId,agentId,conversationId,requestId));}
}
