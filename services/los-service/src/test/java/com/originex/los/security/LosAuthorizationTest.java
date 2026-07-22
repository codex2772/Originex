package com.originex.los.security;

import com.originex.los.application.port.in.LoanApplicationUseCase;
import com.originex.los.domain.model.LoanApplication;
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
 * Verifies the los-service authorization model: the capability guards declared on
 * the <b>use-case port</b> ({@link LoanApplicationUseCase}) are enforced on any bean
 * that implements it, and are inert while security is disabled.
 *
 * <p>los is the first canary with a four-way privilege split, so the interesting
 * assertions are the <b>boundaries between adjacent privileges</b> — most of all
 * that {@code applications:underwrite} (running the checks) does <b>not</b> grant
 * {@code applications:decide} (approving the loan). That boundary is what makes
 * segregation of duties expressible at all; a test that only proved "the right
 * scope is accepted" would not prove the split is real.
 *
 * <p>A stub implementation is used deliberately: the annotations live on the port,
 * so enforcement must hold for <i>any</i> implementation, and the test stays free of
 * the real service's database and outbound adapters. Commands are passed as
 * {@code null} — the guard is evaluated before the method body, so a denied call
 * never dereferences them, and an allowed call hits only the stub's no-op body.
 */
@DisplayName("LOS authorization (@PreAuthorize on the use-case port)")
class LosAuthorizationTest {

