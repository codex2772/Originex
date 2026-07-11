package com.originex.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Originex platform starter.
 * Prefix: originex.*
 */
@ConfigurationProperties(prefix = "originex")
public class OriginexProperties {

    private TenantProperties tenant = new TenantProperties();
    private KafkaProperties kafka = new KafkaProperties();
    private RlsProperties rls = new RlsProperties();

    public TenantProperties getTenant() { return tenant; }
    public void setTenant(TenantProperties tenant) { this.tenant = tenant; }
    public KafkaProperties getKafka() { return kafka; }
    public void setKafka(KafkaProperties kafka) { this.kafka = kafka; }
    public RlsProperties getRls() { return rls; }
    public void setRls(RlsProperties rls) { this.rls = rls; }

    public static class TenantProperties {
        private String headerName = "X-Tenant-Id";
        private boolean enforce = true;

        public String getHeaderName() { return headerName; }
        public void setHeaderName(String headerName) { this.headerName = headerName; }
        public boolean isEnforce() { return enforce; }
        public void setEnforce(boolean enforce) { this.enforce = enforce; }
    }

    public static class KafkaProperties {
        // Default matches dev/docker-compose.yml's schema-registry host port
        // mapping (8090:8081) — the container listens on 8081 internally,
        // but this default is a host-side URL for services run outside
        // Docker (e.g. `mvn spring-boot:run`), so it must use the host port.
        private String schemaRegistryUrl = "http://localhost:8090";

        public String getSchemaRegistryUrl() { return schemaRegistryUrl; }
        public void setSchemaRegistryUrl(String schemaRegistryUrl) { this.schemaRegistryUrl = schemaRegistryUrl; }
    }

    /**
     * Row-level security enforcement (see {@code dev/RLS_DESIGN.md}). Master
     * switch is {@code enabled}; it defaults to {@code false} so that all RLS
     * machinery is inert (the beans are not created) until a service explicitly
     * opts in during the phased rollout.
     */
    public static class RlsProperties {

        /**
         * When false (default), no RLS machinery is wired and the application
         * behaves exactly as before. When true, {@code app.tenant_id} is set per
         * transaction and cross-tenant jobs route to the BYPASSRLS datasource.
         */
        private boolean enabled = false;

        /**
         * The PostgreSQL session variable the RLS policies read via
         * {@code current_setting(...)}. Matches the policy migrations.
         */
        private String sessionVariable = "app.tenant_id";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSessionVariable() { return sessionVariable; }
        public void setSessionVariable(String sessionVariable) { this.sessionVariable = sessionVariable; }
    }
}
