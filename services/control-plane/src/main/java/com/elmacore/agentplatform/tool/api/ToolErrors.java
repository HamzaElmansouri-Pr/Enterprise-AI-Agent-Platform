package com.elmacore.agentplatform.tool.api;
import com.elmacore.agentplatform.tool.domain.ToolFailure;
import com.elmacore.agentplatform.agent.application.AgentNotFound;
import com.elmacore.agentplatform.agent.domain.AgentArchived;
import com.elmacore.agentplatform.execution.domain.RunFailure;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes=ToolController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ToolErrors {
    public record Error(String code){}
    private ResponseEntity<Error> error(String code,int status){var response=ResponseEntity.status(status).cacheControl(CacheControl.noStore());if(status==429)response.header("Retry-After","60");return response.body(new Error(code));}
    @ExceptionHandler(ToolFailure.class)ResponseEntity<Error> tool(ToolFailure e){return error(e.code(),e.status());}
    @ExceptionHandler(RunFailure.class)ResponseEntity<Error> runtime(RunFailure e){return error(e.code(),e.status());}
    @ExceptionHandler(AgentNotFound.class)ResponseEntity<Error> agent(AgentNotFound e){return error("agent_not_found",404);}
    @ExceptionHandler(AgentArchived.class)ResponseEntity<Error> archived(AgentArchived e){return error("agent_archived",409);}
    @ExceptionHandler(org.springframework.dao.CannotAcquireLockException.class)ResponseEntity<Error> busy(Exception e){return error("proposal_busy",409);}
}
