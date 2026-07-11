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

        /**
         * Explicit datasource blocks used only when {@code enabled=true}. The app
         * block connects as the RLS-subject role; the system block as the
         * BYPASSRLS role for cross-tenant jobs.
         */
        private Datasource datasource = new Datasource();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSessionVariable() { return sessionVariable; }
        public void setSessionVariable(String sessionVariable) { this.sessionVariable = sessionVariable; }
        public Datasource getDatasource() { return datasource; }
        public void setDatasource(Datasource datasource) { this.datasource = datasource; }
    }

    /** The {@code app} (RLS-subject) and {@code system} (BYPASSRLS) datasources. */
    public static class Datasource {
        private DataSourceConfig app = new DataSourceConfig();
        private DataSourceConfig system = new DataSourceConfig();

        public DataSourceConfig getApp() { return app; }
        public void setApp(DataSourceConfig app) { this.app = app; }
        public DataSourceConfig getSystem() { return system; }
        public void setSystem(DataSourceConfig system) { this.system = system; }
    }

    /** Connection settings for one RLS datasource route. */
    public static class DataSourceConfig {
        private String url;
        private String username;
        private String password;
        private int maximumPoolSize = 10;
        private String poolName;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
        public String getPoolName() { return poolName; }
        public void setPoolName(String poolName) { this.poolName = poolName; }
    }
}
