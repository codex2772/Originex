package com.originex.starter.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the RBAC enforcement mechanism: when security is enabled, method
 * security is active and a {@code @PreAuthorize} method denies a principal that
 * lacks the required authority (deny-by-default at the method level); when security
 * is disabled, method security is not contributed and the method runs unguarded
 * (behaviour unchanged). No IdP is contacted (JWKS is fetched lazily).
 */
@DisplayName("Method security (RBAC enforcement)")
class MethodSecurityTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
            .withUserConfiguration(SecuredBeanConfig.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", "pw", authorities));
    }

    @Test
    @DisplayName("enabled: a principal missing the required scope is denied")
    void deniedWhenMissingAuthority() {
        runner.withPropertyValues(
                        "originex.security.enabled=true",
                        "originex.security.jwk-set-uri=https://idp.example.com/certs")
                .run(ctx -> {
                    authenticate("SCOPE_customers:read"); // authenticated, but not loans:disburse
                    SecuredBean bean = ctx.getBean(SecuredBean.class);
                    assertThatThrownBy(bean::disburse).isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    @DisplayName("enabled: a principal with the required scope is allowed")
    void allowedWhenHasAuthority() {
        runner.withPropertyValues(
                        "originex.security.enabled=true",
                        "originex.security.jwk-set-uri=https://idp.example.com/certs")
                .run(ctx -> {
                    authenticate("SCOPE_loans:disburse");
                    assertThat(ctx.getBean(SecuredBean.class).disburse()).isEqualTo("ok");
                });
    }

    @Test
    @DisplayName("disabled: method security is inert — the guarded method runs unguarded")
    void inertWhenDisabled() {
        runner.run(ctx -> { // default: originex.security.enabled=false
            authenticate("SCOPE_customers:read"); // wrong scope, yet not enforced
            assertThat(ctx.getBean(SecuredBean.class).disburse()).isEqualTo("ok");
        });
    }

    @Configuration
    static class SecuredBeanConfig {
        @Bean
        SecuredBean securedBean() {
            return new SecuredBean();
        }
    }

    static class SecuredBean {
        @PreAuthorize("hasAuthority('SCOPE_loans:disburse')")
        public String disburse() {
            return "ok";
        }
    }
}
