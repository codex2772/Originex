package com.originex.los.domain.exception;

import java.util.UUID;

public class ApplicationNotFoundException extends RuntimeException {
    public ApplicationNotFoundException(UUID applicationId) {
        super("Loan application not found: " + applicationId);
    }
}
