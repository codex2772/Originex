package com.originex.los.application.port.out;

/**
 * Outbound port — request a credit bureau pull via the Partner Integration
 * Service (Anti-Corruption Layer). LOS never talks to CIBIL/Experian directly.
 */
public interface CreditBureauPort {

    BureauCheckResult pullCreditReport(CreditCheckRequest request);

    record CreditCheckRequest(
            String tenantId,
            String applicationId,
            String panNumber,
            String fullName,
            String dateOfBirth,
            String phone,
            String consentArtifactId
    ) {}

    record BureauCheckResult(
            boolean success,
            String bureauName,
            String reportReference,
            int creditScore,
            String riskGrade,
            String failureReason
    ) {}
}
