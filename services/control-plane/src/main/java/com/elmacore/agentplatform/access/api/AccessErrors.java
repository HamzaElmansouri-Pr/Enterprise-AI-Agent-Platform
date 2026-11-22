package com.elmacore.agentplatform.access.api;
import com.elmacore.agentplatform.access.domain.AccessFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes=AccessController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccessErrors {
    public record Error(String code){}
    @ExceptionHandler(AccessFailure.class)
    ResponseEntity<Error> failure(AccessFailure error){var r=ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore());if(error.status()==429)r.header("Retry-After","60");return r.body(new Error(error.code()));}
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class})
    ResponseEntity<Error> database(Exception error){return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore()).body(new Error("access_persistence_failure"));}
}
