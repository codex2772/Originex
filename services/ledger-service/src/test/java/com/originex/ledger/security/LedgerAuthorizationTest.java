package com.originex.ledger.security;

import com.originex.ledger.application.port.in.LedgerUseCase;
import com.originex.ledger.domain.model.Account;
import com.originex.ledger.domain.model.JournalEntry;
import com.originex.starter.security.MachineActorContext;
import com.originex.starter.security.OriginexScopes;
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
 * Ledger authorization (canary #2). The capability guards on the use-case port
 * ({@link LedgerUseCase}) are enforced on any implementing bean and inert while security is
 * disabled. Establishes two precedents beyond customer's read/write:
 * <ul>
 *   <li><b>elevated corrective scope</b> — {@code ledger:post} does <i>not</i> grant
 *       {@code ledger:reverse}; reversing a committed entry is a distinct, higher privilege.</li>
 *   <li><b>minimally-scoped machine actor</b> — the {@link MachineActorContext} principal used by the
 *       Kafka consumer carries {@code ledger:post} only: it can post, but is denied reverse (no
 *       god-mode system authority).</li>
 * </ul>
 */
@DisplayName("Ledger authorization (@PreAuthorize on the use-case port)")
class LedgerAuthorizationTest {

    private static final String READ = OriginexScopes.authority(OriginexScopes.LEDGER_READ);
    private static final String POST = OriginexScopes.authority(OriginexScopes.LEDGER_POST);
    private static final String REVERSE = OriginexScopes.authority(OriginexScopes.LEDGER_REVERSE);

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
                new TestingAuthenticationToken("finance-op", "pw", authorities));
    }

    // ── read ──
    @Test @DisplayName("read scope allows getAccount")
    void readAllowsRead() {
        securityEnabled.run(ctx -> {
            authenticate(READ);
            assertThatCode(() -> ctx.getBean(LedgerUseCase.class).getAccount(UUID.randomUUID(), UUID.randomUUID()))
                    .doesNotThrowAnyException();
        });
    }

    @Test @DisplayName("no scope: read denied")
    void noScopeDeniesRead() {
        securityEnabled.run(ctx -> {
            authenticate();
            assertThatThrownBy(() -> ctx.getBean(LedgerUseCase.class).getAccount(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test @DisplayName("read scope does NOT grant post")
    void readDeniesPost() {
        securityEnabled.run(ctx -> {
            authenticate(READ);
            assertThatThrownBy(() -> ctx.getBean(LedgerUseCase.class).postJournalEntry(null))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    // ── post ──
    @Test @DisplayName("post scope allows post + openAccount")
    void postAllowsPost() {
        securityEnabled.run(ctx -> {
            authenticate(POST);
            LedgerUseCase uc = ctx.getBean(LedgerUseCase.class);
            assertThatCode(() -> { uc.postJournalEntry(null); uc.openAccount(null); })
                    .doesNotThrowAnyException();
        });
    }

    // ── the elevated-corrective precedent: post ≠ reverse ──
    @Test @DisplayName("post scope does NOT grant reverse (reversal is elevated)")
    void postDeniesReverse() {
        securityEnabled.run(ctx -> {
            authenticate(POST);
            assertThatThrownBy(() -> ctx.getBean(LedgerUseCase.class).reverseEntry(UUID.randomUUID(), UUID.randomUUID(), "r"))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test @DisplayName("reverse scope allows reverse")
    void reverseAllowsReverse() {
        securityEnabled.run(ctx -> {
            authenticate(REVERSE);
            assertThatCode(() -> ctx.getBean(LedgerUseCase.class).reverseEntry(UUID.randomUUID(), UUID.randomUUID(), "r"))
                    .doesNotThrowAnyException();
        });
    }

    @Test @DisplayName("reverse scope does NOT grant post")
    void reverseDeniesPost() {
        securityEnabled.run(ctx -> {
            authenticate(REVERSE);
            assertThatThrownBy(() -> ctx.getBean(LedgerUseCase.class).postJournalEntry(null))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    // ── the minimally-scoped machine actor: post yes, reverse no ──
    @Test @DisplayName("machine actor (consumer) can post but is denied reverse — minimal, not god-mode")
    void machineActorIsMinimallyScoped() {
        securityEnabled.run(ctx -> {
            SecurityContextHolder.getContext().setAuthentication(
                    MachineActorContext.machineAuthentication(OriginexScopes.LEDGER_POST));
            LedgerUseCase uc = ctx.getBean(LedgerUseCase.class);
            assertThatCode(() -> uc.postJournalEntry(null)).doesNotThrowAnyException();
            assertThatThrownBy(() -> uc.reverseEntry(UUID.randomUUID(), UUID.randomUUID(), "r"))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test @DisplayName("security disabled: guards inert")
    void disabledInert() {
        securityDisabled.run(ctx -> {
            authenticate();
            LedgerUseCase uc = ctx.getBean(LedgerUseCase.class);
            assertThatCode(() -> {
                uc.getAccount(UUID.randomUUID(), UUID.randomUUID());
                uc.postJournalEntry(null);
                uc.reverseEntry(UUID.randomUUID(), UUID.randomUUID(), "r");
            }).doesNotThrowAnyException();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class StubUseCaseConfig {
        @Bean
        LedgerUseCase ledgerUseCase() {
            return new StubLedgerUseCase();
        }
    }

    /** Minimal implementation — the port's annotations are what is under test. */
    static class StubLedgerUseCase implements LedgerUseCase {
        @Override public Account openAccount(OpenAccountCommand command) { return null; }
        @Override public Account getAccount(UUID tenantId, UUID accountId) { return null; }
        @Override public JournalEntry postJournalEntry(PostJournalEntryCommand command) { return null; }
        @Override public JournalEntry reverseEntry(UUID tenantId, UUID entryId, String reason) { return null; }
    }
}
