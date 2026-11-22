package com.elmacore.agentplatform.knowledge.application;

import com.elmacore.agentplatform.identity.application.*;
import com.elmacore.agentplatform.identity.domain.Role;
import com.elmacore.agentplatform.knowledge.domain.*;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import java.util.List;
import java.util.UUID;

public final class Knowledge {
    private final MembershipAuthorization authorization;
    private final TenantTransactions transactions;
    private final KnowledgeStore store;
    private final Embeddings embeddings;
    public Knowledge(MembershipAuthorization authorization,TenantTransactions transactions,KnowledgeStore store,Embeddings embeddings) {
        this.authorization=authorization;this.transactions=transactions;this.store=store;this.embeddings=embeddings;
    }
    private AuthorizedTenant authorize(UUID user,UUID tenant,boolean write) {
        var scope=authorization.authorize(user,tenant);
        if(write && scope.role()==Role.MEMBER) throw new IdentityFailure(IdentityFailure.Reason.FORBIDDEN);
        return scope;
    }
    public KnowledgeDocument upload(UUID user,UUID tenant,Upload upload,UUID agent) {
        var scope=authorize(user,tenant,true);
        return transactions.execute(scope,()-> {
            if(agent!=null) store.draftAgent(tenant,agent);
            UUID id=UUID.randomUUID(); store.insert(tenant,id,upload);
            if(agent!=null) store.associate(tenant,id,agent);
            return store.get(tenant,id);
        });
    }
    public KnowledgeDocument get(UUID user,UUID tenant,UUID id) {
        var scope=authorize(user,tenant,false); return transactions.execute(scope,()->store.get(tenant,id));
    }
    public record Page(List<KnowledgeDocument> items,int page,int size,boolean hasNext) {
        @Override public String toString() { return "KnowledgePage[redacted]"; }
    }
    public Page list(UUID user,UUID tenant,int page,int size) {
        if(page<0 || size<1 || size>100 || (long)page*size>10000) throw new IllegalArgumentException("Invalid pagination");
        var scope=authorize(user,tenant,false);
        return transactions.execute(scope,()-> {
            var rows=store.list(tenant,size+1,(long)page*size);
            return new Page(List.copyOf(rows.subList(0,Math.min(size,rows.size()))),page,size,rows.size()>size);
        });
    }
    public KnowledgeDocument retry(UUID user,UUID tenant,UUID id) {
        var scope=authorize(user,tenant,true);
        return transactions.execute(scope,()-> {
            store.get(tenant,id).status().requireRetryable();
            if(!store.retry(tenant,id)) throw new KnowledgeFailure("document_not_failed",409);
            return store.get(tenant,id);
        });
    }
    public KnowledgeDocument associate(UUID user,UUID tenant,UUID document,UUID agent,boolean remove) {
        var scope=authorize(user,tenant,true);
        return transactions.execute(scope,()-> {
            var current=store.get(tenant,document);
            if(!remove && (current.agentIds().contains(agent) || store.associated(tenant,document,agent))) return current;
            if(remove) store.remove(tenant,document,agent);
            else { store.draftAgent(tenant,agent); store.associate(tenant,document,agent); }
            return store.get(tenant,document);
        });
    }
    public List<KnowledgeStore.Match> search(UUID user,UUID tenant,UUID agent,String query,Integer count) {
        int topK=count==null?5:count;
        if(query==null || query.codePointCount(0,query.length())>1000 || query.indexOf(0)>=0 || query.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)) || topK<1 || topK>10) throw new IllegalArgumentException("Invalid search");
        if(!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(query)) throw new IllegalArgumentException("Invalid query Unicode");
        var scope=authorize(user,tenant,false);
        transactions.execute(scope,()-> {store.draftAgent(tenant,agent);return null;});
        var vector=embeddings.embed("query",List.of(query)).getFirst();
        // No transaction is held during inference. Recheck lifecycle/associations at actual retrieval.
        return transactions.execute(scope,()-> {store.draftAgent(tenant,agent);return store.search(tenant,agent,vector,topK);});
    }
}
