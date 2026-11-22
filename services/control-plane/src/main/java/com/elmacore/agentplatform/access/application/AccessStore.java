package com.elmacore.agentplatform.access.application;
import com.elmacore.agentplatform.access.domain.AccessRecords.*;
import java.util.List;
import java.util.UUID;
public interface AccessStore {
    List<Member> members(UUID tenant,UUID actor,int limit,long offset);
    List<Invitation> invitations(UUID tenant,UUID actor,int limit,long offset);
    Invitation invitation(UUID tenant,UUID actor,UUID id);
    void invite(UUID tenant,UUID actor,UUID id,String email,String role,byte[] hash,UUID correlation);
    void cancel(UUID tenant,UUID actor,UUID id,UUID correlation);
    void change(UUID tenant,UUID actor,UUID target,String role,boolean revoke,UUID correlation);
    Accepted acceptNew(byte[] hash,String email,String passwordHash,UUID user,UUID correlation);
    Accepted acceptExisting(byte[] hash,UUID actor,UUID correlation);
}
