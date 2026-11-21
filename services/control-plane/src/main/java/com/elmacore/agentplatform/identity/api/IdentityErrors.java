package com.elmacore.agentplatform.identity.api;

import com.elmacore.agentplatform.identity.application.IdentityFailure;
import com.fasterxml.jackson.core.JacksonException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

@RestControllerAdvice
public class IdentityErrors {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(IdentityErrors.class);
    public record Error(String code) {}
    @ExceptionHandler(IdentityFailure.class)
    ResponseEntity<Error> identity(IdentityFailure error) {
        int status = switch (error.reason()) {
            case UNAUTHENTICATED -> 401; case FORBIDDEN -> 403; case UNAVAILABLE -> 503; case LIMITED -> 429;
        };
        String code = switch (error.reason()) {
            case UNAUTHENTICATED -> "invalid_credentials"; case FORBIDDEN -> "access_denied";
            case UNAVAILABLE -> "authentication_unavailable"; case LIMITED -> "too_many_attempts";
        };
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status == 429) response.header("Retry-After", "60");
        return response.body(new Error(code));
    }
    @ExceptionHandler({IllegalArgumentException.class, JacksonException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Error> invalid(Exception error) { return ResponseEntity.badRequest().body(new Error("invalid_request")); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Error> media(Exception error) { return ResponseEntity.status(415).body(new Error("json_required")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> internal(Exception error) {
        LOG.error("identity_request_failed correlation_id={}", org.slf4j.MDC.get("correlation_id"));
        return ResponseEntity.internalServerError().body(new Error("internal_error"));
    }
}
