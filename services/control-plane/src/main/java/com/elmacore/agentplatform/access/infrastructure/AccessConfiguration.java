package com.elmacore.agentplatform.access.infrastructure;
import com.elmacore.agentplatform.access.application.*;
import com.elmacore.agentplatform.identity.application.*;
import com.elmacore.agentplatform.tenancy.application.TenantTransactions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;
@Configuration(proxyBeanMethods=false)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class AccessConfiguration {
    @Bean AccessStore accessStore(DataSource source){return new JdbcAccessStore(source);}
    @Bean TenantAccessManagement tenantAccessManagement(MembershipAuthorization authorization,TenantTransactions transactions,AccessStore store,Passwords passwords){return new TenantAccessManagement(authorization,transactions,store,passwords);}
}
