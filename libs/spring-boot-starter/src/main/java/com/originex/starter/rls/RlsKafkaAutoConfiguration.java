package com.originex.starter.rls;

import com.originex.starter.tenant.TenantRecordInterceptor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

/**
 * When RLS is enabled and Spring Kafka is present, attaches a
 * {@link TenantRecordInterceptor} to every {@link ConcurrentKafkaListenerContainerFactory}
 * so tenant context is established before each consumer transaction begins.
 *
 * <p>Uses a {@link BeanPostProcessor} to <i>mutate</i> the existing factory
 * rather than replace the {@code kafkaListenerContainerFactory} bean — this is
 * lower-risk (no bean-override or auto-configuration ordering games) and also
 * covers a service that defines its own factory. The interceptor is set during
 * bean initialization, before listener containers are created from the factory
 * at context refresh, so it applies to all listeners.
 *
 * <p>Entirely absent when {@code originex.rls.enabled=false} (the default), so
 * Kafka behaviour is unchanged in Phase 0.
 */
@AutoConfiguration
@ConditionalOnClass(ConcurrentKafkaListenerContainerFactory.class)
@ConditionalOnProperty(prefix = "originex.rls", name = "enabled", havingValue = "true")
public class RlsKafkaAutoConfiguration {

    @Bean
    static BeanPostProcessor rlsTenantRecordInterceptorRegistrar() {
        final TenantRecordInterceptor interceptor = new TenantRecordInterceptor();
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    ConcurrentKafkaListenerContainerFactory raw = factory;
                    raw.setRecordInterceptor(interceptor);
                }
                return bean;
            }
        };
    }
}
