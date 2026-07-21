package com.originex.starter.rls;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-0 gating + fail-loud behavior for the routing datasource. No real
 * database is contacted — Hikari is lazy, so a placeholder URL never connects
 * during context load. Actual routing/isolation is covered by the Testcontainers
 * integration test in CI.
 */
class RlsDataSourceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RlsDataSourceAutoConfiguration.class));

    @Test
    void routingDataSourceAbsentWhenDisabled() {
        runner.withPropertyValues("originex.rls.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(TenantRoutingDataSource.class));
    }

    @Test
    void routingDataSourceAbsentByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(TenantRoutingDataSource.class));
    }

    @Test
    void routingDataSourceIsPrimaryWhenEnabledAndConfigured() {
        runner.withPropertyValues(
                        "originex.rls.enabled=true",
                        "originex.rls.datasource.app.url=jdbc:postgresql://localhost:5432/db",
                        "originex.rls.datasource.app.username=originex_app",
                        "originex.rls.datasource.system.url=jdbc:postgresql://localhost:5432/db",
                        "originex.rls.datasource.system.username=originex_system")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TenantRoutingDataSource.class);
                    assertThat(ctx.getBean(DataSource.class)).isInstanceOf(TenantRoutingDataSource.class);
                });
    }

    @Test
    void failsLoudlyWhenEnabledButSystemBlockMissing() {
        runner.withPropertyValues(
                        "originex.rls.enabled=true",
                        "originex.rls.datasource.app.url=jdbc:postgresql://localhost:5432/db",
                        "originex.rls.datasource.app.username=originex_app")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .hasMessageContaining("originex.rls.datasource.system.url"));
    }
}
