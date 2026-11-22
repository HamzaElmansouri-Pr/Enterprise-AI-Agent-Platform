package com.elmacore.agentplatform.knowledge.api;

import com.elmacore.agentplatform.knowledge.application.*;
import com.elmacore.agentplatform.knowledge.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import java.io.IOException;
import java.util.*;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/tenants/{tenantId}")
public class KnowledgeController {
    private final Knowledge knowledge;private final ObjectMapper mapper;
    public KnowledgeController(Knowledge knowledge,ObjectMapper mapper) {this.knowledge=knowledge;this.mapper=mapper;}
    @PostMapping(value="/knowledge/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KnowledgeDocument> upload(@PathVariable UUID tenantId,@AuthenticationPrincipal Jwt jwt,
            @RequestPart MultipartFile file,@RequestParam(required=false) UUID agentId,MultipartHttpServletRequest request)throws Exception {
        if(request.getMultiFileMap().size()!=1 || request.getFiles("file").size()!=1
                || request.getParameterMap().entrySet().stream().anyMatch(e->!e.getKey().equals("agentId") || e.getValue().length!=1)) throw new IllegalArgumentException("Invalid multipart fields");
        byte[] bytes;try(var stream=file.getInputStream()) {bytes=stream.readNBytes(Upload.MAX_BYTES+1);}
        var source=new Upload(file.getOriginalFilename(),file.getContentType(),bytes);
        return ResponseEntity.accepted().body(knowledge.upload(UUID.fromString(jwt.getSubject()),tenantId,source,agentId));
    }
    @GetMapping("/knowledge/documents")
    public Knowledge.Page list(@PathVariable UUID tenantId,@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size) {
        return knowledge.list(UUID.fromString(jwt.getSubject()),tenantId,page,size);
    }
    @GetMapping("/knowledge/documents/{documentId}")
    public KnowledgeDocument get(@PathVariable UUID tenantId,@PathVariable UUID documentId,@AuthenticationPrincipal Jwt jwt) {
        return knowledge.get(UUID.fromString(jwt.getSubject()),tenantId,documentId);
    }
    @PostMapping("/knowledge/documents/{documentId}/retry")
    public ResponseEntity<KnowledgeDocument> retry(@PathVariable UUID tenantId,@PathVariable UUID documentId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException {
        noBody(request);return ResponseEntity.accepted().body(knowledge.retry(UUID.fromString(jwt.getSubject()),tenantId,documentId));
    }
    @PostMapping("/knowledge/documents/{documentId}/agents/{agentId}")
    public KnowledgeDocument associate(@PathVariable UUID tenantId,@PathVariable UUID documentId,@PathVariable UUID agentId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException {
        noBody(request);return knowledge.associate(UUID.fromString(jwt.getSubject()),tenantId,documentId,agentId,false);
    }
    @DeleteMapping("/knowledge/documents/{documentId}/agents/{agentId}")
    public KnowledgeDocument remove(@PathVariable UUID tenantId,@PathVariable UUID documentId,@PathVariable UUID agentId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException {
        noBody(request);return knowledge.associate(UUID.fromString(jwt.getSubject()),tenantId,documentId,agentId,true);
    }
    public record Search(String query,Integer topK) { @Override public String toString(){return "KnowledgeSearch[redacted]";} }
    public record Results(List<KnowledgeStore.Match> results) { @Override public String toString(){return "KnowledgeResults[redacted]";} }
    @PostMapping(value="/agents/{agentId}/knowledge/search",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Results search(@PathVariable UUID tenantId,@PathVariable UUID agentId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException {
        byte[] body=request.getInputStream().readNBytes(16385);
        if(body.length>16384) throw new IllegalArgumentException("Search too large");
        var json=mapper.readTree(body);
        if(json==null || !json.isObject() || !json.path("query").isTextual() || (!json.path("topK").isMissingNode() && (!json.path("topK").isIntegralNumber() || !json.path("topK").canConvertToInt()))) throw new IllegalArgumentException("Invalid search");
        var payload=mapper.treeToValue(json,Search.class);
        return new Results(knowledge.search(UUID.fromString(jwt.getSubject()),tenantId,agentId,payload.query(),payload.topK()));
    }
    private void noBody(HttpServletRequest request)throws IOException {if(request.getInputStream().read()!=-1) throw new IllegalArgumentException("No body accepted");}
}
