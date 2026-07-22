package com.originex.payment.security;

import com.originex.payment.application.port.in.PaymentUseCase;
import com.originex.payment.domain.model.NachMandate;
import com.originex.payment.domain.model.PaymentOrder;
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
 * Payment authorization (canary #3). Exercises the fuller scope model: {@code payments:read},
 * {@code payments:disburse} (money-out, dual-called), {@code payments:initiate} (collection/inbound/
 * mandate), and the machine-only {@code payments:callback} (gateway webhook). Also proves the
 * consumer's machine actor is minimally scoped to {@code payments:disburse} — it cannot initiate a
 * collection or accept a callback.
 */
@DisplayName("Payment authorization (@PreAuthorize on the use-case port)")
class PaymentAuthorizationTest {

    private static final String READ = OriginexScopes.authority(OriginexScopes.PAYMENTS_READ);
    private static final String INITIATE = OriginexScopes.authority(OriginexScopes.PAYMENTS_INITIATE);
    private static final String DISBURSE = OriginexScopes.authority(OriginexScopes.PAYMENTS_DISBURSE);
    private static final String CALLBACK = OriginexScopes.authority(OriginexScopes.PAYMENTS_CALLBACK);

    private final ApplicationContextRunner securityEnabled = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
            .withUserConfiguration(StubConfig.class)
            .withPropertyValues(
                    "originex.security.enabled=true",
                    "originex.security.jwk-set-uri=https://idp.example.com/realms/originex/protocol/openid-connect/certs");

    private final ApplicationContextRunner securityDisabled = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class))
            .withUserConfiguration(StubConfig.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("ops", "pw", authorities));
    }

    @Test @DisplayName("read allows getPaymentOrder; no scope denies")
    void read() {
        securityEnabled.run(ctx -> {
            PaymentUseCase uc = ctx.getBean(PaymentUseCase.class);
            authenticate(READ);
            assertThatCode(() -> uc.getPaymentOrder(UUID.randomUUID(), UUID.randomUUID())).doesNotThrowAnyException();
            authenticate();
            assertThatThrownBy(() -> uc.getPaymentOrder(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test @DisplayName("disburse allows initiateDisbursement; and is distinct from initiate/callback")
    void disburse() {
        securityEnabled.run(ctx -> {
            PaymentUseCase uc = ctx.getBean(PaymentUseCase.class);
            authenticate(DISBURSE);
            assertThatCode(() -> uc.initiateDisbursement(null)).doesNotThrowAnyException();
            assertThatThrownBy(() -> uc.triggerNachCollection(null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> uc.handlePaymentCallback(null)).isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test @DisplayName("initiate allows collection/mandate; does NOT grant disburse")
    void initiate() {
        securityEnabled.run(ctx -> {
            PaymentUseCase uc = ctx.getBean(PaymentUseCase.class);
            authenticate(INITIATE);
            assertThatCode(() -> { uc.triggerNachCollection(null); uc.registerNachMandate(null); uc.recordInboundPayment(null); })
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> uc.initiateDisbursement(null)).isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test @DisplayName("callback (machine-only) allows the webhook; other scopes do not")
    void callback() {
        securityEnabled.run(ctx -> {
            PaymentUseCase uc = ctx.getBean(PaymentUseCase.class);
            authenticate(CALLBACK);
            assertThatCode(() -> uc.handlePaymentCallback(null)).doesNotThrowAnyException();
            authenticate(DISBURSE);
            assertThatThrownBy(() -> uc.handlePaymentCallback(null)).isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test @DisplayName("machine actor is minimal — payments:disburse only, not collection or callback")
    void machineActorMinimal() {
        securityEnabled.run(ctx -> {
            PaymentUseCase uc = ctx.getBean(PaymentUseCase.class);
            SecurityContextHolder.getContext().setAuthentication(
                    MachineActorContext.machineAuthentication(OriginexScopes.PAYMENTS_DISBURSE));
            assertThatCode(() -> uc.initiateDisbursement(null)).doesNotThrowAnyException();
            assertThatThrownBy(() -> uc.triggerNachCollection(null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> uc.handlePaymentCallback(null)).isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test @DisplayName("security disabled: guards inert")
    void disabledInert() {
        securityDisabled.run(ctx -> {
            PaymentUseCase uc = ctx.getBean(PaymentUseCase.class);
            authenticate();
            assertThatCode(() -> {
                uc.getPaymentOrder(UUID.randomUUID(), UUID.randomUUID());
                uc.initiateDisbursement(null);
                uc.handlePaymentCallback(null);
            }).doesNotThrowAnyException();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class StubConfig {
        @Bean
        PaymentUseCase paymentUseCase() {
            return new StubPaymentUseCase();
        }
    }

    static class StubPaymentUseCase implements PaymentUseCase {
        @Override public PaymentOrder initiateDisbursement(InitiateDisbursementCommand command) { return null; }
        @Override public NachMandate registerNachMandate(RegisterMandateCommand command) { return null; }
        @Override public PaymentOrder triggerNachCollection(TriggerCollectionCommand command) { return null; }
        @Override public PaymentOrder recordInboundPayment(RecordInboundPaymentCommand command) { return null; }
        @Override public PaymentOrder getPaymentOrder(UUID tenantId, UUID paymentOrderId) { return null; }
        @Override public PaymentOrder handlePaymentCallback(PaymentCallbackCommand command) { return null; }
    }
}
