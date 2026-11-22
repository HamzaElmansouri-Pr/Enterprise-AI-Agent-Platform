package com.elmacore.agentplatform.access.application;
import com.elmacore.agentplatform.access.domain.*;
import com.elmacore.agentplatform.access.domain.AccessRecords.*;
import com.elmacore.agentplatform.identity.application.*;
import com.elmacore.agentplatform.identity.domain.*;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import java.util.UUID;
import java.util.List;
public final class TenantAccessManagement {
    private final MembershipAuthorization authorization;private final TenantTransactions transactions;private final AccessStore store;private final Passwords passwords;
    public TenantAccessManagement(MembershipAuthorization authorization,TenantTransactions transactions,AccessStore store,Passwords passwords){this.authorization=authorization;this.transactions=transactions;this.store=store;this.passwords=passwords;}
    private AuthorizedTenant scope(UUID user,UUID tenant,boolean manager){var s=authorization.authorize(user,tenant);if(manager&&s.role()==Role.MEMBER)throw new AccessFailure("access_forbidden",403);return s;}
    private static long offset(int page,int size){if(page<0||size<1||size>100||(long)page*size>10000)throw new IllegalArgumentException("Invalid pagination");return (long)page*size;}
    private static <T> Page<T> page(List<T> rows,int page,int size){return new Page<>(List.copyOf(rows.subList(0,Math.min(size,rows.size()))),page,size,rows.size()>size);}
    public Page<Member> members(UUID user,UUID tenant,int page,int size){long offset=offset(page,size);var s=scope(user,tenant,false);return transactions.execute(s,()->page(store.members(tenant,user,size+1,offset),page,size));}
    public Page<Invitation> invitations(UUID user,UUID tenant,int page,int size){long offset=offset(page,size);var s=scope(user,tenant,true);return transactions.execute(s,()->page(store.invitations(tenant,user,size+1,offset),page,size));}
    public Issued invite(UUID user,UUID tenant,String email,String role,UUID correlation){
        String normalized=new Email(email).value();InvitationSecret.invitedRole(role);var s=scope(user,tenant,true);
        if(s.role()==Role.ADMIN&&!role.equals("MEMBER"))throw new AccessFailure("access_forbidden",403);
        String token=InvitationSecret.generate();byte[] hash=InvitationSecret.hash(token);UUID id=UUID.randomUUID();
        return transactions.execute(s,()->{store.invite(tenant,user,id,normalized,role,hash,correlation);return new Issued(store.invitation(tenant,user,id),token);});
    }
    public Invitation cancel(UUID user,UUID tenant,UUID id,UUID correlation){var s=scope(user,tenant,true);return transactions.execute(s,()->{store.cancel(tenant,user,id,correlation);return store.invitation(tenant,user,id);});}
    public void change(UUID user,UUID tenant,UUID target,String role,boolean revoke,UUID correlation){
        if(!revoke)InvitationSecret.invitedRole(role);var s=scope(user,tenant,true);
        if(user.equals(target)||(!revoke&&s.role()!=Role.OWNER))throw new AccessFailure("access_forbidden",403);
        transactions.execute(s,()->{store.change(tenant,user,target,role,revoke,correlation);return null;});
    }
    public Accepted acceptNew(String token,String email,String password,UUID correlation){
        InvitationSecret.newPassword(password);String normalized=new Email(email).value();byte[] hash=InvitationSecret.hash(token);
        // Hash outside every DB transaction. The API admission guard bounds hashing concurrency.
        return store.acceptNew(hash,normalized,passwords.encode(password),UUID.randomUUID(),correlation);
    }
    public Accepted acceptExisting(String token,UUID user,UUID correlation){return store.acceptExisting(InvitationSecret.hash(token),user,correlation);}
}
