package com.originex.ledger.application.port.in;

import com.originex.ledger.domain.model.Account;
import com.originex.ledger.domain.model.JournalEntry;

import java.util.List;
import java.util.UUID;

public interface LedgerUseCase {

    Account openAccount(OpenAccountCommand command);

    Account getAccount(UUID tenantId, UUID accountId);

    JournalEntry postJournalEntry(PostJournalEntryCommand command);

    JournalEntry reverseEntry(UUID tenantId, UUID entryId, String reason);

    // ─── Commands ───

    record OpenAccountCommand(
            UUID tenantId,
            String accountNumber,
            String name,
            String accountType,
            String glCode,
            String currency,
            UUID loanId,
            UUID customerId
    ) {}

    record PostJournalEntryCommand(
            UUID tenantId,
            String entryType,
            String postingDate,
            String valueDate,
            String description,
            String sourceSystem,
            String sourceId,
            String sourceEventId,
            List<PostingLine> postings,
            String postedBy
    ) {}

    record PostingLine(
            UUID accountId,
            String side,    // DEBIT or CREDIT
            String amount,
            String currency,
            String narration
    ) {}
}
