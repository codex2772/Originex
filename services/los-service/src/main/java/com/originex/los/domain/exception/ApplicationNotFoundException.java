package com.originex.los.domain.exception;

import com.originex.common.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * No such loan application is visible to the caller — mapped to HTTP 404 by the
 * platform's {@code GlobalExceptionHandler}.
 *
 * <p>Extends {@link ResourceNotFoundException} rather than {@code RuntimeException}:
 * as a plain {@code RuntimeException} it matched no handler and fell through to the
 * catch-all, so every missing application surfaced as a 500. Under RLS an
 * application belonging to another tenant is simply not visible, and the honest
 * answer is 404 — {@code ResourceNotFoundException}'s own contract says as much:
 * "another tenant's record is simply not found".
 */
public class ApplicationNotFoundException extends ResourceNotFoundException {

    public ApplicationNotFoundException(UUID applicationId) {
        super("Loan application not found: " + applicationId);
    }
}
