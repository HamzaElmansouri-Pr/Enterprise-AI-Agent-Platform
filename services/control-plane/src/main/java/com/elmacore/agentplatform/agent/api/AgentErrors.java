package com.elmacore.agentplatform.agent.api;

import com.elmacore.agentplatform.agent.application.AgentNotFound;
import com.elmacore.agentplatform.agent.domain.AgentArchived;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AgentController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentErrors {
    public record Error(String code) {}
    @ExceptionHandler(AgentNotFound.class)
    ResponseEntity<Error> missing(AgentNotFound error) { return ResponseEntity.status(404).body(new Error("agent_not_found")); }
    @ExceptionHandler(AgentArchived.class)
    ResponseEntity<Error> archived(AgentArchived error) { return ResponseEntity.status(409).body(new Error("agent_archived")); }
}
