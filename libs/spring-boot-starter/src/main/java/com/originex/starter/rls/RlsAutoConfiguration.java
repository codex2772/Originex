package com.originex.starter.rls;

import com.originex.starter.OriginexProperties;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires row-level-security enforcement. Only active when
 * {@code originex.rls.enabled=true}; when disabled (the default) this
 * configuration contributes nothing and Spring Boot's stock
 * {@code JpaTransactionManager} is used, so behaviour is unchanged.
 *
 * <p>Ordered {@code before} {@link HibernateJpaAutoConfiguration} so that our
 * {@code transactionManager} bean is registered first and Boot's
 * {@code @ConditionalOnMissingBean} default backs off. See {@code dev/RLS_DESIGN.md}.
 */
@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass(EntityManagerFactory.class)
@ConditionalOnProperty(prefix = "originex.rls", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OriginexProperties.class)
public class RlsAutoConfiguration {

    /**
     * Replaces the platform transaction manager with one that sets
     * {@code app.tenant_id} per transaction for RLS.
     */
    @Bean
    @ConditionalOnMissingBean(PlatformTransactionManager.class)
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory,
                                                         OriginexProperties properties) {
        return new RlsTenantTransactionManager(
                entityManagerFactory, properties.getRls().getSessionVariable());
    }
}
