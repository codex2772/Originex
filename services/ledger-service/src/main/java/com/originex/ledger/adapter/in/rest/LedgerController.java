package com.originex.ledger.adapter.in.rest;

import com.originex.common.tenant.TenantContextHolder;
import com.originex.ledger.application.port.in.LedgerUseCase;
import com.originex.ledger.application.port.in.LedgerUseCase.*;
import com.originex.ledger.domain.model.Account;
import com.originex.ledger.domain.model.JournalEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/ledger")
public class LedgerController {

    private final LedgerUseCase ledgerUseCase;

    public LedgerController(LedgerUseCase ledgerUseCase) {
        this.ledgerUseCase = ledgerUseCase;
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> openAccount(@Valid @RequestBody OpenAccountRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        OpenAccountCommand command = new OpenAccountCommand(
                tenantId, request.accountNumber(), request.name(),
                request.accountType(), request.glCode(), request.currency(),
                request.loanId() != null ? UUID.fromString(request.loanId()) : null,
                request.customerId() != null ? UUID.fromString(request.customerId()) : null
        );

        Account account = ledgerUseCase.openAccount(command);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(account.getAccountId()).toUri();
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID accountId) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        Account account = ledgerUseCase.getAccount(tenantId, accountId);
        return ResponseEntity.ok(AccountResponse.from(account));
    }

    @PostMapping("/journal-entries")
    public ResponseEntity<JournalEntryResponse> postEntry(
            @Valid @RequestBody PostJournalEntryRequest request) {

        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        List<PostingLine> postingLines = request.postings().stream()
                .map(p -> new PostingLine(
                        UUID.fromString(p.accountId()), p.side(),
                        p.amount(), p.currency(), p.narration()))
                .toList();

        PostJournalEntryCommand command = new PostJournalEntryCommand(
                tenantId, request.entryType(), request.postingDate(),
                request.valueDate(), request.description(),
                request.sourceSystem(), request.sourceId(), request.sourceEventId(),
                postingLines, request.postedBy()
        );

        JournalEntry entry = ledgerUseCase.postJournalEntry(command);
        return ResponseEntity.ok(JournalEntryResponse.from(entry));
    }

    @PostMapping("/journal-entries/{entryId}/reverse")
    public ResponseEntity<JournalEntryResponse> reverseEntry(
            @PathVariable UUID entryId,
            @RequestBody ReverseRequest request) {

        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        JournalEntry reversal = ledgerUseCase.reverseEntry(tenantId, entryId, request.reason());
        return ResponseEntity.ok(JournalEntryResponse.from(reversal));
    }

    // ─── Request DTOs ───

    record OpenAccountRequest(
            @NotBlank String accountNumber,
            @NotBlank String name,
            @NotBlank String accountType,
            @NotBlank String glCode,
            String currency,
            String loanId,
            String customerId
    ) {}

    record PostJournalEntryRequest(
            @NotBlank String entryType,
            @NotBlank String postingDate,
            String valueDate,
            String description,
            String sourceSystem,
            String sourceId,
            String sourceEventId,
            @NotEmpty List<PostingLineRequest> postings,
            String postedBy
    ) {}

    record PostingLineRequest(
            @NotBlank String accountId,
            @NotBlank String side,
            @NotBlank String amount,
            String currency,
            String narration
    ) {}

    record ReverseRequest(String reason) {}

    // ─── Response DTOs ───

    record AccountResponse(
            UUID accountId,
            String accountNumber,
            String name,
            String accountType,
            String normalBalance,
            String balance,
            String currency,
            String status,
            String glCode
    ) {
        static AccountResponse from(Account a) {
            return new AccountResponse(
                    a.getAccountId(), a.getAccountNumber(), a.getName(),
                    a.getAccountType().name(), a.getNormalBalance().name(),
                    a.getBalance().getAmount().toPlainString(),
                    a.getCurrency(), a.getStatus().name(), a.getGlCode()
            );
        }
    }

    record JournalEntryResponse(
            UUID entryId,
            String entryType,
            String postingDate,
            String description,
            String status,
            int postingCount,
            String postedAt
    ) {
        static JournalEntryResponse from(JournalEntry e) {
            return new JournalEntryResponse(
                    e.getEntryId(), e.getEntryType().name(),
                    e.getPostingDate().toString(), e.getDescription(),
                    e.getStatus().name(), e.getPostings().size(),
                    e.getPostedAt().toString()
            );
        }
    }
}
