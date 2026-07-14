package com.originex.starter;

import com.originex.starter.exception.GlobalExceptionHandler;
import com.originex.starter.tenant.TenantResolutionFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
     * Registers the legacy {@link TenantResolutionFilter}, which derives tenant
     * context from the {@code X-Tenant-Id} request header, at highest priority so
     * all downstream code has access to {@code TenantContext}.
     *
     * <p><b>Security boundary (see {@code dev/AUTH_DESIGN.md} §5).</b> This filter
     * is the pre-authentication, header-trust tenant source. It is contributed only
     * when {@code originex.security.enabled=false} (the default) — preserving today's
     * behaviour exactly. When security is enabled, the header path is <i>not</i>
     * registered: the verified JWT becomes the single, authoritative source of
     * tenant/subject context via {@code TenantClaimResolutionFilter} (inside the
     * Spring Security chain), and {@code X-Tenant-Id} is ignored. This removes the
     * ordering conflict whereby the header filter — running before the security
     * chain — could reject a valid token-bearing request that carries no header.
     *
     * <p>Enabling security therefore requires the OAuth2 resource-server dependency
     * (so {@code TenantClaimResolutionFilter} is wired); with security enabled but
     * no resource server present, no tenant is resolved (fail-closed).
     */
    @Bean
    @ConditionalOnProperty(prefix = "originex.security", name = "enabled",
            havingValue = "false", matchIfMissing = true)
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
