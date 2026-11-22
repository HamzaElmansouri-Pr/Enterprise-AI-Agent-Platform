package com.elmacore.agentplatform.execution.api;

import com.elmacore.agentplatform.execution.domain.RunFailure;
import com.elmacore.agentplatform.agent.application.AgentNotFound;
import com.elmacore.agentplatform.agent.domain.AgentArchived;
import com.elmacore.agentplatform.knowledge.domain.KnowledgeFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestControllerAdvice(assignableTypes=RunController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RunErrors {
    public record Error(String code,UUID runId) {}
    @ExceptionHandler(RunFailure.class)
    ResponseEntity<Error> run(RunFailure error){
        var response=ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore());
        if(error.status()==429)response.header("Retry-After","60");
        return response.body(new Error(error.code(),error.runId()));
    }
    @ExceptionHandler(AgentNotFound.class)
    ResponseEntity<Error> agent(AgentNotFound error){return ResponseEntity.status(404).body(new Error("agent_not_found",null));}
    @ExceptionHandler(AgentArchived.class)
    ResponseEntity<Error> archived(AgentArchived error){return ResponseEntity.status(409).body(new Error("agent_archived",null));}
    @ExceptionHandler(KnowledgeFailure.class)
    ResponseEntity<Error> knowledge(KnowledgeFailure error){return ResponseEntity.status(error.status()).body(new Error(error.code(),null));}
}
