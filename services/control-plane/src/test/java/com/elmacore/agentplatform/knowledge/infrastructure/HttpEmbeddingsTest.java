package com.elmacore.agentplatform.knowledge.infrastructure;

import com.elmacore.agentplatform.knowledge.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpEmbeddingsTest {
    HttpServer server;String address;final ObjectMapper mapper=new ObjectMapper();
    final AtomicReference<String> body=new AtomicReference<>();
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        address="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop(){server.stop(0);}
    void reply(String response,int code) {
        server.createContext("/internal/embeddings",exchange->{
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("X-Embedding-Secret")).isEqualTo("x".repeat(40));
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });server.start();
    }
    @Test void explicitInternalContractChecksModelAndDimensions()throws Exception {
        var v=new ArrayList<Double>(Collections.nCopies(384,0.0));v.set(0,1.0);
        reply(mapper.writeValueAsString(Map.of("modelId",EmbeddingVector.MODEL,"dimension",384,"vectors",List.of(v))),200);
        var client=new HttpEmbeddings(address,"x".repeat(40),mapper);
        assertThat(client.embed("query",List.of("private-query"))).hasSize(1);
        var input=mapper.readTree(body.get());assertThat(input.get("kind").asText()).isEqualTo("query");assertThat(input.get("texts").get(0).asText()).isEqualTo("private-query");
    }
    @Test void oversizeResponseAndServerFailureAreSanitized() {
        reply("x".repeat(131073),200);
        var failure=assertThrows(KnowledgeFailure.class,()->new HttpEmbeddings(address,"x".repeat(40),mapper).embed("query",List.of("private-query")));
        assertThat(failure.code()).isEqualTo("EMBEDDING_UNAVAILABLE");assertThat(failure.toString()).doesNotContain("private-query");
    }
    @Test void mismatchedModelDoesNotReachVectorPersistence()throws Exception {
        reply(mapper.writeValueAsString(Map.of("modelId","wrong","dimension",384,"vectors",List.of(Collections.nCopies(384,0.0)))),200);
        assertThrows(KnowledgeFailure.class,()->new HttpEmbeddings(address,"x".repeat(40),mapper).embed("query",List.of("fixture")));
    }
    @Test void totalDeadlineIncludesSlowResponseBody() {
        server.createContext("/internal/embeddings",exchange->{
            exchange.sendResponseHeaders(200,100);exchange.getResponseBody().flush();
            try{Thread.sleep(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            exchange.close();
        });server.start();
        long start=System.nanoTime();
        assertThrows(KnowledgeFailure.class,()->new HttpEmbeddings(address,"x".repeat(40),mapper,Duration.ofMillis(200)).embed("query",List.of("fixture")));
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));
    }
}
