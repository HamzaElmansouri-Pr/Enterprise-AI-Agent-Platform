package com.elmacore.agentplatform.tool.application;
import com.elmacore.agentplatform.tool.domain.*;
import java.util.*;
import java.util.function.Supplier;

public interface ProposalStore {
    Proposal insert(UUID tenant,UUID agent,UUID creator,UUID id,ToolPayload payload,String digest,UUID correlation);
    Proposal get(UUID tenant,UUID id,UUID visibleCreator,boolean lock);
    List<Proposal> list(UUID tenant,UUID visibleCreator,int limit,long offset);
    void decide(UUID tenant,Proposal proposal,UUID actor,boolean approve,String reason,UUID correlation);
    void claim(UUID tenant,UUID id,UUID actor,UUID correlation);
    record Effect(UUID taskId,String failureCode) {}
    Effect attempt(Supplier<UUID> handler);
    void finish(UUID tenant,UUID id,Effect effect);
    List<FollowupTask> tasks(UUID tenant,int limit,long offset);
}
