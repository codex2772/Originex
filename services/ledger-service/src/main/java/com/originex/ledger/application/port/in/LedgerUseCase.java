package com.originex.ledger.application.port.in;

import com.originex.ledger.domain.model.Account;
import com.originex.ledger.domain.model.JournalEntry;
import com.originex.starter.security.OriginexScopes;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.UUID;

/**
 * Authorization is declared here, on the use-case port — the platform's single enforcement point —
 * mirroring customer-service. Guards are inert while {@code originex.security.enabled=false}.
 *
 * <p>Scope model (privilege boundaries, not endpoints): reads need {@code ledger:read}; routine
 * posting/account-opening needs {@code ledger:post}; <b>reversing a committed entry is corrective
 * and elevated</b>, so it needs its own {@code ledger:reverse} (see {@link OriginexScopes#LEDGER_REVERSE}).
 * The Kafka consumer path auto-posts as a minimally-scoped machine actor
 * ({@code SCOPE_ledger:post} only) — see {@code MachineActorContext}.
 */
public interface LedgerUseCase {

    String REQUIRES_LEDGER_READ =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.LEDGER_READ + "')";
    String REQUIRES_LEDGER_POST =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.LEDGER_POST + "')";
    String REQUIRES_LEDGER_REVERSE =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.LEDGER_REVERSE + "')";

    @PreAuthorize(REQUIRES_LEDGER_POST)
    Account openAccount(OpenAccountCommand command);

    @PreAuthorize(REQUIRES_LEDGER_READ)
    Account getAccount(UUID tenantId, UUID accountId);

    @PreAuthorize(REQUIRES_LEDGER_POST)
    JournalEntry postJournalEntry(PostJournalEntryCommand command);

    @PreAuthorize(REQUIRES_LEDGER_REVERSE)
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
