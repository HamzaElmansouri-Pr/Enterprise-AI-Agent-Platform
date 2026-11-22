package com.elmacore.agentplatform.conversation.application;
import com.elmacore.agentplatform.conversation.domain.ConversationRecords.*;
import java.util.*;
public interface ConversationStore {
 void context(UUID user);
 Conversation create(UUID tenant,UUID agent,UUID user,String title);
 Conversation get(UUID tenant,UUID agent,UUID user,UUID id,boolean lock);
 List<Conversation> list(UUID tenant,UUID agent,UUID user,int limit,long offset);
 Conversation change(UUID tenant,UUID agent,UUID user,UUID id,String title,boolean close);
 Reservation find(UUID tenant,UUID id,UUID request);
 Reservation reserve(UUID tenant,UUID agent,UUID user,UUID id,UUID request,String question);
 List<ConversationRuntime.History> history(UUID tenant,UUID id);
 List<Message> messages(UUID tenant,UUID id,int limit,long offset);
}
