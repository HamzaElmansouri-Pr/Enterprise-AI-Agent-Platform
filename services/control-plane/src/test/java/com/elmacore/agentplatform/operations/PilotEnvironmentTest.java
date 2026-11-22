package com.elmacore.agentplatform.operations;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PilotEnvironmentTest {
    MockEnvironment valid(){
        var e=new MockEnvironment();e.setActiveProfiles("pilot");
        e.withProperty("EAAP_DEPLOYMENT_MODE","pilot").withProperty("EAAP_RELEASE_SHA","a".repeat(40))
            .withProperty("AUTH_PRIVATE_KEY","file:/run/secrets/jwt-private.pem").withProperty("AUTH_PUBLIC_KEY","file:/run/secrets/jwt-public.pem")
            .withProperty("AUTH_ISSUER","https://localhost").withProperty("AUTH_AUDIENCE","pilot").withProperty("AUTH_KEY_ID","pilot")
            .withProperty("DB_URL","jdbc:postgresql://postgres:5432/eaap_pilot").withProperty("DB_PASSWORD","r".repeat(40))
            .withProperty("EMBEDDING_SECRET","e".repeat(40)).withProperty("EXECUTION_INTERNAL_SECRET","x".repeat(40))
            .withProperty("OPERATIONS_SECRET","o".repeat(40)).withProperty("spring.config.import","configtree:/run/secrets/")
            .withProperty("management.server.port","9090").withProperty("management.endpoints.web.exposure.include","health,pilot")
            .withProperty("server.request.header.forwarded.strategy","none");return e;
    }
    @Test void validPilotAccepted(){assertThatCode(()->PilotEnvironment.validate(valid())).doesNotThrowAnyException();}
    @Test void existingDevelopmentStillNeedsNoKeys(){assertThatCode(()->PilotEnvironment.validate(new MockEnvironment())).doesNotThrowAnyException();}
    @Test void pilotNeverFallsBackToDevelopment(){var e=valid();e.withProperty("EAAP_DEPLOYMENT_MODE","development");assertThatThrownBy(()->PilotEnvironment.validate(e)).isInstanceOf(IllegalStateException.class);}
    @Test void unknownModeRejected(){assertThatThrownBy(()->PilotEnvironment.validate(new MockEnvironment().withProperty("EAAP_DEPLOYMENT_MODE","unexpected"))).isInstanceOf(IllegalStateException.class);}
    @Test void requiredConfigurationCannotBeBlank(){for(String name:new String[]{"DB_PASSWORD","EMBEDDING_SECRET","EXECUTION_INTERNAL_SECRET","AUTH_PRIVATE_KEY","AUTH_ISSUER","AUTH_AUDIENCE"}){var e=valid().withProperty(name,"");assertThatThrownBy(()->PilotEnvironment.validate(e)).isInstanceOf(IllegalStateException.class);}}
    @Test void weakReusedSecretsRejectedWithoutEcho(){for(String value:new String[]{"short","r".repeat(40)}){var e=valid().withProperty("EXECUTION_INTERNAL_SECRET",value);assertThatThrownBy(()->PilotEnvironment.validate(e)).hasMessage("Unsafe pilot configuration; check the operator preflight").hasMessageNotContaining(value);}}
    @Test void debugAndUnsafeManagementRejected(){for(var setting:Map.of("debug","true","management.endpoints.web.exposure.include","*","server.request.header.forwarded.strategy","framework","DB_URL","jdbc:postgresql://production.example/private").entrySet()){var e=valid().withProperty(setting.getKey(),setting.getValue());assertThatThrownBy(()->PilotEnvironment.validate(e)).isInstanceOf(IllegalStateException.class);}}
    @Test void environmentCredentialFallbackRejected(){var e=valid();e.getPropertySources().addFirst(new MapPropertySource("systemEnvironment",Map.of("DB_PASSWORD","sensitive-test-marker-".repeat(3))));assertThatThrownBy(()->PilotEnvironment.validate(e)).hasMessageNotContaining("sensitive-test-marker");}
    @Test void operatorProfileMustBeExplicitAndOffline(){var e=new MockEnvironment().withProperty("EAAP_DEPLOYMENT_MODE","operator");assertThatThrownBy(()->PilotEnvironment.validate(e)).isInstanceOf(IllegalStateException.class);e.setActiveProfiles("migration");assertThatCode(()->PilotEnvironment.validate(e)).doesNotThrowAnyException();}
    @Test void diagnosticsOnlyExposeFixedCounters(){OperationalSignals.record(OperationalSignals.Event.REQUEST_OK,1);assertThat(OperationalSignals.snapshot()).hasSize(13);assertThat(OperationalSignals.snapshot().get("REQUEST_OK")).isPositive();}
    @Test void terminalCountersOnlyChangeAfterCommit() {
        long before=OperationalSignals.snapshot().get("EXECUTION_FAILED");
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            OperationalSignals.committed(OperationalSignals.Event.EXECUTION_FAILED,1);
            assertThat(OperationalSignals.snapshot().get("EXECUTION_FAILED")).isEqualTo(before);
            var callbacks=org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            assertThat(OperationalSignals.snapshot().get("EXECUTION_FAILED")).isEqualTo(before+1);
        } finally {org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
    }
    @Test void rolledBackCountersDoNotClaimSuccess() {
        long before=OperationalSignals.snapshot().get("EXECUTION_SUCCEEDED");
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {OperationalSignals.committed(OperationalSignals.Event.EXECUTION_SUCCEEDED,1);}
        finally {org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
        assertThat(OperationalSignals.snapshot().get("EXECUTION_SUCCEEDED")).isEqualTo(before);
    }
}
