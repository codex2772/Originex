package com.originex.partner.application.port.out;

import com.originex.partner.domain.model.PanVerificationResult;

/**
 * Anti-Corruption Layer port — PAN verification (NSDL / Protean e-Gov).
 */
public interface PanVerificationPort {

    PanVerificationResult verify(PanVerificationRequest request);

    record PanVerificationRequest(
            String panNumber,
            String fullName,
            String dateOfBirth
    ) {}
}
