package com.elmacore.agentplatform.conversation.domain;
import com.elmacore.agentplatform.execution.domain.RunResult;
import java.time.Instant;
import java.util.*;
public final class ConversationRecords {
 private ConversationRecords(){}
 public record Conversation(UUID id,UUID tenantId,UUID agentId,UUID createdBy,String title,String status,Instant createdAt,Instant updatedAt){@Override public String toString(){return "Conversation[redacted]";}}
 public record Message(UUID id,UUID requestId,long ordinal,String role,String content,String status,List<RunResult.Citation> citations,UUID sourceRunId,Instant createdAt){@Override public String toString(){return "ConversationMessage[redacted]";}}
 public record Turn(UUID requestId,UUID runId,String status,RunResult result){@Override public String toString(){return "ConversationTurn[redacted]";}}
 public record Reservation(UUID requestId,UUID runId,String question,boolean fresh){@Override public String toString(){return "Reservation[redacted]";}}
 public record Page<T>(List<T> items,int page,int size,boolean hasNext){@Override public String toString(){return "ConversationPage[redacted]";}}
 public static String title(String value){
  if(value==null)value="New conversation";value=value.strip();
  if(value.isBlank()||value.codePointCount(0,value.length())>120||!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value)||value.codePoints().anyMatch(c->Character.isISOControl(c)||(c>=0x202A&&c<=0x202E)||(c>=0x2066&&c<=0x2069)))throw new IllegalArgumentException("Invalid conversation title");
  return value;
 }
}
