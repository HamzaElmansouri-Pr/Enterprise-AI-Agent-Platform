package com.elmacore.agentplatform.execution.infrastructure;

import com.elmacore.agentplatform.execution.application.ExecutionRuntime;
import com.elmacore.agentplatform.execution.domain.RunFailure;
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

class HttpExecutionRuntimeTest {
    HttpServer server;String address;final ObjectMapper mapper=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    final AtomicReference<String> body=new AtomicReference<>();
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        address="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop(){server.stop(0);}
    ExecutionRuntime.Request input(){return new ExecutionRuntime.Request(UUID.randomUUID(),UUID.randomUUID(),"stored instructions","private question",List.of(new ExecutionRuntime.Passage("S1","private passage")));}
    void reply(String response,int code) {
        server.createContext("/internal/executions",exchange->{
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("X-Execution-Secret")).isEqualTo("x".repeat(40));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("X-Embedding-Secret")).isNull();
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });server.start();
    }
    HttpExecutionRuntime client(){return new HttpExecutionRuntime(address,"x".repeat(40),mapper);}
    @Test void explicitContractContainsNoCustomerIdentityOrJwt()throws Exception {
        reply("{\"answer\":\"passport\",\"sourceIds\":[\"S1\"],\"modelId\":\""+ExecutionRuntime.MODEL+"\",\"usage\":{\"inputTokens\":42,\"outputTokens\":9}}",200);
        var output=client().execute(input());assertThat(output.usage().inputTokens()).isEqualTo(42);
        var json=mapper.readTree(body.get());assertThat(json.size()).isEqualTo(5);assertThat(json.has("tenantId")).isFalse();assertThat(json.has("userId")).isFalse();
        assertThat(json.path("passages").get(0).size()).isEqualTo(2);
    }
    @Test void multiTurnUsesDedicatedRouteAndBoundedHistoryWithoutCustomerIdentity()throws Exception {
        var path=new AtomicReference<String>();
        server.createContext("/internal/executions/conversation",exchange->{
            path.set(exchange.getRequestURI().getPath());body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("X-Execution-Secret")).isEqualTo("x".repeat(40));
            byte[] bytes=("{\"answer\":\"passport\",\"sourceIds\":[\"S1\"],\"modelId\":\""+ExecutionRuntime.MODEL+"\",\"usage\":{\"inputTokens\":42,\"outputTokens\":9}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
        });server.start();var original=input();
        var request=new com.elmacore.agentplatform.conversation.application.ConversationRuntime.Request(original.runId(),original.correlationId(),original.instructions(),original.message(),original.passages(),List.of(new com.elmacore.agentplatform.conversation.application.ConversationRuntime.History("earlier question","earlier answer")));
        assertThat(client().converse(request).answer()).isEqualTo("passport");assertThat(path.get()).isEqualTo("/internal/executions/conversation");
        var json=mapper.readTree(body.get());assertThat(json.size()).isEqualTo(6);assertThat(json.path("history").get(0).path("answer").asText()).isEqualTo("earlier answer");assertThat(json.has("tenantId")).isFalse();assertThat(json.has("userId")).isFalse();
    }
    @Test void unconfiguredCredentialNeverCallsRuntime() {
        assertThat(assertThrows(RunFailure.class,()->new HttpExecutionRuntime(address,"",mapper).execute(input())).status()).isEqualTo(503);
        assertThat(body.get()).isNull();
    }
    @Test void oversizedResponseSanitized() {
        reply("x".repeat(65537),200);
        var failure=assertThrows(RunFailure.class,()->client().execute(input()));
        assertThat(failure.status()).isEqualTo(502);assertThat(failure.toString()).doesNotContain("private question");
    }
    @Test void malformedUsageCannotCoerceOrFabricateTokens() {
        reply("{\"answer\":\"passport\",\"sourceIds\":[\"S1\"],\"modelId\":\"x\",\"usage\":{\"inputTokens\":\"42\",\"outputTokens\":1.5}}",200);
        assertThat(assertThrows(RunFailure.class,()->client().execute(input())).code()).isEqualTo("INVALID_MODEL_OUTPUT");
    }
    @Test void providerErrorTextIsNotExposed() {
        reply("{\"detail\":\"private provider exception\"}",502);
        assertThat(assertThrows(RunFailure.class,()->client().execute(input())).getMessage()).isEqualTo("RUNTIME_FAILURE");
    }
    @Test void safeFailureUsageComesFromRuntimeMetadata() {
        reply("{\"detail\":\"INSUFFICIENT_KNOWLEDGE\",\"modelId\":\""+ExecutionRuntime.MODEL+"\",\"usage\":{\"inputTokens\":42,\"outputTokens\":9}}",422);
        var failure=assertThrows(RunFailure.class,()->client().execute(input()));
        assertThat(failure.code()).isEqualTo("INSUFFICIENT_KNOWLEDGE");assertThat(failure.usage().inputTokens()).isEqualTo(42);
    }
    @Test void totalDeadlineIncludesBodyAndNeverRetries() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/internal/executions",exchange->{
            calls.incrementAndGet();exchange.sendResponseHeaders(200,100);exchange.getResponseBody().flush();
            try{Thread.sleep(1200);}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.close();
        });server.start();long start=System.nanoTime();
        assertThat(assertThrows(RunFailure.class,()->new HttpExecutionRuntime(address,"x".repeat(40),mapper,Duration.ofMillis(200)).execute(input())).status()).isEqualTo(504);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(1));assertThat(calls.get()).isEqualTo(1);
    }
}
