package com.originex.ledger.domain.exception;

import com.originex.common.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * No such journal entry is visible to the caller — mapped to HTTP 404 by the
 * platform's {@code GlobalExceptionHandler}.
 *
 * <p>See {@link AccountNotFoundException} for why this extends
 * {@link ResourceNotFoundException} rather than throwing
 * {@code IllegalArgumentException}.
 */
public class JournalEntryNotFoundException extends ResourceNotFoundException {

    public JournalEntryNotFoundException(UUID entryId) {
        super("Journal entry not found: " + entryId);
    }
}
