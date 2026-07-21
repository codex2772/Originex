package com.originex.ledger.domain.exception;

import com.originex.common.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * No such account is visible to the caller — mapped to HTTP 404 by the platform's
 * {@code GlobalExceptionHandler}.
 *
 * <p>Extends {@link ResourceNotFoundException} rather than throwing
 * {@code IllegalArgumentException} (which the handler maps to 400): under RLS an
 * account belonging to another tenant is simply not visible, and the honest answer
 * to "give me this account" is 404, not "your request was malformed".
 */
public class AccountNotFoundException extends ResourceNotFoundException {

    public AccountNotFoundException(UUID accountId) {
        super("Account not found: " + accountId);
    }
}
