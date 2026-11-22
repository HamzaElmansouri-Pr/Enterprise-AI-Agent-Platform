package com.elmacore.agentplatform.agent.api;

import com.elmacore.agentplatform.agent.application.AgentConfigurations;
import com.elmacore.agentplatform.agent.application.AgentPageRequest;
import com.elmacore.agentplatform.agent.domain.AgentConfiguration;
import com.elmacore.agentplatform.agent.domain.AgentDetails;
import com.elmacore.agentplatform.agent.domain.AgentStatus;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/tenants/{tenantId}/agents")
public class AgentController {
    private static final int MAX_BODY_BYTES = 160 * 1024;
    private final AgentConfigurations agents;
    private final ObjectMapper mapper;
    public AgentController(AgentConfigurations agents, ObjectMapper mapper) { this.agents = agents; this.mapper = mapper; }
    public record Payload(String name, String description, String systemInstructions) {
        @Override public String toString() { return "AgentPayload[redacted]"; }
    }
    public record Response(UUID id, UUID tenantId, String name, String description, String systemInstructions,
                           AgentStatus status, Instant createdAt, Instant updatedAt) {
        static Response from(AgentConfiguration agent) {
            return new Response(agent.id(), agent.tenantId(), agent.details().name(), agent.details().description(),
                    agent.details().systemInstructions(), agent.status(), agent.createdAt(), agent.updatedAt());
        }
        @Override public String toString() { return "AgentResponse[redacted]"; }
    }
    public record Page(List<Response> items, int page, int size, boolean hasNext) {
        @Override public String toString() { return "AgentPageResponse[redacted]"; }
    }
    private AgentDetails body(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Agent body too large");
        var json = mapper.readTree(bytes);
        if (json == null || !json.isObject() || !json.path("name").isTextual()
                || !json.path("systemInstructions").isTextual()
                || (!json.path("description").isMissingNode() && !json.path("description").isNull()
                    && !json.path("description").isTextual())) {
            throw new IllegalArgumentException("Invalid agent payload");
        }
        var payload = mapper.treeToValue(json, Payload.class);
        return new AgentDetails(payload.name(), payload.description(), payload.systemInstructions());
    }
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Response> create(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt,
                                           HttpServletRequest request) throws IOException {
        var agent = agents.create(UUID.fromString(jwt.getSubject()), tenantId, body(request));
        var location = URI.create(request.getContextPath() + "/api/v1/tenants/" + tenantId + "/agents/" + agent.id());
        return ResponseEntity.created(location).body(Response.from(agent));
    }
    @GetMapping
    public Page list(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt,
                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                     @RequestParam(required = false) String status) {
        var result = agents.list(UUID.fromString(jwt.getSubject()), tenantId, new AgentPageRequest(page, size, status == null ? null : AgentStatus.valueOf(status)));
        return new Page(result.items().stream().map(Response::from).toList(), result.page(), result.size(), result.hasNext());
    }
    @GetMapping("/{agentId}")
    public Response get(@PathVariable UUID tenantId, @PathVariable UUID agentId, @AuthenticationPrincipal Jwt jwt) {
        return Response.from(agents.get(UUID.fromString(jwt.getSubject()), tenantId, agentId));
    }
    @PutMapping(value = "/{agentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response update(@PathVariable UUID tenantId, @PathVariable UUID agentId, @AuthenticationPrincipal Jwt jwt,
                           HttpServletRequest request) throws IOException {
        return Response.from(agents.update(UUID.fromString(jwt.getSubject()), tenantId, agentId, body(request)));
    }
    @PostMapping("/{agentId}/archive")
    public Response archive(@PathVariable UUID tenantId, @PathVariable UUID agentId, @AuthenticationPrincipal Jwt jwt,
                            HttpServletRequest request) throws IOException {
        if (request.getInputStream().read() != -1) throw new IllegalArgumentException("Archive accepts no body");
        return Response.from(agents.archive(UUID.fromString(jwt.getSubject()), tenantId, agentId));
    }
}
