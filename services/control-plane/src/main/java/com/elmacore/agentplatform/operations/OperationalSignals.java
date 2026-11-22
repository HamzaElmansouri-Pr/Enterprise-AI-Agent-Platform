package com.elmacore.agentplatform.operations;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Process-local bounded categories only; no arbitrary labels or private payloads. */
public final class OperationalSignals {
    public enum Event { REQUEST_OK, REQUEST_DENIED, REQUEST_FAILED, RATE_LIMITED,
        EXECUTION_SUCCEEDED, EXECUTION_FAILED, EXECUTION_RECOVERED, KNOWLEDGE_FAILED, TOOL_FAILED }
    private static final Map<Event,LongAdder> COUNTS=new EnumMap<>(Event.class);
    static { for(var event:Event.values()) COUNTS.put(event,new LongAdder()); }
    private OperationalSignals() {}
    public static void record(Event event,long count) { COUNTS.get(event).add(count); }
    public static void committed(Event event,long count) {
        if(count<1)return;
        if(TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){record(event,count);}
            });
    }
    public static Map<String,Long> snapshot() {
        var result=new TreeMap<String,Long>();COUNTS.forEach((event,n)->result.put(event.name(),n.sum()));return result;
    }
}
