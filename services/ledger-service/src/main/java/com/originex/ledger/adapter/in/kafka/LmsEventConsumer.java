package com.originex.ledger.adapter.in.kafka;

import com.originex.common.money.Money;
import com.originex.common.tenant.TenantContext;
import com.originex.starter.security.MachineActorContext;
import com.originex.starter.security.OriginexScopes;
import com.originex.ledger.application.port.in.LedgerUseCase;
import com.originex.ledger.application.port.in.LedgerUseCase.PostJournalEntryCommand;
import com.originex.ledger.application.port.in.LedgerUseCase.PostingLine;
import com.originex.ledger.application.port.out.AccountRepository;
import com.originex.ledger.domain.model.Account;
import com.originex.starter.kafka.KafkaEventEnvelope;
import com.originex.starter.outbox.InboxEventJpaEntity;
import com.originex.starter.outbox.InboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ledger Kafka Consumer — auto-posts journal entries from LMS events.
 *
 * <p>Listens to: originex.lms.loans.events
 * <p>Handles:
 * <ul>
 *   <li>LoanDisbursed → DR Loan Receivable, CR Bank/Pool Account</li>
 *   <li>RepaymentAllocated → DR Bank/Cash, CR Loan Receivable (principal), CR Interest Income (interest)</li>
 *   <li>InterestAccrued → DR Interest Receivable, CR Interest Income (accrued)</li>
 * </ul>
 *
 * <p>Implements inbox idempotency — events with duplicate IDs are skipped. Payload/header defects
 * surface as {@link com.originex.starter.kafka.PoisonEventException} (via {@link KafkaEventEnvelope})
 * and are routed straight to the DLQ; transient failures propagate and are retried by the shared
 * error handler.
 */
