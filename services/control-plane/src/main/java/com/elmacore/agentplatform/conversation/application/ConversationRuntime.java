package com.elmacore.agentplatform.conversation.application;
import com.elmacore.agentplatform.execution.application.ExecutionRuntime;
import java.util.*;
public interface ConversationRuntime {
 record History(String question,String answer){@Override public String toString(){return "History[redacted]";}}
 record Request(UUID runId,UUID correlationId,String instructions,String message,List<ExecutionRuntime.Passage> passages,List<History> history){@Override public String toString(){return "ConversationExecution[redacted]";}}
 ExecutionRuntime.Response converse(Request request);
}
