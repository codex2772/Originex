package com.originex.lms.adapter.in.kafka.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import com.originex.lms.application.port.in.LoanUseCase;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The machine-actor boundary in the lms PaymentEvent consumer — the multi-capability shape. One listener
 * dispatches to two guarded ops, so the minimal grant is applied <b>per branch</b>, and this test proves
 * each branch holds <b>exactly</b> its own scope:
 * <ul>
 *   <li>DisbursementCompleted → exactly {@code SCOPE_loans:disburse}</li>
 *   <li>PaymentReceived → exactly {@code SCOPE_loans:service}</li>
 * </ul>
 * Critically, <b>neither branch ever holds {@code loans:repay-manual}</b> — the machine can apply a
 * settlement-backed repayment but can never manually assert an un-received one (KI-19). Both contexts are
 * cleared after processing, including when the use-case throws.
 */
class PaymentEventConsumerContextCleanupTest {

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    private static ConsumerRecord<String, byte[]> event(String eventType, String payload, UUID tenant) {
        ConsumerRecord<String, byte[]> r = new ConsumerRecord<>(
                "originex.payments.orders.events", 0, 0L, "k", payload.getBytes(StandardCharsets.UTF_8));
        r.headers().add(new RecordHeader("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        r.headers().add(new RecordHeader("event_type", eventType.getBytes(StandardCharsets.UTF_8)));
        r.headers().add(new RecordHeader("tenant_id", tenant.toString().getBytes(StandardCharsets.UTF_8)));
        return r;
    }

    private static ConsumerRecord<String, byte[]> disbursementCompleted(UUID tenant) {
        return event("originex.payments.DisbursementCompleted",
                "{\"loan_id\":\"" + UUID.randomUUID() + "\",\"payment_order_id\":\"" + UUID.randomUUID()
                        + "\",\"utr\":\"UTR12345\"}", tenant);
    }

    private static ConsumerRecord<String, byte[]> paymentReceived(UUID tenant) {
        return event("originex.payments.PaymentReceived",
                "{\"loan_id\":\"" + UUID.randomUUID() + "\",\"amount\":\"1000\",\"currency\":\"INR\","
                        + "\"payment_type\":\"REPAYMENT_COLLECTION\"}", tenant);
    }

    @Test
    void disbursementCompleted_bindsExactlyDisburseScope_thenClears() {
        AtomicReference<Authentication> authDuring = new AtomicReference<>();
        AtomicReference<TenantContext> tenantDuring = new AtomicReference<>();
        LoanUseCase loans = mock(LoanUseCase.class);
        InboxEventRepository inbox = mock(InboxEventRepository.class);
        when(inbox.existsById(any())).thenReturn(false);
        when(loans.confirmDisbursementByPayment(any(), any(), any(), anyString())).thenAnswer(inv -> {
            authDuring.set(SecurityContextHolder.getContext().getAuthentication());
            tenantDuring.set(TenantContextHolder.get());
            return null;
        });

        new PaymentEventConsumer(loans, inbox, new ObjectMapper())
                .handlePaymentEvent(disbursementCompleted(UUID.randomUUID()));

        assertThat(tenantDuring.get()).as("tenant bound during processing").isNotNull();
        assertThat(authDuring.get().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .as("confirm branch — exactly loans:disburse, never loans:repay-manual")
                .containsExactly("SCOPE_loans:disburse");

        assertThat(TenantContextHolder.get()).as("tenant cleared after").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("security context cleared after").isNull();
    }

    @Test
    void paymentReceived_bindsExactlyServiceScope_neverRepayManual_thenClears() {
        AtomicReference<Authentication> authDuring = new AtomicReference<>();
        LoanUseCase loans = mock(LoanUseCase.class);
        InboxEventRepository inbox = mock(InboxEventRepository.class);
        when(inbox.existsById(any())).thenReturn(false);
        when(loans.allocateRepaymentFromPayment(any(), any(), anyString(), anyString())).thenAnswer(inv -> {
            authDuring.set(SecurityContextHolder.getContext().getAuthentication());
            return null;
        });

        new PaymentEventConsumer(loans, inbox, new ObjectMapper())
                .handlePaymentEvent(paymentReceived(UUID.randomUUID()));

        assertThat(authDuring.get().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .as("repayment branch — exactly the BACKED loans:service, never the unbacked loans:repay-manual")
                .containsExactly("SCOPE_loans:service");
        assertThat(authDuring.get().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .doesNotContain("SCOPE_loans:repay-manual");

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("security context cleared after").isNull();
    }

    @Test
    void exception_stillClearsBothContexts() {
        LoanUseCase loans = mock(LoanUseCase.class);
        InboxEventRepository inbox = mock(InboxEventRepository.class);
        when(inbox.existsById(any())).thenReturn(false);
        when(loans.allocateRepaymentFromPayment(any(), any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("allocation failed mid-processing"));

        assertThatThrownBy(() -> new PaymentEventConsumer(loans, inbox, new ObjectMapper())
                .handlePaymentEvent(paymentReceived(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(TenantContextHolder.get()).as("tenant cleared even when processing throws").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("security context cleared even when processing throws").isNull();
    }
}
