package com.originex.bre.security;

import com.originex.bre.application.port.in.EvaluationUseCase;
import com.originex.bre.domain.model.EvaluationResult;
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
 * Verifies the bre-service authorization model: the {@code decisioning:evaluate} guard on
 * {@link EvaluationUseCase#evaluate} is enforced on any implementing bean when security is enabled, and
 * inert while it is disabled (the default — which is why the gate is safe to add before the token-less
 * los→bre S2S path is fixed; see the port's javadoc on threat T4).
 *
 * <p>Proven under enforcement here so the gate is not merely declared: with the scope the call passes,
 * without it the call is denied before the body runs (the command is {@code null}). Method security, not a
 * network round-trip, is under test — so this runs with no Docker and no JWT decode.
 */
@DisplayName("BRE authorization (@PreAuthorize on the use-case port)")
class BreAuthorizationTest {

    private static final String EVALUATE = "SCOPE_decisioning:evaluate";
    /** A neighbouring scope that must NOT grant evaluate — deny-by-default is not "any authenticated". */
    private static final String UNRELATED = "SCOPE_loans:read";

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
    @DisplayName("decisioning:evaluate allows an evaluation")
    void evaluateScopeAllows() {
        securityEnabled.run(ctx -> {
            EvaluationUseCase useCase = ctx.getBean(EvaluationUseCase.class);
            authenticate(EVALUATE);
            assertThatCode(() -> useCase.evaluate(null)).doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("no scope is denied — the gate is real, not decorative")
    void noScopeDenied() {
        securityEnabled.run(ctx -> {
            EvaluationUseCase useCase = ctx.getBean(EvaluationUseCase.class);
            authenticate(); // authenticated, but no authorities
            assertThatThrownBy(() -> useCase.evaluate(null)).isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    @DisplayName("an unrelated scope does not grant evaluate")
    void unrelatedScopeDenied() {
        securityEnabled.run(ctx -> {
            EvaluationUseCase useCase = ctx.getBean(EvaluationUseCase.class);
            authenticate(UNRELATED);
            assertThatThrownBy(() -> useCase.evaluate(null)).isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    @DisplayName("security disabled: the guard is inert — behaviour unchanged")
    void disabledSecurityIsInert() {
        securityDisabled.run(ctx -> {
            EvaluationUseCase useCase = ctx.getBean(EvaluationUseCase.class);
            authenticate(); // no scopes
            assertThatCode(() -> useCase.evaluate(null)).doesNotThrowAnyException();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class StubUseCaseConfig {
        @Bean
        EvaluationUseCase evaluationUseCase() {
            return new StubEvaluationUseCase();
        }
    }

    /** Minimal implementation — the port's annotation is what is under test. */
    static class StubEvaluationUseCase implements EvaluationUseCase {
        @Override public EvaluationResult evaluate(EvaluateCommand command) { return null; }
    }
}