    private static final String READ = "SCOPE_applications:read";
    private static final String SUBMIT = "SCOPE_applications:submit";
    private static final String UNDERWRITE = "SCOPE_applications:underwrite";
    private static final String DECIDE = "SCOPE_applications:decide";

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
                new TestingAuthenticationToken("officer", "pw", authorities));
    }

    // ── reads ──

    @Test
    @DisplayName("read scope allows a read")
    void readScopeAllowsRead() {
        securityEnabled.run(ctx -> {
            authenticate(READ);
            LoanApplicationUseCase useCase = ctx.getBean(LoanApplicationUseCase.class);
            assertThatCode(() -> useCase.getApplication(UUID.randomUUID(), UUID.randomUUID()))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("no scopes: a read is denied")
    void noScopeDeniesRead() {
        securityEnabled.run(ctx -> {
            authenticate(); // authenticated, but carries no scopes
            LoanApplicationUseCase useCase = ctx.getBean(LoanApplicationUseCase.class);
            assertThatThrownBy(() -> useCase.getApplication(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    // ── applicant self-service (submit) ──

    @Test
    @DisplayName("submit scope allows submission")
    void submitScopeAllowsSubmit() {
        securityEnabled.run(ctx -> {
            authenticate(SUBMIT);
            LoanApplicationUseCase useCase = ctx.getBean(LoanApplicationUseCase.class);
            assertThatCode(() -> useCase.submitApplication(null))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("read scope does NOT grant submit")
    void readScopeDeniesSubmit() {
        securityEnabled.run(ctx -> {
            authenticate(READ);
            LoanApplicationUseCase useCase = ctx.getBean(LoanApplicationUseCase.class);
            assertThatThrownBy(() -> useCase.submitApplication(null))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    // ── underwriting analysis (underwrite) ──

    @Test
    @DisplayName("underwrite scope allows initiating a credit check")
    void underwriteScopeAllowsCreditCheck() {
        securityEnabled.run(ctx -> {
            authenticate(UNDERWRITE);
            LoanApplicationUseCase useCase = ctx.getBean(LoanApplicationUseCase.class);
            assertThatCode(() -> useCase.initiateCreditCheck(UUID.randomUUID(), UUID.randomUUID(), "consent-1"))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("submit scope does NOT grant underwriting (an applicant cannot run their own checks)")
    void submitScopeDeniesUnderwrite() {
        securityEnabled.run(ctx -> {
            authenticate(SUBMIT);
            LoanApplicationUseCase useCase = ctx.getBean(LoanApplicationUseCase.class);
            assertThatThrownBy(() -> useCase.initiateCreditCheck(UUID.randomUUID(), UUID.randomUUID(), "consent-1"))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    // ── the decision (decide) — and the boundary that makes SoD expressible ──

    @Test
    @DisplayName("decide scope allows approve and reject")
    void decideScopeAllowsApproveAndReject() {
        securityEnabled.run(ctx -> {
            authenticate(DECIDE);
            LoanApplicationUseCase useCase = ctx.getBean(LoanApplicationUseCase.class);
            assertThatCode(() -> {
                useCase.approveAndGenerateOffer(null);
                useCase.rejectApplication(null);
            }).doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("underwrite scope does NOT grant the decision — running the checks is not authority to approve")
    void underwriteScopeDeniesDecide() {
        securityEnabled.run(ctx -> {
            authenticate(UNDERWRITE);
            LoanApplicationUseCase useCase = ctx.getBean(LoanApplicationUseCase.class);
            assertThatThrownBy(() -> useCase.approveAndGenerateOffer(null))
                    .as("the SoD-enabling boundary: analysis privilege must not confer decision privilege")
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> useCase.rejectApplication(null))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    @DisplayName("decide scope does NOT grant underwriting either (the split cuts both ways)")
    void decideScopeDeniesUnderwrite() {
        securityEnabled.run(ctx -> {
            authenticate(DECIDE);
            LoanApplicationUseCase useCase = ctx.getBean(LoanApplicationUseCase.class);
            assertThatThrownBy(() -> useCase.initiateCreditCheck(UUID.randomUUID(), UUID.randomUUID(), "consent-1"))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    // ── the SoD gap, asserted as known current behaviour (KI-16) ──

    @Test
    @DisplayName("KNOWN GAP: a principal holding both submit and decide CAN approve its own submission")
    void bothScopesCanSubmitAndApprove_openSelfApprovalGap() {
        securityEnabled.run(ctx -> {
            // A single persona granted BOTH scopes. Nothing in this pass forbids that: the realm
            // issues scopes ungated (role-gating deferred, KI-14), and @PreAuthorize gates
            // *capability*, not whether the same principal should exercise both on one application.
            authenticate(SUBMIT, DECIDE);
            LoanApplicationUseCase useCase = ctx.getBean(LoanApplicationUseCase.class);

            // Asserted as expected CURRENT behaviour, not the desired end state. Self-approval stays
            // OPEN until BOTH land: (b) role-gating stops issuing submit+decide to one persona, AND
            // (c) a per-application approver≠submitter binding. This test documents the live gap so
            // applications:decide is never mistaken for "approvals are controlled"; update it when
            // either control arrives. See KI-16.
            assertThatCode(() -> {
                useCase.submitApplication(null);
                useCase.approveAndGenerateOffer(null);
            }).as("both capabilities resolve for one principal — the self-approval gap is real and OPEN")
              .doesNotThrowAnyException();
        });
    }

    // ── the opt-in default ──

    @Test
    @DisplayName("security disabled: guards are inert — behaviour unchanged")
    void disabledSecurityIsInert() {
        securityDisabled.run(ctx -> {
            authenticate(); // no scopes at all
            LoanApplicationUseCase useCase = ctx.getBean(LoanApplicationUseCase.class);
            assertThatCode(() -> {
                useCase.getApplication(UUID.randomUUID(), UUID.randomUUID());
                useCase.submitApplication(null);
                useCase.initiateCreditCheck(UUID.randomUUID(), UUID.randomUUID(), "consent-1");
                useCase.approveAndGenerateOffer(null);
                useCase.rejectApplication(null);
            }).doesNotThrowAnyException();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class StubUseCaseConfig {
        @Bean
        LoanApplicationUseCase loanApplicationUseCase() {
            return new StubLoanApplicationUseCase();
        }
    }

    /** Minimal implementation — the port's annotations are what is under test. */
    static class StubLoanApplicationUseCase implements LoanApplicationUseCase {
        @Override public LoanApplication submitApplication(SubmitApplicationCommand command) { return null; }
        @Override public LoanApplication getApplication(UUID tenantId, UUID applicationId) { return null; }
        @Override public LoanApplication addDocument(AddDocumentCommand command) { return null; }
        @Override public LoanApplication acceptOffer(UUID tenantId, UUID applicationId) { return null; }
        @Override public LoanApplication withdrawApplication(UUID tenantId, UUID applicationId) { return null; }
        @Override public LoanApplication recordCreditResult(RecordCreditResultCommand command) { return null; }
        @Override public LoanApplication initiateCreditCheck(UUID tenantId, UUID applicationId, String consentArtifactId) { return null; }
        @Override public LoanApplication approveAndGenerateOffer(ApproveCommand command) { return null; }
        @Override public LoanApplication rejectApplication(RejectCommand command) { return null; }
    }
}
