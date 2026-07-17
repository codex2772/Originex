package com.originex.payment.domain.exception;

import com.originex.common.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * No such payment order is visible to the caller — mapped to HTTP 404 by the
 * platform's {@code GlobalExceptionHandler}.
 *
 * <p>Extends {@link ResourceNotFoundException} rather than throwing
 * {@code IllegalArgumentException} (which the handler maps to 400): under RLS a
 * payment order belonging to another tenant is simply not visible, and the honest
 * answer to "give me this order" is 404, not "your request was malformed".
 */
public class PaymentOrderNotFoundException extends ResourceNotFoundException {

    public PaymentOrderNotFoundException(UUID paymentOrderId) {
        super("Payment order not found: " + paymentOrderId);
    }
}
