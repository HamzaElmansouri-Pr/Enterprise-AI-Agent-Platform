package com.elmacore.agentplatform.tool.infrastructure;

import com.elmacore.agentplatform.tool.application.ProposalRuntime;
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

class HttpProposalRuntimeTest {
    HttpServer server;String address;final ObjectMapper mapper=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    final AtomicReference<String> body=new AtomicReference<>();
    @BeforeEach void start()throws Exception {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);address="http://127.0.0.1:"+server.getAddress().getPort();}
    @AfterEach void stop(){server.stop(0);}
    ProposalRuntime.Request input(){return new ProposalRuntime.Request(UUID.randomUUID(),UUID.randomUUID(),"stored instructions","private-intent");}
    void reply(String response,int code){server.createContext("/internal/tool-proposals",exchange->{
        body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
        assertThat(exchange.getRequestHeaders().getFirst("X-Execution-Secret")).isEqualTo("x".repeat(40));
        byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
    });server.start();}
    String valid(){return "{\"proposal\":{\"toolName\":\"admissions.create_followup_task\",\"arguments\":{\"title\":\"Review\",\"description\":\"Check documents\",\"priority\":\"NORMAL\"},\"rationale\":\"Staff review\"},\"modelId\":\""+ExecutionRuntime.MODEL+"\",\"usage\":{\"inputTokens\":28,\"outputTokens\":36}}";}
    HttpProposalRuntime client(){return new HttpProposalRuntime(address,"x".repeat(40),mapper);}
    @Test void onlyBoundedIntentAndInstructionsAreForwarded()throws Exception {
        reply(valid(),200);var result=client().generate(input());assertThat(result.usage().inputTokens()).isEqualTo(28);
        var request=mapper.readTree(body.get());assertThat(request.size()).isEqualTo(4);assertThat(request.has("tenantId")).isFalse();assertThat(request.has("role")).isFalse();assertThat(request.has("userId")).isFalse();
    }
    @Test void unknownModelToolAndAuthorityFieldsNeverBecomeProposals(){
        reply(valid().replace("admissions.create_followup_task","shell.run"),200);
        assertThat(assertThrows(RunFailure.class,()->client().generate(input())).code()).isEqualTo("INVALID_MODEL_OUTPUT");
    }
    @Test void unknownArgumentFieldsRejectedIndependentlyInSpring(){
        reply(valid().replace("\"priority\":\"NORMAL\"","\"priority\":\"NORMAL\",\"tenantId\":\"other\""),200);
        assertThrows(RunFailure.class,()->client().generate(input()));
    }
    @Test void oversizedResponseAndProviderErrorsSanitized(){reply("x".repeat(65537),200);assertThat(assertThrows(RunFailure.class,()->client().generate(input())).toString()).doesNotContain("private-intent");}
    @Test void totalDeadlineIncludesSlowBody(){
        server.createContext("/internal/tool-proposals",e->{e.sendResponseHeaders(200,100);e.getResponseBody().flush();try{Thread.sleep(1200);}catch(InterruptedException x){Thread.currentThread().interrupt();}e.close();});server.start();
        assertThat(assertThrows(RunFailure.class,()->new HttpProposalRuntime(address,"x".repeat(40),mapper,Duration.ofMillis(200)).generate(input())).status()).isEqualTo(504);
    }
}
