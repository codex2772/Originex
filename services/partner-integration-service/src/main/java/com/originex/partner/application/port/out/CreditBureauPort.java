package com.originex.partner.application.port.out;

import com.originex.partner.domain.model.BureauReport;

/**
 * Anti-Corruption Layer port — Credit Bureau access.
 * Concrete adapters translate our domain request into bureau-specific
 * XML/JSON/SOAP formats (CIBIL, Experian, Equifax, CRIF).
 */
public interface CreditBureauPort {

    /**
     * @return the bureau this adapter integrates with, e.g. "CIBIL"
     */
    String bureauName();

    BureauReport pullReport(BureauPullRequest request);

    record BureauPullRequest(
            String panNumber,
            String fullName,
            String dateOfBirth,
            String phone,
            String consentArtifactId   // Proof of customer consent (DPDPA/RBI requirement)
    ) {}
}
