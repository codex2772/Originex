package com.originex.ledger.application.service;

import com.originex.common.money.Money;
import com.originex.ledger.application.port.in.LedgerUseCase;
import com.originex.ledger.application.port.out.AccountRepository;
import com.originex.ledger.application.port.out.JournalEntryRepository;
import com.originex.ledger.domain.model.Account;
import com.originex.ledger.domain.model.JournalEntry;
import com.originex.ledger.domain.model.JournalEntry.Posting;
import com.originex.starter.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class LedgerApplicationService implements LedgerUseCase {

    private static final Logger log = LoggerFactory.getLogger(LedgerApplicationService.class);

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final OutboxPublisher outboxPublisher;

    public LedgerApplicationService(AccountRepository accountRepository,
                                    JournalEntryRepository journalEntryRepository,
                                    OutboxPublisher outboxPublisher) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.outboxPublisher = outboxPublisher;
    }

    @Override
    public Account openAccount(OpenAccountCommand command) {
        Account.AccountType type = Account.AccountType.valueOf(command.accountType());

        Account account = Account.open(
                command.tenantId(), command.accountNumber(),
                command.name(), type, command.glCode(),
                command.currency() != null ? command.currency() : "INR"
        );

        if (command.loanId() != null) account.setLoanId(command.loanId());
        if (command.customerId() != null) account.setCustomerId(command.customerId());

        Account saved = accountRepository.save(account);
        log.info("Account opened: id={}, number={}, type={}", saved.getAccountId(),
                saved.getAccountNumber(), saved.getAccountType());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Account getAccount(UUID tenantId, UUID accountId) {
        return accountRepository.findById(tenantId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
    }

    @Override
    public JournalEntry postJournalEntry(PostJournalEntryCommand command) {
        // Build posting objects from command
        List<Posting> postings = command.postings().stream()
                .map(line -> Posting.create(
                        line.accountId(),
                        Account.DebitCredit.valueOf(line.side()),
                        Money.of(line.amount(), line.currency() != null ? line.currency() : "INR"),
                        line.narration()
                ))
                .toList();

        // Create journal entry (validates double-entry invariant)
        JournalEntry entry = JournalEntry.create(
                command.tenantId(),
                JournalEntry.JournalEntryType.valueOf(command.entryType()),
                LocalDate.parse(command.postingDate()),
                command.valueDate() != null ? LocalDate.parse(command.valueDate()) : null,
                command.description(),
                command.sourceSystem(),
                command.sourceId(),
                command.sourceEventId(),
                postings,
                command.postedBy()
        );

        // Apply postings to each account (update balances)
        for (Posting posting : postings) {
            Account account = accountRepository.findById(command.tenantId(), posting.getAccountId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Account not found: " + posting.getAccountId()));
            account.applyPosting(posting.getSide(), posting.getAmount());
            accountRepository.save(account);
        }

        // Persist journal entry
        JournalEntry saved = journalEntryRepository.save(entry);

        log.info("Journal entry posted: id={}, type={}, postings={}",
                saved.getEntryId(), saved.getEntryType(), postings.size());

        outboxPublisher.publish("JournalEntry", saved.getEntryId(),
                "originex.ledger.JournalEntryPosted", command.tenantId(),
                String.format("{\"entry_id\":\"%s\",\"entry_type\":\"%s\",\"posting_date\":\"%s\",\"postings\":%d}",
                        saved.getEntryId(), saved.getEntryType(), saved.getPostingDate(), postings.size())
                        .getBytes(StandardCharsets.UTF_8));

        return saved;
    }

    @Override
    public JournalEntry reverseEntry(UUID tenantId, UUID entryId, String reason) {
        JournalEntry original = journalEntryRepository.findById(tenantId, entryId)
                .orElseThrow(() -> new IllegalArgumentException("Journal entry not found: " + entryId));

        JournalEntry reversal = original.reverse(reason, "SYSTEM");

        // Apply reversal postings to accounts
        for (Posting posting : reversal.getPostings()) {
            Account account = accountRepository.findById(tenantId, posting.getAccountId())
                    .orElseThrow();
            account.applyPosting(posting.getSide(), posting.getAmount());
            accountRepository.save(account);
        }

        journalEntryRepository.save(original);  // Save updated status
        JournalEntry savedReversal = journalEntryRepository.save(reversal);

        log.info("Journal entry reversed: originalId={}, reversalId={}", entryId, savedReversal.getEntryId());
        return savedReversal;
    }
}
