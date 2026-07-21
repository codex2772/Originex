package com.originex.ledger.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import com.originex.ledger.application.port.in.LedgerUseCase;
import com.originex.ledger.application.port.out.AccountRepository;
import com.originex.ledger.domain.model.Account;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the machine-actor boundary in the ledger Kafka consumer: while processing an event it runs
 * under a <b>tenant</b> identity AND a <b>minimal</b> authorization (exactly {@code SCOPE_ledger:post}),
 * and — the hazard this guards — after the invocation returns, <b>both</b> contexts are cleared so
 * neither leaks into a later event on the same <i>reused</i> listener thread.
 *
 * <p>The leak vector here is thread <b>reuse</b>, not child-thread inheritance
 * ({@code SecurityContextHolder} runs the default non-inheritable {@code MODE_THREADLOCAL}; the
 * tenant holder is a plain {@link ThreadLocal}). The dangerous path is therefore the <b>exception</b>
 * path — a handler that throws must still leave both contexts cleared via the {@code finally}. Both
 * paths are asserted below.
 */
class LmsEventConsumerContextCleanupTest {

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    /** Build a consumer whose guarded post either records the live contexts, or throws. */
    private static LmsEventConsumer consumerWith(Answer<Object> onPost) {
        LedgerUseCase ledger = mock(LedgerUseCase.class);
        AccountRepository accounts = mock(AccountRepository.class);
        InboxEventRepository inbox = mock(InboxEventRepository.class);
        Account existing = Account.open(UUID.randomUUID(), "LR-00000000",
                "Loan Receivable", Account.AccountType.ASSET, "1100", "INR");
        when(accounts.findByAccountNumber(any(), any())).thenReturn(Optional.of(existing));
        when(inbox.existsById(any())).thenReturn(false);
        when(ledger.postJournalEntry(any())).thenAnswer(onPost);
        return new LmsEventConsumer(ledger, accounts, inbox, new ObjectMapper());
    }

    private static ConsumerRecord<String, byte[]> loanDisbursed(UUID tenant) {
        String payload = "{\"loan_id\":\"" + UUID.randomUUID() + "\",\"amount\":\"1000.00\",\"currency\":\"INR\"}";
        ConsumerRecord<String, byte[]> r =
                new ConsumerRecord<>("originex.lms.loans.events", 0, 0L, "k", payload.getBytes(StandardCharsets.UTF_8));
        r.headers().add(new RecordHeader("event_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        r.headers().add(new RecordHeader("event_type", "originex.lms.LoanDisbursed".getBytes(StandardCharsets.UTF_8)));
        r.headers().add(new RecordHeader("tenant_id", tenant.toString().getBytes(StandardCharsets.UTF_8)));
        return r;
    }

    @Test
    void success_bindsMinimalAuthAndTenant_thenClearsBoth() {
        UUID tenant = UUID.randomUUID();
        AtomicReference<Authentication> authDuring = new AtomicReference<>();
        AtomicReference<TenantContext> tenantDuring = new AtomicReference<>();

        LmsEventConsumer consumer = consumerWith(inv -> {
            authDuring.set(SecurityContextHolder.getContext().getAuthentication());
            tenantDuring.set(TenantContextHolder.get());
            return null;
        });

        consumer.handleLmsEvent(loanDisbursed(tenant));

        assertThat(tenantDuring.get()).as("tenant identity bound during processing").isNotNull();
        assertThat(authDuring.get()).as("machine authentication bound during processing").isNotNull();
        assertThat(authDuring.get().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .as("minimally scoped — exactly ledger:post, no god-mode")
                .containsExactly("SCOPE_ledger:post");

        assertThat(TenantContextHolder.get()).as("tenant cleared after success").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("security context cleared after success").isNull();
    }

    @Test
    void exception_stillClearsBothContexts() {
        // The dangerous path: a handler throws. The finally must still clear both, or the next
        // event on this reused thread would inherit this tenant + this authority.
        LmsEventConsumer consumer = consumerWith(inv -> {
            // both are bound at the point of failure...
            assertThat(TenantContextHolder.get()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            throw new IllegalStateException("posting failed mid-processing");
        });

        assertThatThrownBy(() -> consumer.handleLmsEvent(loanDisbursed(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);

        // ...and both are cleared despite the throw.
        assertThat(TenantContextHolder.get()).as("tenant cleared even when processing throws").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("security context cleared even when processing throws").isNull();
    }
}
