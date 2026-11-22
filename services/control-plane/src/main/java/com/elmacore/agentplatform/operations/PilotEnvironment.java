package com.elmacore.agentplatform.operations;

import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Reject unsafe pilot configuration before normal logging/context initialization. */
public final class PilotEnvironment implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }
    @Override public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) { validate(env); }
    public static void validate(ConfigurableEnvironment env) {
        String mode=env.getProperty("EAAP_DEPLOYMENT_MODE", "development");
        var profiles=Set.of(env.getActiveProfiles());
        if(!Set.of("development","pilot","operator","offline-evaluation").contains(mode)) invalid();
        if(mode.equals("operator")) {
            if(profiles.size()!=1 || !(profiles.contains("migration") || profiles.contains("identity-bootstrap"))) invalid();
            return;
        }
        if(!mode.equals("pilot") && !profiles.contains("pilot")) return;
        if(!mode.equals("pilot") || !profiles.equals(Set.of("pilot"))) invalid();
        for(String name:List.of("AUTH_PRIVATE_KEY","AUTH_PUBLIC_KEY","AUTH_ISSUER","AUTH_AUDIENCE","AUTH_KEY_ID","DB_URL","DB_PASSWORD","EMBEDDING_SECRET","EXECUTION_INTERNAL_SECRET","OPERATIONS_SECRET","EAAP_RELEASE_SHA"))
            if(env.getProperty(name, "").isBlank()) invalid();
        if(!env.getProperty("EAAP_RELEASE_SHA").matches("[a-f0-9]{40}")) invalid();
        if(!env.getProperty("DB_URL").equals("jdbc:postgresql://postgres:5432/eaap_pilot")) invalid();
        if(!env.getProperty("spring.datasource.username","eaap_runtime").equals("eaap_runtime")) invalid();
        var values=new HashSet<String>();
        for(String name:List.of("DB_PASSWORD","EMBEDDING_SECRET","EXECUTION_INTERNAL_SECRET","OPERATIONS_SECRET")) {
            String value=env.getProperty(name);
            if(value.length()<32 || value.length()>128 || !value.matches("[!-~]+") || !values.add(value)) invalid();
            // Pilot credentials come from mounted config-tree files, never an env fallback.
            if(environmentHas(env,name)) invalid();
        }
        for(String name:List.of("AUTH_PRIVATE_KEY","AUTH_PUBLIC_KEY"))
            if(!env.getProperty(name).startsWith("file:/run/secrets/")) invalid();
        if(!env.getProperty("spring.config.import", "").equals("configtree:/run/secrets/")) invalid();
        if(!env.getProperty("management.server.port", "").equals("9090")
            || !env.getProperty("management.endpoints.web.exposure.include", "").equals("health,pilot")
            || !env.getProperty("server.request.header.forwarded.strategy", "").equals("none")
            || !env.getProperty("logging.level.root", "INFO").equalsIgnoreCase("INFO")
            || env.getProperty("debug",Boolean.class,false) || env.getProperty("trace",Boolean.class,false)) invalid();
        for(String name:List.of("SPRING_APPLICATION_JSON","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","OPENAI_API_KEY","MIGRATION_DB_PASSWORD","IDENTITY_OPERATOR_DB_PASSWORD"))
            if(environmentHas(env,name) && !name.equals("JAVA_TOOL_OPTIONS")) invalid();
        // Arbitrary JVM arguments and log-level overrides are outside this supported profile.
        if(!env.getProperty("JAVA_TOOL_OPTIONS", "-XX:MaxRAMPercentage=70").equals("-XX:MaxRAMPercentage=70")) invalid();
        for(String name:env.getSystemEnvironment().keySet())
            if(name.startsWith("LOGGING_LEVEL_") || name.startsWith("SPRING_FLYWAY_") || name.equals("JDK_JAVA_OPTIONS")) invalid();
        if(!env.getProperty("spring.flyway.enabled",Boolean.class,false).equals(false)) invalid();
    }
    private static boolean environmentHas(ConfigurableEnvironment env,String name) {
        var source=env.getPropertySources().get("systemEnvironment");
        return env.getSystemEnvironment().containsKey(name) || (source!=null && source.containsProperty(name));
    }
    private static void invalid() { throw new IllegalStateException("Unsafe pilot configuration; check the operator preflight"); }
}
