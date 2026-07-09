package com.originex.partner.domain.model;

/**
 * Result of PAN verification (NSDL / Protean e-Gov / Income Tax PAN API).
 */
public record PanVerificationResult(
        boolean valid,
        String panNumber,          // Echoed back, masked at the LOS/Customer layer
        String nameOnRecord,
        String panStatus,          // ACTIVE, INACTIVE, DEACTIVATED
        String panType,            // INDIVIDUAL, COMPANY, HUF, etc.
        boolean nameMatch,         // Fuzzy match against submitted name
        String aadhaarSeedingStatus, // LINKED, NOT_LINKED
        String failureReason
) {
    public static PanVerificationResult invalid(String panNumber, String reason) {
        return new PanVerificationResult(false, panNumber, null, "INVALID", null, false, null, reason);
    }
}