@Component
public class LmsEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(LmsEventConsumer.class);

    // Standard GL account IDs (would be resolved from config/chart of accounts in production).
    // Seeded by V2__seed_chart_of_accounts_and_inbox_table.sql for the default tenant
    // (00000000-0000-0000-0000-000000000001). Package-private (not private) so
    // LmsEventConsumerBootstrapTest can assert the seed migration stays in sync with these
    // constants instead of duplicating the UUID literals a third time.
    static final UUID POOL_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID INTEREST_INCOME_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID INTEREST_RECEIVABLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final LedgerUseCase ledgerUseCase;
    private final AccountRepository accountRepository;
    private final InboxEventRepository inboxRepository;
    private final ObjectMapper objectMapper;

    public LmsEventConsumer(LedgerUseCase ledgerUseCase,
                            AccountRepository accountRepository,
                            InboxEventRepository inboxRepository,
                            ObjectMapper objectMapper) {
        this.ledgerUseCase = ledgerUseCase;
        this.accountRepository = accountRepository;
        this.inboxRepository = inboxRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "originex.lms.loans.events",
            groupId = "ledger-lms-consumer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handleLmsEvent(ConsumerRecord<String, byte[]> record) {
        String eventType = KafkaEventEnvelope.requireHeader(record, "event_type");
        UUID eventUuid = KafkaEventEnvelope.requireUuidHeader(record, "event_id");
        String tenantId = KafkaEventEnvelope.requireHeader(record, "tenant_id");

        // Inbox idempotency check
        if (inboxRepository.existsById(eventUuid)) {
            log.debug("Duplicate event detected, skipping: eventId={}", eventUuid);
            return;
        }

        try {
            // Tenant identity (RLS) + a minimal machine authorization (ledger:post only) established
            // together, one lifecycle, cleared together in the finally — see MachineActorContext.
            MachineActorContext.establish(TenantContext.of(tenantId, tenantId), OriginexScopes.LEDGER_POST);
            MDC.put("tenantId", tenantId);
            MDC.put("eventId", eventUuid.toString());

            switch (eventType) {
                case "originex.lms.LoanDisbursed" ->
                        handleLoanDisbursed(tenantId, KafkaEventEnvelope.readJson(objectMapper, record));
                case "originex.lms.RepaymentAllocated" ->
                        handleRepaymentAllocated(tenantId, KafkaEventEnvelope.readJson(objectMapper, record));
                case "originex.lms.InterestAccrued" ->
                        handleInterestAccrued(tenantId, KafkaEventEnvelope.readJson(objectMapper, record));
                default -> {
                    log.debug("Ignoring unhandled event type: {}", eventType);
                    return; // not our event — do not record in inbox
                }
            }

            // Mark as processed in inbox
            inboxRepository.save(InboxEventJpaEntity.of(eventUuid, eventType));

        } finally {
            MachineActorContext.clear();
            MDC.remove("tenantId");
            MDC.remove("eventId");
        }
    }

    /**
     * LoanDisbursed → DR Loan Receivable, CR Pool Account
     */
    private void handleLoanDisbursed(String tenantId, JsonNode json) {
        String loanId = KafkaEventEnvelope.requiredText(json, "loan_id");
        String amount = KafkaEventEnvelope.requiredText(json, "amount");
        String currency = KafkaEventEnvelope.optionalText(json, "currency", "INR");

        // Resolve loan-specific receivable account (or create if first disbursement)
        UUID loanReceivableId = resolveLoanReceivableAccount(tenantId, loanId, currency);

        PostJournalEntryCommand command = new PostJournalEntryCommand(
                UUID.fromString(tenantId),
                "DISBURSEMENT",
                LocalDate.now().toString(),
                null,
                "Loan disbursement: " + loanId,
                "LMS", loanId, null,
                List.of(
                        new PostingLine(loanReceivableId, "DEBIT", amount, currency, "Loan receivable - disbursement"),
                        new PostingLine(POOL_ACCOUNT_ID, "CREDIT", amount, currency, "Pool account - funds released")
                ),
                "SYSTEM"
        );

        ledgerUseCase.postJournalEntry(command);
        log.info("Ledger posted: LoanDisbursed, loanId={}, amount={}", loanId, amount);
    }

    /**
     * RepaymentAllocated → DR Cash, CR Loan Receivable (principal), CR Interest Income (interest)
     */
    private void handleRepaymentAllocated(String tenantId, JsonNode json) {
        String loanId = KafkaEventEnvelope.requiredText(json, "loan_id");
        String principal = KafkaEventEnvelope.requiredText(json, "principal");
        String interest = KafkaEventEnvelope.requiredText(json, "interest");
        String currency = KafkaEventEnvelope.optionalText(json, "currency", "INR");

        UUID loanReceivableId = resolveLoanReceivableAccount(tenantId, loanId, currency);
        Money principalMoney = Money.of(principal, currency);
        Money interestMoney = Money.of(interest, currency);
        Money totalReceived = principalMoney.add(interestMoney);

        // Only post if there's something to post
        if (totalReceived.isZero()) return;

        List<PostingLine> postings = new java.util.ArrayList<>();
        postings.add(new PostingLine(POOL_ACCOUNT_ID, "DEBIT",
                totalReceived.getAmount().toPlainString(), currency, "Cash received - repayment"));

        if (principalMoney.isPositive()) {
            postings.add(new PostingLine(loanReceivableId, "CREDIT",
                    principal, currency, "Principal repaid"));
        }
        if (interestMoney.isPositive()) {
            postings.add(new PostingLine(INTEREST_INCOME_ID, "CREDIT",
                    interest, currency, "Interest income recognized"));
        }

        PostJournalEntryCommand command = new PostJournalEntryCommand(
                UUID.fromString(tenantId),
                "REPAYMENT",
                LocalDate.now().toString(), null,
                "Repayment allocation: " + loanId,
                "LMS", loanId, null, postings, "SYSTEM"
        );

        ledgerUseCase.postJournalEntry(command);
        log.info("Ledger posted: RepaymentAllocated, loanId={}, principal={}, interest={}", loanId, principal, interest);
    }

    /**
     * InterestAccrued → DR Interest Receivable, CR Interest Income (Accrued)
     */
    private void handleInterestAccrued(String tenantId, JsonNode json) {
        String loanId = KafkaEventEnvelope.requiredText(json, "loan_id");
        String amount = KafkaEventEnvelope.requiredText(json, "accrued_amount");
        String currency = KafkaEventEnvelope.optionalText(json, "currency", "INR");

        PostJournalEntryCommand command = new PostJournalEntryCommand(
                UUID.fromString(tenantId),
                "INTEREST_ACCRUAL",
                LocalDate.now().toString(), null,
                "Daily interest accrual: " + loanId,
                "LMS", loanId, null,
                List.of(
                        new PostingLine(INTEREST_RECEIVABLE_ID, "DEBIT", amount, currency, "Interest receivable"),
                        new PostingLine(INTEREST_INCOME_ID, "CREDIT", amount, currency, "Interest income accrued")
                ),
                "SYSTEM"
        );

        ledgerUseCase.postJournalEntry(command);
        log.info("Ledger posted: InterestAccrued, loanId={}, amount={}", loanId, amount);
    }

    /**
     * Resolve or lazily create the loan-specific receivable sub-account.
     */
    private UUID resolveLoanReceivableAccount(String tenantId, String loanId, String currency) {
        String accountNumber = "LR-" + loanId.substring(0, 8);
        UUID tenantUuid = UUID.fromString(tenantId);

        Optional<Account> existing = accountRepository.findByAccountNumber(tenantUuid, accountNumber);
        if (existing.isPresent()) {
            return existing.get().getAccountId();
        }

        // Auto-create loan receivable sub-account
        Account account = Account.open(tenantUuid, accountNumber,
                "Loan Receivable - " + loanId.substring(0, 8),
                Account.AccountType.ASSET, "1100", currency);
        account.setLoanId(UUID.fromString(loanId));
        Account saved = accountRepository.save(account);
        log.info("Auto-created loan receivable account: {}", accountNumber);
        return saved.getAccountId();
    }
}
