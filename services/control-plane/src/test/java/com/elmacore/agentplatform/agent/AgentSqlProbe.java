package com.elmacore.agentplatform.agent;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** Test-only connection/SQL metadata. Never captures bound configuration content or credentials. */
final class AgentSqlProbe {
    record Event(String sql, int backend, String tenant) {}
    static final ConcurrentLinkedQueue<Event> EVENTS = new ConcurrentLinkedQueue<>();
    static volatile Consumer<String> hook;
    static List<Event> agents() { return EVENTS.stream().filter(e -> e.sql().contains("platform.agent_configurations")).toList(); }
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean static BeanPostProcessor trackRuntimeConnections() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof HikariDataSource source)) return bean;
                    return new DelegatingDataSource(source) {
                        @Override public Connection getConnection() throws SQLException {
                            var actual = super.getConnection();
                            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                                    (proxy, method, args) -> {
                                        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                                            try (var statement = actual.createStatement(); var row = statement.executeQuery("SELECT pg_backend_pid(), platform.current_tenant_id()")) {
                                                row.next(); EVENTS.add(new Event(sql, row.getInt(1), row.getString(2)));
                                            }
                                            var current = hook;
                                            if (current != null) current.accept(sql);
                                        }
                                        try { return method.invoke(actual, args); }
                                        catch (InvocationTargetException error) { throw error.getCause(); }
                                    });
                        }
                    };
                }
            };
        }
    }
}
