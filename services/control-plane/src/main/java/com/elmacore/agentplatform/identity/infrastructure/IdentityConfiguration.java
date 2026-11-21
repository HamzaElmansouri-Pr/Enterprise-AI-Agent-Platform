package com.elmacore.agentplatform.identity.infrastructure;

import com.elmacore.agentplatform.identity.application.IdentityStore;
import com.elmacore.agentplatform.identity.application.Passwords;
import com.elmacore.agentplatform.identity.application.Login;
import com.elmacore.agentplatform.identity.application.TenantRegistry;
import com.elmacore.agentplatform.identity.application.TenantContext;
import com.elmacore.agentplatform.identity.application.MembershipAuthorization;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class IdentityConfiguration {
    @Bean IdentityStore identities(JdbcTemplate jdbc) { return new JdbcIdentityStore(jdbc); }
    @Bean Passwords passwords() { return new ArgonPasswords(); }
    @Bean JwtTokens tokens(Environment environment, ResourceLoader resources) { return new JwtTokens(environment, resources); }
    @Bean Login login(IdentityStore identities, Passwords passwords, JwtTokens tokens) {
        return new Login(identities, passwords, tokens);
    }
    @Bean TenantRegistry tenantRegistry(DataSource source) { return new JdbcTenantRegistry(source); }
    @Bean MembershipAuthorization membershipAuthorization(IdentityStore identities) {
        return new MembershipAuthorization(identities);
    }
    @Bean TenantContext tenantContext(MembershipAuthorization authorization, TenantRegistry registry) {
        return new TenantContext(authorization, registry);
    }
}
