package com.originex.partner.security;

import com.originex.partner.application.port.in.PartnerIntegrationUseCase;
import com.originex.partner.domain.model.AadhaarVerificationResult;
import com.originex.partner.domain.model.BankAccountVerificationResult;
import com.originex.partner.domain.model.BureauReport;
import com.originex.partner.domain.model.PanVerificationResult;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the partner-integration authorization model: {@code partner:credit-pull} gates the credit-bureau
 * pull, {@code partner:verify} gates the three identity verifications, and the split holds <b>both ways</b>
 * (neither scope confers the other). Enforced on any implementing bean when security is enabled, inert while
 * disabled — which is why the gate is safe to add before the token-less S2S callers are fixed (threat T4).
 * Method security is under test, so this runs with no Docker and no JWT decode; commands are {@code null}
 * because the guard runs before the body.
 */
@DisplayName("partner-integration authorization (@PreAuthorize on the use-case port)")
class PartnerAuthorizationTest {

    private static final String CREDIT_PULL = "SCOPE_partner:credit-pull";
    private static final String VERIFY = "SCOPE_partner:verify";

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

    @Test
    @DisplayName("partner:credit-pull allows the bureau pull; verify does not grant it")
    void creditPullScope() {
        securityEnabled.run(ctx -> {
            PartnerIntegrationUseCase useCase = ctx.getBean(PartnerIntegrationUseCase.class);
            authenticate(CREDIT_PULL);
            assertThatCode(() -> useCase.pullCreditReport(null)).doesNotThrowAnyException();
            authenticate(VERIFY);
            assertThatThrownBy(() -> useCase.pullCreditReport(null)).isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    @DisplayName("partner:verify allows all three identity checks; credit-pull does not grant them")
    void verifyScope() {
        securityEnabled.run(ctx -> {
            PartnerIntegrationUseCase useCase = ctx.getBean(PartnerIntegrationUseCase.class);
            authenticate(VERIFY);
            assertThatCode(() -> {
                useCase.verifyAadhaar(null);
                useCase.verifyPan(null);
                useCase.verifyBankAccount(null);
            }).doesNotThrowAnyException();

            authenticate(CREDIT_PULL);
            assertThatThrownBy(() -> useCase.verifyAadhaar(null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> useCase.verifyPan(null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> useCase.verifyBankAccount(null)).isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    @DisplayName("no scope is denied on every operation — deny-by-default")
    void noScopeDenied() {
        securityEnabled.run(ctx -> {
            PartnerIntegrationUseCase useCase = ctx.getBean(PartnerIntegrationUseCase.class);
            authenticate(); // authenticated, no authorities
            assertThatThrownBy(() -> useCase.pullCreditReport(null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> useCase.verifyAadhaar(null)).isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    @DisplayName("security disabled: guards are inert — behaviour unchanged")
    void disabledSecurityIsInert() {
        securityDisabled.run(ctx -> {
            PartnerIntegrationUseCase useCase = ctx.getBean(PartnerIntegrationUseCase.class);
            authenticate(); // no scopes
            assertThatCode(() -> {
                useCase.pullCreditReport(null);
                useCase.verifyAadhaar(null);
                useCase.verifyPan(null);
                useCase.verifyBankAccount(null);
            }).doesNotThrowAnyException();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class StubUseCaseConfig {
        @Bean
        PartnerIntegrationUseCase partnerIntegrationUseCase() {
            return new StubPartnerUseCase();
        }
    }

    /** Minimal implementation — the port's annotations are what is under test. */
    static class StubPartnerUseCase implements PartnerIntegrationUseCase {
        @Override public BureauReport pullCreditReport(PullCreditReportCommand command) { return null; }
        @Override public AadhaarVerificationResult verifyAadhaar(VerifyAadhaarCommand command) { return null; }
        @Override public PanVerificationResult verifyPan(VerifyPanCommand command) { return null; }
        @Override public BankAccountVerificationResult verifyBankAccount(VerifyBankAccountCommand command) { return null; }
    }
}
