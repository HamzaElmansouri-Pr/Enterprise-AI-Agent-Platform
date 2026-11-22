package com.elmacore.agentplatform.knowledge.infrastructure;

import com.elmacore.agentplatform.knowledge.application.Embeddings;
import com.elmacore.agentplatform.knowledge.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public final class HttpEmbeddings implements Embeddings {
    private final URI uri;private final String secret;private final ObjectMapper mapper;
    private final Duration timeout;
    private final java.util.concurrent.Semaphore active=new java.util.concurrent.Semaphore(1);
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public HttpEmbeddings(String base,String secret,ObjectMapper mapper) {
        this(base,secret,mapper,Duration.ofSeconds(45));
    }
    HttpEmbeddings(String base,String secret,ObjectMapper mapper,Duration timeout) {
        this.timeout=timeout;
        if(!secret.isEmpty() && (secret.length()<32 || secret.length()>128 || secret.codePoints().anyMatch(c->c<33 || c>126))) throw new IllegalArgumentException("Invalid internal embedding secret");
        this.uri=URI.create(base+"/internal/embeddings");this.secret=secret;this.mapper=mapper;
        if(!List.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException("Invalid internal embedding address");
    }
    @Override public List<EmbeddingVector> embed(String kind,List<String> texts) {
        if(secret.length()<32 || texts.isEmpty() || texts.size()>8) throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);
        if(!active.tryAcquire()) throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            var request=HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type","application/json").header("X-Embedding-Secret",secret)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(Map.of("kind",kind,"texts",texts)))).build();
            pending=client.sendAsync(request, info->new BoundedBody());
            var response=pending.get(timeout.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);
            {
                if(response.statusCode()!=200) throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);
                byte[] bytes=response.body();
                var json=mapper.readTree(bytes);
                if(json.path("dimension").asInt()!=384 || !json.path("vectors").isArray() || json.path("vectors").size()!=texts.size()) throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);
                var vectors=new ArrayList<EmbeddingVector>();
                for(var row:json.path("vectors")) {
                    if(!row.isArray()) throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);
                    var values=new ArrayList<Double>();for(var value:row) {
                        if(!value.isNumber()) throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);values.add(value.asDouble());
                    }
                    vectors.add(new EmbeddingVector(json.path("modelId").asText(),values));
                }
                return List.copyOf(vectors);
            }
        } catch(InterruptedException error) {Thread.currentThread().interrupt();throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);}
        catch(java.io.IOException|java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException error) {throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);}
        finally { if(pending!=null && !pending.isDone()) pending.cancel(true); active.release(); }
    }
    /** Completes at end-of-body so the future deadline covers the entire bounded response. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> body=new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody(){return body;}
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<java.nio.ByteBuffer> buffers){
            for(var buffer:buffers) {
                if((long)bytes.size()+buffer.remaining()>128*1024) {
                    subscription.cancel();body.completeExceptionally(new java.io.IOException("Embedding response too large"));return;
                }
                byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onComplete(){body.complete(bytes.toByteArray());}
        @Override public void onError(Throwable error){body.completeExceptionally(error);}
    }

}
