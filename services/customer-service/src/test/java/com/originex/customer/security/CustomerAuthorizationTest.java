package com.originex.customer.security;

import com.originex.customer.application.port.in.CustomerUseCase;
import com.originex.customer.domain.model.Customer;
import com.originex.starter.security.SecurityAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the customer-service authorization foundation (see
 * {@code dev/AUTH_DESIGN.md} §4.5): the capability guards declared on the
 * <b>use-case port</b> ({@link CustomerUseCase}) are enforced on any bean that
 * implements it — reads need {@code SCOPE_customers:read}, writes need
 * {@code SCOPE_customers:write} — and are inert while security is disabled.
 *
 * <p>A stub implementation is used deliberately: the annotations live on the port,
 * so enforcement must hold for <i>any</i> implementation, and the test stays free
 * of the real service's database/adapter dependencies. No IdP is contacted (the
 * JWKS URI is only needed so the gated decoder bean can be built; it is fetched
 * lazily).
 */
@DisplayName("Customer authorization (@PreAuthorize on the use-case port)")
class CustomerAuthorizationTest {

    private static final String READ = "SCOPE_customers:read";
    private static final String WRITE = "SCOPE_customers:write";

    private final ApplicationContextRunner securityEnabled = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
            .withUserConfiguration(StubUseCaseConfig.class)
            .withPropertyValues(
                    "originex.security.enabled=true",
                    "originex.security.jwk-set-uri=https://idp.example.com/realms/originex/protocol/openid-connect/certs");

    private final ApplicationContextRunner securityDisabled = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
            .withUserConfiguration(StubUseCaseConfig.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "pw", authorities));
    }

    private static CustomerUseCase.RegisterCustomerCommand registerCommand() {
        return new CustomerUseCase.RegisterCustomerCommand(
                UUID.randomUUID(), "Alice", "Borrower", "alice@example.com",
                "9999999999", LocalDate.of(1990, 1, 1), "ABCDE1234F", "123412341234");
    }

    @Test
    @DisplayName("read scope allows a read")
    void readScopeAllowsRead() {
        securityEnabled.run(ctx -> {
            authenticate(READ);
            CustomerUseCase useCase = ctx.getBean(CustomerUseCase.class);
            assertThatCode(() -> useCase.getCustomer(UUID.randomUUID(), UUID.randomUUID()))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("no scopes: a read is denied")
    void noScopeDeniesRead() {
        securityEnabled.run(ctx -> {
            authenticate(); // authenticated, but carries no scopes
            CustomerUseCase useCase = ctx.getBean(CustomerUseCase.class);
            assertThatThrownBy(() -> useCase.getCustomer(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    @DisplayName("read scope does NOT grant a write")
    void readScopeDeniesWrite() {
        securityEnabled.run(ctx -> {
            authenticate(READ);
            CustomerUseCase useCase = ctx.getBean(CustomerUseCase.class);
            assertThatThrownBy(() -> useCase.registerCustomer(registerCommand()))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    @DisplayName("write scope allows a write")
    void writeScopeAllowsWrite() {
        securityEnabled.run(ctx -> {
            authenticate(WRITE);
            CustomerUseCase useCase = ctx.getBean(CustomerUseCase.class);
            assertThatCode(() -> useCase.registerCustomer(registerCommand()))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("write scope does NOT grant a read (scopes are distinct capabilities)")
    void writeScopeDeniesRead() {
        securityEnabled.run(ctx -> {
            authenticate(WRITE);
            CustomerUseCase useCase = ctx.getBean(CustomerUseCase.class);
            assertThatThrownBy(() -> useCase.getCustomer(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    @DisplayName("security disabled: guards are inert — behaviour unchanged")
    void disabledSecurityIsInert() {
        securityDisabled.run(ctx -> {
            authenticate(); // no scopes at all
            CustomerUseCase useCase = ctx.getBean(CustomerUseCase.class);
            assertThatCode(() -> {
                useCase.getCustomer(UUID.randomUUID(), UUID.randomUUID());
                useCase.registerCustomer(registerCommand());
            }).doesNotThrowAnyException();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class StubUseCaseConfig {
        @Bean
        CustomerUseCase customerUseCase() {
            return new StubCustomerUseCase();
        }
    }

    /** Minimal implementation — the port's annotations are what is under test. */
    static class StubCustomerUseCase implements CustomerUseCase {
        @Override public Customer registerCustomer(RegisterCustomerCommand command) { return null; }
        @Override public Customer getCustomer(UUID tenantId, UUID customerId) { return null; }
        @Override public Customer updateProfile(UpdateProfileCommand command) { return null; }
        @Override public Customer submitKyc(SubmitKycCommand command) { return null; }
        @Override public Customer completeKyc(UUID tenantId, UUID customerId, UUID kycRecordId) { return null; }
        @Override public Customer initiateAadhaarEkyc(InitiateAadhaarEkycCommand command) { return null; }
        @Override public Customer addBankAccount(AddBankAccountCommand command) { return null; }
        @Override public Customer verifyBankAccount(UUID tenantId, UUID customerId, UUID bankAccountId) { return null; }
    }
}
