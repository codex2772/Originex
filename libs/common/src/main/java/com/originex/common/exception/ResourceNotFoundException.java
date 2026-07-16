package com.originex.common.exception;

/**
 * A requested resource does not exist for the caller — mapped to HTTP 404 by the
 * platform's {@code GlobalExceptionHandler}.
 *
 * <p>Domain "not found" exceptions (e.g. {@code CustomerNotFoundException}) should
 * extend this so a missing row — including one hidden from the caller by row-level
 * security — surfaces as a 404, not a 500. Returning 404 (rather than leaking a
 * 500) is also the correct tenant-isolation response: another tenant's record is
 * simply "not found".
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
