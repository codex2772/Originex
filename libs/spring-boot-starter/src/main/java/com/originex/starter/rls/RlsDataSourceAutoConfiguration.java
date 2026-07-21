package com.originex.starter.rls;

import com.originex.starter.OriginexProperties;
import com.originex.starter.OriginexProperties.DataSourceConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Wires the {@link TenantRoutingDataSource} (app / system routes) as the primary
 * {@link DataSource} when {@code originex.rls.enabled=true}. Ordered before
 * {@link DataSourceAutoConfiguration} so Boot's default datasource backs off;
 * when RLS is disabled (the default) this configuration contributes nothing and
 * Boot's single autoconfigured datasource is used unchanged.
 *
 * <p>Fail-loud (see {@code dev/RLS_DESIGN.md} §7.3): if either the {@code app} or
 * {@code system} block is not configured (missing {@code url}/{@code username}),
 * the bean throws at startup naming the offending property instead of silently
 * degrading tenant isolation.
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@ConditionalOnClass({DataSource.class, HikariDataSource.class})
@ConditionalOnProperty(prefix = "originex.rls", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OriginexProperties.class)
public class RlsDataSourceAutoConfiguration {

    @Bean
    @Primary
    public DataSource dataSource(OriginexProperties properties) {
        OriginexProperties.Datasource ds = properties.getRls().getDatasource();
        DataSource app = build(ds.getApp(), "app");
        DataSource system = build(ds.getSystem(), "system");

        TenantRoutingDataSource routing = new TenantRoutingDataSource();
        routing.setTargetDataSources(Map.of(
                TenantRoutingDataSource.Route.APP, app,
                TenantRoutingDataSource.Route.SYSTEM, system));
        routing.setDefaultTargetDataSource(app);
        routing.setLenientFallback(false); // an unexpected key throws, never masks
        routing.afterPropertiesSet();
        return routing;
    }

    private static DataSource build(DataSourceConfig cfg, String which) {
        require(cfg.getUrl(), which, "url");
        require(cfg.getUsername(), which, "username");
        HikariDataSource hikari = new HikariDataSource(); // lazy — no connection until first use
        hikari.setJdbcUrl(cfg.getUrl());
        hikari.setUsername(cfg.getUsername());
        hikari.setPassword(cfg.getPassword());
        hikari.setMaximumPoolSize(cfg.getMaximumPoolSize());
        hikari.setPoolName(cfg.getPoolName() != null ? cfg.getPoolName() : "rls-" + which);
        // Bind String parameters as an unspecified PostgreSQL type so a raw-JSON
        // String (e.g. OutboxEventJpaEntity.metadataJson) is accepted by a jsonb
        // column instead of being rejected as "character varying". Preserves the
        // raw JSON (no re-serialization) and is scoped to these RLS pools.
        hikari.addDataSourceProperty("stringtype", "unspecified");
        return hikari;
    }

    private static void require(String value, String which, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "originex.rls.enabled=true but originex.rls.datasource." + which + "." + field
                            + " is not configured");
        }
    }
}
