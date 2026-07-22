package com.originex.lms.domain.exception;

import com.originex.common.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * No such loan is visible to the caller — mapped to HTTP 404 by the platform's
 * {@code GlobalExceptionHandler}.
 *
 * <p>Extends {@link ResourceNotFoundException} rather than throwing a bare
 * {@code IllegalArgumentException}: the latter is handled as <b>400 Bad Request</b>
 * (the handler's contract for invalid input), so a missing loan was reported as a
 * client input error rather than a not-found — and validation failures like
 * "Payment must be positive" share that same 400. A distinct not-found type keeps
 * the two apart: this maps to 404, validation stays 400. Under RLS a loan belonging
 * to another tenant is simply not visible, and 404 is the honest, isolation-safe
 * answer.
 */
public class LoanNotFoundException extends ResourceNotFoundException {

    public LoanNotFoundException(UUID loanId) {
        super("Loan not found: " + loanId);
    }
}
