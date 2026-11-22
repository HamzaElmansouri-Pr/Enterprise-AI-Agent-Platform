package com.elmacore.agentplatform.access;
import com.elmacore.agentplatform.access.domain.*;
import com.elmacore.agentplatform.access.api.AcceptanceProtection;
import com.elmacore.agentplatform.identity.application.IdentityFailure;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.assertj.core.api.Assertions.*;
class InvitationSecretTest {
 @Test void cryptographicTokensHaveUniqueCanonicalEntropyAndOnlyHashedRepresentation(){var tokens=new HashSet<String>();for(int n=0;n<1000;n++){String token=InvitationSecret.generate();assertThat(token).matches("[A-Za-z0-9_-]{43}");assertThat(InvitationSecret.hash(token)).hasSize(32);tokens.add(token);}assertThat(tokens).hasSize(1000);}
 @Test void weakOrMalformedPasswordsAndTokensAreRejected(){for(String password:new String[]{"short"," ".repeat(20),"x".repeat(129),"test-password-16\u0000"})assertThatThrownBy(()->InvitationSecret.newPassword(password)).isInstanceOf(IllegalArgumentException.class);for(String token:new String[]{"x","x".repeat(44),"!".repeat(43)})assertThatThrownBy(()->InvitationSecret.hash(token)).isInstanceOf(AccessFailure.class);}
 @Test void hashingConcurrencyIsBoundedWithoutBlocking(){var p=new AcceptanceProtection();p.enter("a");p.enter("b");assertThatThrownBy(()->p.enter("c")).isInstanceOf(IdentityFailure.class);p.leave();p.leave();p.enter("c");p.leave();}
 @Test void peerAndProcessAcceptanceRateAreBounded(){var p=new AcceptanceProtection();for(int n=0;n<5;n++){p.enter("peer");p.leave();}assertThatThrownBy(()->p.enter("peer")).isInstanceOf(IdentityFailure.class);for(int n=0;n<15;n++){p.enter("other"+n);p.leave();}assertThatThrownBy(()->p.enter("last")).isInstanceOf(IdentityFailure.class);}
}
