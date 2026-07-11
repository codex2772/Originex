package com.originex.starter.rls;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Phase-0 gating of the Kafka interceptor wiring: the
 * BeanPostProcessor that attaches {@code TenantRecordInterceptor} is contributed
 * only when {@code originex.rls.enabled=true}, so Kafka behaviour is unchanged
 * when disabled. Actual interceptor attachment is covered by the Testcontainers
 * integration test in CI.
 */
class RlsKafkaAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RlsKafkaAutoConfiguration.class));

    @Test
    void registrarAbsentWhenDisabled() {
        runner.withPropertyValues("originex.rls.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("rlsTenantRecordInterceptorRegistrar"));
    }

    @Test
    void registrarAbsentByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean("rlsTenantRecordInterceptorRegistrar"));
    }

    @Test
    void registrarPresentWhenEnabled() {
        runner.withPropertyValues("originex.rls.enabled=true")
                .run(ctx -> assertThat(ctx).hasBean("rlsTenantRecordInterceptorRegistrar"));
    }
}
