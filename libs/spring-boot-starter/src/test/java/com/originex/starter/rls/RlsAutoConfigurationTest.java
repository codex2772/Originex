package com.originex.starter.rls;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Phase-0 gating guarantee: with {@code originex.rls.enabled=false}
 * (or unset) the RLS transaction manager is not contributed at all, so the
 * application uses Spring Boot's stock manager and behaviour is unchanged. No
 * database is required — a stub {@link EntityManagerFactory} is enough to prove
 * bean wiring (the real {@code set_config} behaviour is covered by the
 * Testcontainers integration test in CI).
 */
class RlsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RlsAutoConfiguration.class))
            .withUserConfiguration(StubEmfConfig.class);

    @Test
    void rlsTransactionManagerAbsentWhenDisabled() {
        runner.withPropertyValues("originex.rls.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(RlsTenantTransactionManager.class));
    }

    @Test
    void rlsTransactionManagerAbsentByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(RlsTenantTransactionManager.class));
    }

    @Test
    void rlsTransactionManagerReplacesTxManagerWhenEnabled() {
        runner.withPropertyValues("originex.rls.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RlsTenantTransactionManager.class);
                    assertThat(ctx.getBean(PlatformTransactionManager.class))
                            .isInstanceOf(RlsTenantTransactionManager.class);
                });
    }

    @Configuration
    static class StubEmfConfig {
        /**
         * A do-nothing EntityManagerFactory (JDK dynamic proxy) — enough for the
         * transaction-manager bean to be constructed without a real database, and
         * free of the JVM-version-sensitive bytecode mocking used by Mockito.
         */
        @Bean
        EntityManagerFactory entityManagerFactory() {
            return (EntityManagerFactory) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{EntityManagerFactory.class},
                    (proxy, method, args) -> {
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt.isPrimitive()) return 0;
                        return null;
                    });
        }
    }
}
