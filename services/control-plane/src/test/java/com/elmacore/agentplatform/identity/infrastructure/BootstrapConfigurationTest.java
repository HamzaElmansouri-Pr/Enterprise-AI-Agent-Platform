package com.elmacore.agentplatform.identity.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class BootstrapConfigurationTest {
    private MockEnvironment inputs() {
        return new MockEnvironment()
                .withProperty("BOOTSTRAP_TENANT_ID", "10000000-0000-0000-0000-000000000001")
                .withProperty("BOOTSTRAP_USER_ID", "20000000-0000-0000-0000-000000000001")
                .withProperty("BOOTSTRAP_TENANT_NAME", "Test tenant")
                .withProperty("BOOTSTRAP_EMAIL", "test@example.com");
    }
    private String failure(MockEnvironment env) {
        var source = mock(DataSource.class);
        var runner = new BootstrapConfiguration().bootstrapIdentity(env, source);
        var error = assertThrows(IllegalStateException.class,
                () -> runner.run(new DefaultApplicationArguments()));
        verifyNoInteractions(source);
        assertThat(error.getCause()).isNull();
        return error.getMessage();
    }
    @Test void missingPasswordNamesTheFieldWithoutOpeningDatabase() {
        assertThat(failure(inputs())).isEqualTo("Identity bootstrap failed: Missing required input: BOOTSTRAP_PASSWORD");
    }
    @Test void shortPasswordReportsConstraintWithoutRevealingValue() {
        String password = "short-test-only";
        assertThat(failure(inputs().withProperty("BOOTSTRAP_PASSWORD", password)))
                .contains("BOOTSTRAP_PASSWORD", "16–128").doesNotContain(password);
    }
    @Test void invalidUuidDoesNotEchoItsValue() {
        String value = "private-test-input";
        assertThat(failure(inputs().withProperty("BOOTSTRAP_TENANT_ID", value)))
                .isEqualTo("Identity bootstrap failed: BOOTSTRAP_TENANT_ID must be a UUID").doesNotContain(value);
    }
    @Test void invalidEmailDoesNotEchoItsValue() {
        String value = "private-test-input";
        assertThat(failure(inputs().withProperty("BOOTSTRAP_EMAIL", value)))
                .isEqualTo("Identity bootstrap failed: BOOTSTRAP_EMAIL must be a valid email").doesNotContain(value);
    }
}
