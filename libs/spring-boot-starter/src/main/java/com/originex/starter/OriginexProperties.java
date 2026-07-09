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

    public TenantProperties getTenant() { return tenant; }
    public void setTenant(TenantProperties tenant) { this.tenant = tenant; }
    public KafkaProperties getKafka() { return kafka; }
    public void setKafka(KafkaProperties kafka) { this.kafka = kafka; }

    public static class TenantProperties {
        private String headerName = "X-Tenant-Id";
        private boolean enforce = true;

        public String getHeaderName() { return headerName; }
        public void setHeaderName(String headerName) { this.headerName = headerName; }
        public boolean isEnforce() { return enforce; }
        public void setEnforce(boolean enforce) { this.enforce = enforce; }
    }

    public static class KafkaProperties {
        private String schemaRegistryUrl = "http://localhost:8081";

        public String getSchemaRegistryUrl() { return schemaRegistryUrl; }
        public void setSchemaRegistryUrl(String schemaRegistryUrl) { this.schemaRegistryUrl = schemaRegistryUrl; }
    }
}
