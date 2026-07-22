package com.originex.lms.adapter.in.kafka;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The machine-actor boundary in the lms DisbursementRequested consumer (third reuse of the shared
 * {@code MachineActorContext}, after ledger's sync and payment's paths). During processing it runs under
 * the tenant AND <b>exactly</b> {@code SCOPE_loans:create} — not disburse, not service, and above all not
 * the fraud-sensitive {@code loans:repay-manual} — and both contexts are cleared afterwards, on the
 * success and exception paths alike.
 */
class DisbursementRequestedConsumerContextCleanupTest {

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    private static DisbursementRequestedConsumer consumerWith(Answer<Object> onCreate) {
        LoanUseCase loans = mock(LoanUseCase.class);
        InboxEventRepository inbox = mock(InboxEventRepository.class);
        when(inbox.existsById(any())).thenReturn(false);
        when(loans.createLoan(any())).thenAnswer(onCreate);
        return new DisbursementRequestedConsumer(loans, inbox, new ObjectMapper());
    }

    private static ConsumerRecord<String, byte[]> disbursementRequested(UUID tenant) {
        String payload = "{\"customer_id\":\"" + UUID.randomUUID() + "\",\"application_id\":\"" + UUID.randomUUID()
                + "\",\"product_code\":\"PERSONAL_LOAN\",\"sanctioned_amount\":\"500000\",\"interest_rate\":\"12.5\","
                + "\"tenure_months\":\"24\",\"emi\":\"23000\"}";
        ConsumerRecord<String, byte[]> r = new ConsumerRecord<>(
                "originex.los.applications.events", 0, 0L, "k", payload.getBytes(StandardCharsets.UTF_8));
        r.headers().add(new RecordHeader("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        r.headers().add(new RecordHeader("event_type", "originex.los.DisbursementRequested".getBytes(StandardCharsets.UTF_8)));
        r.headers().add(new RecordHeader("tenant_id", tenant.toString().getBytes(StandardCharsets.UTF_8)));
        return r;
    }

    @Test
    void success_bindsMinimalCreateScopeAndTenant_thenClearsBoth() {
        AtomicReference<Authentication> authDuring = new AtomicReference<>();
        AtomicReference<TenantContext> tenantDuring = new AtomicReference<>();
        DisbursementRequestedConsumer consumer = consumerWith(inv -> {
            authDuring.set(SecurityContextHolder.getContext().getAuthentication());
            tenantDuring.set(TenantContextHolder.get());
            return null;
        });

        consumer.handleDisbursementRequested(disbursementRequested(UUID.randomUUID()));

        assertThat(tenantDuring.get()).as("tenant bound during processing").isNotNull();
        assertThat(authDuring.get().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .as("minimally scoped — exactly loans:create, never disburse/service/repay-manual")
                .containsExactly("SCOPE_loans:create");

        assertThat(TenantContextHolder.get()).as("tenant cleared after success").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("security context cleared after success").isNull();
    }

    @Test
    void exception_stillClearsBothContexts() {
        DisbursementRequestedConsumer consumer = consumerWith(inv -> {
            throw new IllegalStateException("loan creation failed mid-processing");
        });

        assertThatThrownBy(() -> consumer.handleDisbursementRequested(disbursementRequested(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(TenantContextHolder.get()).as("tenant cleared even when processing throws").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("security context cleared even when processing throws").isNull();
    }
}
