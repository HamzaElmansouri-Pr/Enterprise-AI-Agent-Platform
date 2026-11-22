package com.elmacore.agentplatform.conversation;
import com.elmacore.agentplatform.conversation.domain.ConversationRecords;
import com.elmacore.agentplatform.conversation.application.ConversationRuntime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ConversationDomainTest {
 @Test void titleDefaultsTrimsAndCountsUnicode(){assertThat(ConversationRecords.title(null)).isEqualTo("New conversation");assertThat(ConversationRecords.title("  Admissions  ")).isEqualTo("Admissions");assertThat(ConversationRecords.title("😀".repeat(120))).isEqualTo("😀".repeat(120));}
 @Test void unsafeOrOversizedTitlesAreRejected(){for(String title:new String[]{" ","x".repeat(121),"\uD800","a\0b","a\nb","a\u202Eb"})assertThatThrownBy(()->ConversationRecords.title(title)).isInstanceOf(IllegalArgumentException.class);}
 @Test void historyDiagnosticsAreRedacted(){assertThat(new ConversationRuntime.History("private-question","private-answer").toString()).doesNotContain("private-question","private-answer");}
}
