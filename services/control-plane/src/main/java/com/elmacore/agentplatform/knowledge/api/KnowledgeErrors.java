package com.elmacore.agentplatform.knowledge.api;

import com.elmacore.agentplatform.knowledge.domain.KnowledgeFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.ResponseEntity;

@RestControllerAdvice(assignableTypes=KnowledgeController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class KnowledgeErrors {
    public record Error(String code) {}
    @ExceptionHandler(KnowledgeFailure.class)
    ResponseEntity<Error> failure(KnowledgeFailure error) {return ResponseEntity.status(error.status()).body(new Error(error.code().toLowerCase(java.util.Locale.ROOT)));}
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Error> size(Exception error) {return ResponseEntity.status(413).body(new Error("upload_too_large"));}
    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<Error> missing(Exception error) {return ResponseEntity.badRequest().body(new Error("invalid_request"));}
}
