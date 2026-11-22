package com.elmacore.agentplatform.execution.api;

import com.elmacore.agentplatform.execution.application.AgentRuns;
import com.elmacore.agentplatform.execution.domain.*;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.UUID;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/tenants/{tenantId}/agents/{agentId}/runs")
public class RunController {
    private final AgentRuns runs;private final ObjectMapper mapper;
    public RunController(AgentRuns runs,ObjectMapper mapper){this.runs=runs;this.mapper=mapper;}
    private void noQuery(HttpServletRequest request){if(request.getQueryString()!=null)throw new IllegalArgumentException("No query parameters");}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RunResult> start(@PathVariable UUID tenantId,@PathVariable UUID agentId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request)throws IOException {
        noQuery(request);
        byte[] bytes=request.getInputStream().readNBytes(16385);
        if(bytes.length>16384)throw new IllegalArgumentException("Run request too large");
        RunInput input;
        try {
            var json=mapper.readTree(bytes);
            if(json==null || !json.isObject() || !json.path("message").isTextual()
                    || (!json.path("topK").isMissingNode() && (!json.path("topK").isIntegralNumber() || !json.path("topK").canConvertToInt())))throw new IllegalArgumentException("Invalid execution");
            input=mapper.treeToValue(json,RunInput.class);
        } catch(tools.jackson.core.JacksonException error) {
            // Framework DEBUG exception resolution must not echo private JSON fragments.
            throw new IllegalArgumentException("Invalid execution request");
        }
        var result=runs.start(UUID.fromString(jwt.getSubject()),tenantId,agentId,input,UUID.fromString(org.slf4j.MDC.get("correlation_id")));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping("/{runId}")
    public ResponseEntity<RunResult> get(@PathVariable UUID tenantId,@PathVariable UUID agentId,@PathVariable UUID runId,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) {
        noQuery(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runs.get(UUID.fromString(jwt.getSubject()),tenantId,agentId,runId));
    }
}
