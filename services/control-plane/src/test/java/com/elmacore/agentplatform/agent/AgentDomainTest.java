package com.elmacore.agentplatform.agent;

import com.elmacore.agentplatform.agent.application.AgentPageRequest;
import com.elmacore.agentplatform.agent.domain.AgentArchived;
import com.elmacore.agentplatform.agent.domain.AgentConfiguration;
import com.elmacore.agentplatform.agent.domain.AgentDetails;
import com.elmacore.agentplatform.agent.domain.AgentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentDomainTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private AgentConfiguration draft() {
        return AgentConfiguration.draft(UUID.randomUUID(), UUID.randomUUID(), new AgentDetails("Agent", null, "Instructions"), NOW);
    }
    @Test void unicodeNamesAreTrimmedAndMultilineInstructionsStayLiteral() {
        String instructions = "  First line\n${SECRET}\nhttps://example.invalid\nrm -rf /  ";
        var details = new AgentDetails("\u00a0 مساعد 🎓 \u3000", null, instructions);
        assertThat(details.name()).isEqualTo("مساعد 🎓");
        assertThat(details.systemInstructions()).isEqualTo(instructions);
        assertThat(details.toString()).doesNotContain(instructions);
    }
    @ParameterizedTest @ValueSource(strings = {"", "  ", "\t\n", "\u00a0\u3000", "unsafe\nname", "unsafe\tname", "unsafe\u007fname", "unsafe\u0085name", "unsafe\u202ename", "unsafe\u2028name", "unsafe\u2066name"})
    void invalidNamesAreRejected(String name) { assertThrows(IllegalArgumentException.class, () -> new AgentDetails(name, null, "Valid")); }
    @ParameterizedTest @ValueSource(strings = {"", " ", "\t\r\n", "\u00a0\u202f\u3000"})
    void blankInstructionsAreRejected(String instructions) { assertThrows(IllegalArgumentException.class, () -> new AgentDetails("Valid", null, instructions)); }
    @Test void fieldBoundsUseUnicodeCodePointsAndNeverTruncate() {
        assertThat(new AgentDetails("🎓".repeat(120), "🎓".repeat(1000), "🎓".repeat(10000)).name()).isEqualTo("🎓".repeat(120));
        assertThrows(IllegalArgumentException.class, () -> new AgentDetails("x".repeat(121), null, "Valid"));
        assertThrows(IllegalArgumentException.class, () -> new AgentDetails("Valid", "x".repeat(1001), "Valid"));
        assertThrows(IllegalArgumentException.class, () -> new AgentDetails("Valid", null, "x".repeat(10001)));
        assertThrows(IllegalArgumentException.class, () -> new AgentDetails("Valid", null, null));
    }
    @Test void invalidUnicodeAndNulAreRejectedWithoutEchoingContent() {
        for (String invalid : new String[]{"\uD800", "\uDC00", "sensitive\0text"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> new AgentDetails("Valid", null, invalid));
            assertThat(error.getMessage()).doesNotContain(invalid);
        }
    }
    @Test void creationAndUpdatesPreserveIdentityAndServerTimes() {
        var initial = draft();
        assertThat(initial.status()).isEqualTo(AgentStatus.DRAFT);
        var updated = initial.update(new AgentDetails("Changed", "Description", "Updated"), NOW);
        assertThat(updated.id()).isEqualTo(initial.id());
        assertThat(updated.tenantId()).isEqualTo(initial.tenantId());
        assertThat(updated.createdAt()).isEqualTo(initial.createdAt());
        assertThat(updated.updatedAt()).isAfter(initial.updatedAt());
        assertThat(initial.details().name()).isEqualTo("Agent");
    }
    @Test void archiveIsTerminalAndIdempotent() {
        var initial = draft();
        var archived = initial.archive(NOW.plusSeconds(1));
        assertThat(archived.status()).isEqualTo(AgentStatus.ARCHIVED);
        assertThat(archived.id()).isEqualTo(initial.id());
        assertThat(archived.tenantId()).isEqualTo(initial.tenantId());
        assertThat(archived.archive(NOW.plusSeconds(2))).isSameAs(archived);
        assertThrows(AgentArchived.class, () -> archived.update(new AgentDetails("Edit", null, "Edit"), NOW.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> AgentStatus.valueOf("ACTIVE"));
    }
    @Test void timestampOrderIsAnInvariant() {
        var initial = draft();
        assertThrows(IllegalArgumentException.class, () -> new AgentConfiguration(initial.id(), initial.tenantId(), initial.details(),
                AgentStatus.DRAFT, NOW, NOW.minusSeconds(1)));
    }
    @Test void paginationIsBoundedWithoutIntegerOverflow() {
        assertThat(new AgentPageRequest(500, 20, null).offset()).isEqualTo(10000);
        assertThrows(IllegalArgumentException.class, () -> new AgentPageRequest(-1, 20, null));
        assertThrows(IllegalArgumentException.class, () -> new AgentPageRequest(0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new AgentPageRequest(0, 101, null));
        assertThrows(IllegalArgumentException.class, () -> new AgentPageRequest(Integer.MAX_VALUE, 100, null));
    }
}
