package com.elmacore.agentplatform.tool.infrastructure;

import com.elmacore.agentplatform.tool.application.ProposalRuntime;
import com.elmacore.agentplatform.execution.application.ExecutionRuntime;
import com.elmacore.agentplatform.execution.domain.RunFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class HttpProposalRuntime implements ProposalRuntime {
    private final URI uri;
    private final String secret;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public HttpProposalRuntime(String base,String secret,ObjectMapper mapper){this(base,secret,mapper,Duration.ofSeconds(50));}
    HttpProposalRuntime(String base,String secret,ObjectMapper mapper,Duration timeout) {
        this.uri=URI.create(base+"/internal/tool-proposals");this.secret=secret;this.mapper=mapper;this.timeout=timeout;
        if(!secret.isEmpty() && (secret.length()<32 || secret.length()>128 || secret.codePoints().anyMatch(c->c<33 || c>126)))throw new IllegalArgumentException("Invalid execution secret");
        if(!List.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)throw new IllegalArgumentException("Invalid runtime address");
    }
    @Override public Response generate(Request body) {
        if(secret.isEmpty())throw new RunFailure("MODEL_UNAVAILABLE",503);
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            byte[] bytes=mapper.writeValueAsBytes(body);
            if(bytes.length>128*1024)throw new RunFailure("CONTEXT_LIMIT",400);
            var request=HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type","application/json")
                .header("X-Execution-Secret",secret).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            pending=client.sendAsync(request,info->new BoundedBody());
            var response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            if(response.statusCode()!=200) {
                // Never echo an untrusted runtime/provider error string.
                var failureJson=mapper.readTree(response.body());
                String detail=failureJson.path("detail").asText();
                String model=null;var usage=new com.elmacore.agentplatform.execution.domain.RunResult.Usage(null,null);
                if(ExecutionRuntime.MODEL.equals(failureJson.path("modelId").asText()) && failureJson.path("usage").isObject()) {
                    var in=failureJson.path("usage").path("inputTokens");var out=failureJson.path("usage").path("outputTokens");
                    if(validToken(in,1000000) && validToken(out,800)) {
                        model=ExecutionRuntime.MODEL;usage=new com.elmacore.agentplatform.execution.domain.RunResult.Usage(in.isNull()?null:in.intValue(),out.isNull()?null:out.intValue());
                    }
                }
                if(response.statusCode()==422 && detail.equals("INSUFFICIENT_KNOWLEDGE"))throw new RunFailure(detail,422,null,model,usage);
                if(response.statusCode()==504)throw new RunFailure("PROVIDER_TIMEOUT",504);
                if(response.statusCode()==503 || response.statusCode()==401 || response.statusCode()==429)throw new RunFailure("MODEL_UNAVAILABLE",503);
                throw new RunFailure("RUNTIME_FAILURE",502,null,model,usage);
            }
            var json=mapper.readTree(response.body());
            if(!json.isObject() || !json.path("proposal").isObject() || !json.path("modelId").isTextual() || !json.path("usage").isObject())throw new RunFailure("INVALID_MODEL_OUTPUT",502);
            var proposal=json.path("proposal");var args=proposal.path("arguments");
            if(!proposal.path("toolName").isTextual() || !proposal.path("rationale").isTextual() || !args.isObject()
                || !args.path("title").isTextual() || !args.path("description").isTextual() || !args.path("priority").isTextual())throw new RunFailure("INVALID_MODEL_OUTPUT",502);
            for(String field:List.of("inputTokens","outputTokens")) {
                var value=json.path("usage").path(field);
                if(value.isMissingNode() || !(value.isNull() || (value.isIntegralNumber() && value.canConvertToInt())))throw new RunFailure("INVALID_MODEL_OUTPUT",502);
            }
            try{return mapper.treeToValue(json,Response.class);}
            catch(java.io.IOException|IllegalArgumentException invalid){throw new RunFailure("INVALID_MODEL_OUTPUT",502);}
        } catch(TimeoutException error){throw new RunFailure("PROVIDER_TIMEOUT",504);}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new RunFailure("RUNTIME_UNAVAILABLE",503);}
        catch(java.io.IOException|ExecutionException error){throw new RunFailure("RUNTIME_UNAVAILABLE",502);}
        finally {if(pending!=null && !pending.isDone())pending.cancel(true);}
    }
    private static boolean validToken(com.fasterxml.jackson.databind.JsonNode n,int max) {return n.isNull() || (n.isIntegralNumber() && n.canConvertToInt() && n.intValue()>=0 && n.intValue()<=max);}
    /** Completes at end-of-body so the future deadline covers the entire bounded response. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> body=new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody(){return body;}
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<java.nio.ByteBuffer> buffers){
            for(var buffer:buffers) {
                if((long)bytes.size()+buffer.remaining()>64*1024) {
                    subscription.cancel();body.completeExceptionally(new java.io.IOException("Execution response too large"));return;
                }
                byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onComplete(){body.complete(bytes.toByteArray());}
        @Override public void onError(Throwable error){body.completeExceptionally(error);}
    }

}
