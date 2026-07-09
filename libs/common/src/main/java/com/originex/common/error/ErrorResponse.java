package com.originex.common.error;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Standard error response following RFC 7807 (Problem Details for HTTP APIs).
 * Used as the consistent error response format across all Originex services.
 */
public record ErrorResponse(
        URI type,
        String title,
        int status,
        String detail,
        String instance,
        List<FieldError> errors,
        ErrorMeta meta
) {

    public ErrorResponse {
        if (errors == null) errors = Collections.emptyList();
        if (meta == null) meta = new ErrorMeta(null, Instant.now(), null);
    }

    public static ErrorResponse of(int status, String title, String detail) {
        return new ErrorResponse(
                URI.create("https://api.originex.io/problems/" + title.toLowerCase().replace(' ', '-')),
                title,
                status,
                detail,
                null,
                List.of(),
                new ErrorMeta(null, Instant.now(), null)
        );
    }

    public static ErrorResponse validation(String detail, List<FieldError> errors) {
        return new ErrorResponse(
                URI.create("https://api.originex.io/problems/validation-error"),
                "Validation Failed",
                422,
                detail,
                null,
                errors,
                new ErrorMeta(null, Instant.now(), null)
        );
    }

    /**
     * Individual field-level validation error.
     */
    public record FieldError(
            String field,
            String code,
            String message
    ) {}

    /**
     * Metadata attached to every error response for tracing.
     */
    public record ErrorMeta(
            String requestId,
            Instant timestamp,
            String correlationId
    ) {}
}
