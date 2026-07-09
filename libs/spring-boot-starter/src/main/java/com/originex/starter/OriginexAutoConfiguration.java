package com.originex.starter;

import com.originex.starter.exception.GlobalExceptionHandler;
import com.originex.starter.tenant.TenantResolutionFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Auto-configuration for Originex platform cross-cutting concerns.
 * Automatically applied to any Spring Boot service that includes originex-spring-boot-starter.
 */
@AutoConfiguration
@EnableConfigurationProperties(OriginexProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OriginexAutoConfiguration {

    /**
     * Registers the tenant resolution filter at highest priority
     * so all downstream code has access to TenantContext.
     */
    @Bean
    public FilterRegistrationBean<TenantResolutionFilter> tenantResolutionFilter(
            OriginexProperties properties) {

        TenantResolutionFilter filter = new TenantResolutionFilter(
                properties.getTenant().getHeaderName(),
                properties.getTenant().isEnforce()
        );

        FilterRegistrationBean<TenantResolutionFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        registration.setName("tenantResolutionFilter");
        return registration;
    }

    /**
     * Global exception handler providing RFC 7807 error responses.
     */
    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
