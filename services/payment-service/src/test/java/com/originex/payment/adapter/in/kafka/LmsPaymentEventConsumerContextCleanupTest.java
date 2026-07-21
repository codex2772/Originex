package com.originex.payment.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import com.originex.payment.application.port.in.PaymentUseCase;
import com.originex.starter.outbox.InboxEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The machine-actor boundary in the payment LoanDisbursed consumer (first reuse of the shared
 * {@code MachineActorContext} outside ledger): during processing it runs under the tenant AND exactly
 * {@code SCOPE_payments:disburse} (its one op — minimal, not the coarser payments:initiate), and after
 * processing both contexts are cleared. The exception path is asserted — the same thread-reuse leak
 * that would have bitten ledger.
 */
class LmsPaymentEventConsumerContextCleanupTest {

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    private static LmsPaymentEventConsumer consumerWith(Answer<Object> onDisburse) {
        PaymentUseCase payment = mock(PaymentUseCase.class);
        InboxEventRepository inbox = mock(InboxEventRepository.class);
        when(inbox.existsById(any())).thenReturn(false);
        when(payment.initiateDisbursement(any())).thenAnswer(onDisburse);
        return new LmsPaymentEventConsumer(payment, inbox, new ObjectMapper());
    }

    private static ConsumerRecord<String, byte[]> loanDisbursed(UUID tenant) {
        String payload = "{\"loan_id\":\"" + UUID.randomUUID() + "\",\"amount\":\"1000.00\",\"currency\":\"INR\","
                + "\"beneficiary_account\":\"000111222\",\"beneficiary_ifsc\":\"HDFC0000001\","
                + "\"customer_id\":\"" + UUID.randomUUID() + "\"}";
        ConsumerRecord<String, byte[]> r =
                new ConsumerRecord<>("originex.lms.loans.events", 0, 0L, "k", payload.getBytes(StandardCharsets.UTF_8));
        r.headers().add(new RecordHeader("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        r.headers().add(new RecordHeader("event_type", "originex.lms.LoanDisbursed".getBytes(StandardCharsets.UTF_8)));
        r.headers().add(new RecordHeader("tenant_id", tenant.toString().getBytes(StandardCharsets.UTF_8)));
        return r;
    }

    @Test
    void success_bindsMinimalDisburseScopeAndTenant_thenClearsBoth() {
        AtomicReference<Authentication> authDuring = new AtomicReference<>();
        AtomicReference<TenantContext> tenantDuring = new AtomicReference<>();
        LmsPaymentEventConsumer consumer = consumerWith(inv -> {
            authDuring.set(SecurityContextHolder.getContext().getAuthentication());
            tenantDuring.set(TenantContextHolder.get());
            return null;
        });

        consumer.handleLmsEvent(loanDisbursed(UUID.randomUUID()));

        assertThat(tenantDuring.get()).as("tenant bound during processing").isNotNull();
        assertThat(authDuring.get().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .as("minimally scoped — exactly payments:disburse")
                .containsExactly("SCOPE_payments:disburse");

        assertThat(TenantContextHolder.get()).as("tenant cleared after success").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("security context cleared after success").isNull();
    }

    @Test
    void exception_stillClearsBothContexts() {
        LmsPaymentEventConsumer consumer = consumerWith(inv -> {
            assertThat(TenantContextHolder.get()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            throw new IllegalStateException("disbursement failed mid-processing");
        });

        assertThatThrownBy(() -> consumer.handleLmsEvent(loanDisbursed(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(TenantContextHolder.get()).as("tenant cleared even when processing throws").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("security context cleared even when processing throws").isNull();
    }
}
