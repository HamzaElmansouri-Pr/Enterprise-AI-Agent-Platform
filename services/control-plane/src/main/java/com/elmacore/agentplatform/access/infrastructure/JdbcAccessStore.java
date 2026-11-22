package com.elmacore.agentplatform.access.infrastructure;
import com.elmacore.agentplatform.access.application.AccessStore;
import com.elmacore.agentplatform.access.domain.*;
import com.elmacore.agentplatform.access.domain.AccessRecords.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.DataAccessException;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
public final class JdbcAccessStore implements AccessStore {
    private final JdbcTemplate jdbc;private final TransactionTemplate transactions;
    public JdbcAccessStore(DataSource source){jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(3);jdbc.setExceptionTranslator((task,sql,error)->{throw failure(error);});transactions=new TransactionTemplate(new JdbcTransactionManager(source));transactions.setTimeout(5);}
    private static Instant instant(ResultSet rs,String field)throws SQLException{var time=rs.getTimestamp(field);return time==null?null:time.toInstant();}
    private static UUID id(ResultSet rs,String field)throws SQLException{return rs.getObject(field,UUID.class);}
    private static final RowMapper<Member> MEMBER=(rs,row)->new Member(id(rs,"user_id"),rs.getString("email"),rs.getString("role"),instant(rs,"created_at"));
    private static final RowMapper<Invitation> INVITATION=(rs,row)->new Invitation(id(rs,"id"),id(rs,"tenant_id"),rs.getString("email"),rs.getString("role"),id(rs,"invited_by"),rs.getString("status"),instant(rs,"created_at"),instant(rs,"expires_at"),rs.getBoolean("expired"),instant(rs,"accepted_at"),id(rs,"accepted_by"),instant(rs,"cancelled_at"),id(rs,"cancelled_by"));
    private static final RowMapper<Accepted> ACCEPTED=(rs,row)->new Accepted(id(rs,"invitation_id"),id(rs,"tenant_id"),id(rs,"user_id"),rs.getString("role"));
    private static AccessFailure failure(java.sql.SQLException error){
        String message=error.getMessage();
        if("P0001".equals(error.getSQLState())&&message!=null)for(var entry:java.util.Map.of("access_forbidden",403,"access_not_found",404,"access_invalid",400,"invitation_limit",429,"invitation_conflict",409,"membership_conflict",409,"invitation_unavailable",410).entrySet())if(message.equals(entry.getKey())||message.startsWith("ERROR: "+entry.getKey()+"\n")||message.equals("ERROR: "+entry.getKey()))return new AccessFailure(entry.getKey(),entry.getValue());
        return new AccessFailure("access_persistence_failure",500);
    }
    private <T>T safe(Supplier<T> work){try{return work.get();}catch(DataAccessException error){throw new AccessFailure("access_persistence_failure",500);}}
    private <T>List<T> rows(String sql,RowMapper<T> mapper,Object...args){
        // Bind directly: Spring TRACE parameter diagnostics must not receive invitation/password hashes.
        return safe(()->jdbc.query(connection->{var statement=connection.prepareStatement(sql);for(int n=0;n<args.length;n++){if(args[n] instanceof byte[] bytes)statement.setBytes(n+1,bytes);else statement.setObject(n+1,args[n]);}return statement;},mapper));
    }
    private void bound(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Access operation requires transaction");}
    public List<Member> members(UUID tenant,UUID actor,int limit,long offset){bound();return safe(()->rows("SELECT * FROM platform.access_members(?,?,?,?)",MEMBER,tenant,actor,limit,offset));}
    public List<Invitation> invitations(UUID tenant,UUID actor,int limit,long offset){bound();return safe(()->rows("SELECT * FROM platform.access_invitations(?,?,?,?)",INVITATION,tenant,actor,limit,offset));}
    public Invitation invitation(UUID tenant,UUID actor,UUID id){bound();return safe(()->rows("SELECT * FROM platform.access_invitation(?,?,?)",INVITATION,tenant,actor,id).stream().findFirst().orElseThrow(()->new AccessFailure("access_not_found",404)));}
    public void invite(UUID tenant,UUID actor,UUID id,String email,String role,byte[] hash,UUID correlation){bound();rows("SELECT platform.access_invite(?,?,?,?,?,?,?)",(rs,row)->rs.getObject(1),tenant,actor,id,email,role,hash,correlation);}
    public void cancel(UUID tenant,UUID actor,UUID id,UUID correlation){bound();rows("SELECT platform.access_cancel(?,?,?,?)",(rs,row)->rs.getObject(1),tenant,actor,id,correlation);}
    public void change(UUID tenant,UUID actor,UUID target,String role,boolean revoke,UUID correlation){bound();rows("SELECT platform.access_member_change(?,?,?,?,?,?)",(rs,row)->rs.getObject(1),tenant,actor,target,role,revoke,correlation);}
    private Accepted accept(Supplier<Accepted> work){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Acceptance cannot nest a tenant transaction");return transactions.execute(status->safe(work));}
    public Accepted acceptNew(byte[] hash,String email,String passwordHash,UUID user,UUID correlation){return accept(()->rows("SELECT * FROM platform.access_accept_new(?,?,?,?,?)",ACCEPTED,hash,email,passwordHash,user,correlation).getFirst());}
    public Accepted acceptExisting(byte[] hash,UUID actor,UUID correlation){return accept(()->rows("SELECT * FROM platform.access_accept_existing(?,?,?)",ACCEPTED,hash,actor,correlation).getFirst());}
}
