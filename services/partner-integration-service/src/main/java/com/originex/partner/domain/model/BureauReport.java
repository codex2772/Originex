package com.originex.partner.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Result of a credit bureau pull (CIBIL / Experian / Equifax / CRIF).
 */
public record BureauReport(
        String bureauName,
        String reportReference,
        int creditScore,
        String scoreVersion,          // e.g., "CIBIL TransUnion Score 3.0"
        String riskGrade,             // e.g., "LOW", "MEDIUM", "HIGH"
        int activeLoanCount,
        int activeCreditCardCount,
        String totalOutstandingAmount,
        int enquiriesLast6Months,
        boolean hasWriteOff,
        boolean hasSettlement,
        List<String> delinquencyFlags,
        Instant reportDate
) {
    public static BureauReport notFound(String bureauName) {
        return new BureauReport(bureauName, null, -1, null, "NO_HIT",
                0, 0, "0", 0, false, false, List.of(), Instant.now());
    }
}
