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
    private SecurityProperties security = new SecurityProperties();
    private WebProperties web = new WebProperties();

    public TenantProperties getTenant() { return tenant; }
    public void setTenant(TenantProperties tenant) { this.tenant = tenant; }
    public KafkaProperties getKafka() { return kafka; }
    public void setKafka(KafkaProperties kafka) { this.kafka = kafka; }
    public RlsProperties getRls() { return rls; }
    public void setRls(RlsProperties rls) { this.rls = rls; }
    public SecurityProperties getSecurity() { return security; }
    public void setSecurity(SecurityProperties security) { this.security = security; }
    public WebProperties getWeb() { return web; }
    public void setWeb(WebProperties web) { this.web = web; }

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

    /**
     * Browser-facing web settings. Currently only CORS.
     */
    public static class WebProperties {
        private Cors cors = new Cors();

        public Cors getCors() { return cors; }
        public void setCors(Cors cors) { this.cors = cors; }

        /**
         * Cross-Origin Resource Sharing. <b>Fail-safe by default:</b>
         * {@code allowedOrigins} is empty, and the CORS auto-configuration is
         * gated on this property being present ({@code @ConditionalOnProperty}),
         * so with no configuration <i>no CORS beans exist</i> and browsers get no
         * {@code Access-Control-Allow-Origin} header — cross-origin is blocked.
         * Production sets nothing (off); <b>dev opts in</b> by listing explicit
         * origins (e.g. {@code http://localhost:3000,http://localhost:5173}).
         * Wildcards are never used — origins are matched exactly, and credentials
         * are allowed only against those explicit origins.
         */
        public static class Cors {
            private java.util.List<String> allowedOrigins = new java.util.ArrayList<>();

            public java.util.List<String> getAllowedOrigins() { return allowedOrigins; }
            public void setAllowedOrigins(java.util.List<String> allowedOrigins) {
                this.allowedOrigins = allowedOrigins;
            }
        }
    }

    /**
     * OAuth2 resource-server / authentication settings (see {@code dev/AUTH_DESIGN.md}).
     * Master switch is {@code enabled}; it defaults to {@code false} so the
     * security machinery is inert (no beans, no filter chain contributed) until a
     * service explicitly opts in during the phased rollout. When disabled, behaviour
     * is unchanged from today's header-based tenant resolution.
     *
     * <p>{@code issuerUri} (OIDC issuer, e.g. the Keycloak realm URL) or
     * {@code jwkSetUri} identifies the token signer; {@code audience}, when set, is
     * the expected {@code aud} claim for this service (per-service audience, so a
     * token minted for another service is not accepted here).
     */
    public static class SecurityProperties {
        private boolean enabled = false;
        private String issuerUri;
        private String jwkSetUri;
        private String audience;
        /**
         * Authentication posture when {@code enabled=true}. Defaults to
         * {@code ENFORCED} (fail-secure): enabling security without an explicit mode
         * requires authentication. Operators set {@code PERMISSIVE} deliberately for
         * the bounded migration observe window (see dev/AUTH_DESIGN.md §8.2).
         */
        private com.originex.starter.security.AuthMode mode =
                com.originex.starter.security.AuthMode.ENFORCED;
        private Permissive permissive = new Permissive();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
        public String getJwkSetUri() { return jwkSetUri; }
        public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public com.originex.starter.security.AuthMode getMode() { return mode; }
        public void setMode(com.originex.starter.security.AuthMode mode) { this.mode = mode; }
        public Permissive getPermissive() { return permissive; }
        public void setPermissive(Permissive permissive) { this.permissive = permissive; }
    }

    /**
     * Settings that apply only in {@code PERMISSIVE} mode during the migration
     * window (see dev/AUTH_DESIGN.md §8.2).
     */
    public static class Permissive {
        /**
         * Source networks (CIDR, e.g. {@code 10.0.0.0/8}) from which the legacy
         * {@code X-Tenant-Id} header fallback is honoured when no token is present.
         * Empty (the default) means the fallback is never applied — the network
         * restriction is fail-closed, so PERMISSIVE without an explicit allowlist
         * behaves like require-auth for the tenant source.
         */
        private java.util.List<String> trustedFallbackCidrs = new java.util.ArrayList<>();

        public java.util.List<String> getTrustedFallbackCidrs() { return trustedFallbackCidrs; }
        public void setTrustedFallbackCidrs(java.util.List<String> trustedFallbackCidrs) {
            this.trustedFallbackCidrs = trustedFallbackCidrs;
        }
    }
}
