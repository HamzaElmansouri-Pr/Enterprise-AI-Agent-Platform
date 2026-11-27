package com.elmacore.agentplatform;

import com.elmacore.agentplatform.identity.application.*;
import com.elmacore.agentplatform.identity.domain.Role;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantDiscoveryTest {
    private final IdentityStore identities = mock(IdentityStore.class);
    private final TenantDirectory directory = mock(TenantDirectory.class);
    private final TenantDiscovery discovery = new TenantDiscovery(identities, directory);
    private final UUID actor = UUID.randomUUID();

    @Test void enabledIdentityGetsBoundedPageAndLookaheadNotAnUnboundedDirectory() {
        when(identities.enabled(actor)).thenReturn(true);
        var first = new TenantDirectory.Workspace(UUID.randomUUID(), "Atlas", Role.OWNER);
        when(directory.list(actor, 2, 1)).thenReturn(List.of(first,
                new TenantDirectory.Workspace(UUID.randomUUID(), "Rif", Role.MEMBER)));
        var result = discovery.execute(actor, 1, 1);
        assertThat(result.items()).containsExactly(first);
        assertThat(result.hasNext()).isTrue();
        verify(directory).list(actor, 2, 1);
    }
    @Test void disabledIdentityCannotUseDirectory() {
        assertThatThrownBy(() -> discovery.execute(actor, 0, 20)).isInstanceOf(IdentityFailure.class);
        verifyNoInteractions(directory);
    }
    @Test void emptyMembershipIsAValidEmptyPage() {
        when(identities.enabled(actor)).thenReturn(true);
        when(directory.list(actor, 21, 0)).thenReturn(List.of());
        var page = discovery.execute(actor, 0, 20);
        assertThat(page.items()).isEmpty(); assertThat(page.hasNext()).isFalse();
    }
    @Test void invalidAndOverflowingPagesFailBeforePersistence() {
        for (int[] input : new int[][]{{-1,20},{0,0},{0,101},{Integer.MAX_VALUE,100},{501,20}}) {
            assertThatThrownBy(() -> discovery.execute(actor,input[0],input[1])).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(identities, directory);
    }
}
