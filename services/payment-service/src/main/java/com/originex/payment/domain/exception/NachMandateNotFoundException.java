package com.originex.payment.domain.exception;

import com.originex.common.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * No such NACH mandate is visible to the caller — mapped to HTTP 404 by the
 * platform's {@code GlobalExceptionHandler}.
 *
 * <p>See {@link PaymentOrderNotFoundException} for why this extends
 * {@link ResourceNotFoundException} rather than throwing
 * {@code IllegalArgumentException}.
 */
public class NachMandateNotFoundException extends ResourceNotFoundException {

    public NachMandateNotFoundException(UUID mandateId) {
        super("NACH mandate not found: " + mandateId);
    }
}
