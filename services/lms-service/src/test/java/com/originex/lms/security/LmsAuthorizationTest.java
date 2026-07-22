package com.originex.lms.security;

import com.originex.lms.application.port.in.LoanUseCase;
import com.originex.lms.domain.model.Loan;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the lms-service authorization model: the capability guards on the use-case port
 * ({@link LoanUseCase}) are enforced on any implementing bean, and inert while security is disabled.
 *
 * <p>The load-bearing assertion is the boundary between the two repayment scopes. {@code recordRepayment}
 * (human, {@code loans:repay-manual}) can mark a loan paid <b>without settlement proof</b>;
 * {@code allocateRepaymentFromPayment} (machine, {@code loans:service}) applies a settlement-backed
 * repayment. They are the same operation by name but not the same privilege — and the test proves the
 * split holds both ways, so a service-account holding {@code loans:service} can never reach the
 * fraud-sensitive unbacked path (KI-19). Commands are {@code null}: the guard runs before the body.
 */
@DisplayName("LMS authorization (@PreAuthorize on the use-case port)")
class LmsAuthorizationTest {

    private static final String READ = "SCOPE_loans:read";
    private static final String CREATE = "SCOPE_loans:create";
    private static final String DISBURSE = "SCOPE_loans:disburse";
    private static final String SERVICE = "SCOPE_loans:service";
    private static final String REPAY_MANUAL = "SCOPE_loans:repay-manual";

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
                new TestingAuthenticationToken("actor", "pw", authorities));
    }

    // ── reads ──

    @Test
    @DisplayName("read scope allows a read; no scope is denied")
    void readScope() {
        securityEnabled.run(ctx -> {
            LoanUseCase useCase = ctx.getBean(LoanUseCase.class);
            authenticate(READ);
            assertThatCode(() -> useCase.getLoan(UUID.randomUUID(), UUID.randomUUID())).doesNotThrowAnyException();
            authenticate(); // no scopes
            assertThatThrownBy(() -> useCase.getLoan(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    // ── create (machine) ──

    @Test
    @DisplayName("create scope allows loan creation; read does not grant it")
    void createScope() {
        securityEnabled.run(ctx -> {
            LoanUseCase useCase = ctx.getBean(LoanUseCase.class);
            authenticate(CREATE);
            assertThatCode(() -> useCase.createLoan(null)).doesNotThrowAnyException();
            authenticate(READ);
            assertThatThrownBy(() -> useCase.createLoan(null)).isInstanceOf(AccessDeniedException.class);
        });
    }

    // ── disburse (machine confirm + dead HTTP ops) ──

    @Test
    @DisplayName("disburse scope allows disbursement confirmation; create does not grant it")
    void disburseScope() {
        securityEnabled.run(ctx -> {
            LoanUseCase useCase = ctx.getBean(LoanUseCase.class);
            authenticate(DISBURSE);
            assertThatCode(() -> useCase.confirmDisbursementByPayment(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "utr")).doesNotThrowAnyException();
            authenticate(CREATE);
            assertThatThrownBy(() -> useCase.confirmDisbursementByPayment(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "utr"))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    // ── the two repayment privileges, and the boundary between them ──

    @Test
    @DisplayName("service scope allows the settlement-backed repayment")
    void serviceScopeAllowsBackedRepayment() {
        securityEnabled.run(ctx -> {
            LoanUseCase useCase = ctx.getBean(LoanUseCase.class);
            authenticate(SERVICE);
            assertThatCode(() -> useCase.allocateRepaymentFromPayment(
                    UUID.randomUUID(), UUID.randomUUID(), "1000", "INR")).doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("repay-manual scope allows the unbacked manual assertion")
    void repayManualScopeAllowsUnbackedAssertion() {
        securityEnabled.run(ctx -> {
            LoanUseCase useCase = ctx.getBean(LoanUseCase.class);
            authenticate(REPAY_MANUAL);
            assertThatCode(() -> useCase.recordRepayment(null)).doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("KEY: service (backed) does NOT grant repay-manual (unbacked) — the fraud-scope isolation")
    void serviceScopeDeniesUnbackedManualRepayment() {
        securityEnabled.run(ctx -> {
            LoanUseCase useCase = ctx.getBean(LoanUseCase.class);
            authenticate(SERVICE);
            assertThatThrownBy(() -> useCase.recordRepayment(null))
                    .as("a settlement-backed servicing grant must never confer the unbacked mark-paid capability")
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    @DisplayName("repay-manual does NOT grant the backed servicing path either (split cuts both ways)")
    void repayManualScopeDeniesBackedRepayment() {
        securityEnabled.run(ctx -> {
            LoanUseCase useCase = ctx.getBean(LoanUseCase.class);
            authenticate(REPAY_MANUAL);
            assertThatThrownBy(() -> useCase.allocateRepaymentFromPayment(
                    UUID.randomUUID(), UUID.randomUUID(), "1000", "INR"))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    @DisplayName("no machine scope (create/disburse/service) grants the human unbacked repay-manual")
    void machineScopesNeverGrantUnbackedRepayment() {
        securityEnabled.run(ctx -> {
            LoanUseCase useCase = ctx.getBean(LoanUseCase.class);
            for (String machineScope : new String[]{CREATE, DISBURSE, SERVICE}) {
                authenticate(machineScope);
                assertThatThrownBy(() -> useCase.recordRepayment(null))
                        .as("machine scope %s must not reach loans:repay-manual", machineScope)
                        .isInstanceOf(AccessDeniedException.class);
            }
        });
    }

    // ── opt-in default ──

    @Test
    @DisplayName("security disabled: guards are inert — behaviour unchanged")
    void disabledSecurityIsInert() {
        securityDisabled.run(ctx -> {
            LoanUseCase useCase = ctx.getBean(LoanUseCase.class);
            authenticate(); // no scopes
            assertThatCode(() -> {
                useCase.getLoan(UUID.randomUUID(), UUID.randomUUID());
                useCase.createLoan(null);
                useCase.recordRepayment(null);
                useCase.allocateRepaymentFromPayment(UUID.randomUUID(), UUID.randomUUID(), "1", "INR");
            }).doesNotThrowAnyException();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class StubUseCaseConfig {
        @Bean
        LoanUseCase loanUseCase() {
            return new StubLoanUseCase();
        }
    }

    /** Minimal implementation — the port's annotations are what is under test. */
    static class StubLoanUseCase implements LoanUseCase {
        @Override public Loan createLoan(CreateLoanCommand command) { return null; }
        @Override public Loan getLoan(UUID tenantId, UUID loanId) { return null; }
        @Override public Loan.RepaymentAllocation recordRepayment(RecordRepaymentCommand command) { return null; }
        @Override public Loan disburseLoan(UUID tenantId, UUID loanId, String beneficiaryAccount) { return null; }
        @Override public Loan confirmDisbursement(UUID tenantId, UUID loanId, UUID disbursementId, String paymentRef) { return null; }
        @Override public Loan confirmDisbursementByPayment(UUID tenantId, UUID loanId, UUID paymentOrderId, String utr) { return null; }
        @Override public Loan.RepaymentAllocation allocateRepaymentFromPayment(UUID tenantId, UUID loanId, String amount, String currency) { return null; }
    }
}
