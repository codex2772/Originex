package com.originex.partner.application.port.in;

import com.originex.partner.domain.model.*;

import java.util.UUID;

public interface PartnerIntegrationUseCase {

    BureauReport pullCreditReport(PullCreditReportCommand command);

    AadhaarVerificationResult verifyAadhaar(VerifyAadhaarCommand command);

    PanVerificationResult verifyPan(VerifyPanCommand command);

    BankAccountVerificationResult verifyBankAccount(VerifyBankAccountCommand command);

    record PullCreditReportCommand(
            UUID tenantId,
            String referenceId,       // applicationId
            String preferredBureau,   // CIBIL, EXPERIAN, EQUIFAX, CRIF — null = default routing
            String panNumber,
            String fullName,
            String dateOfBirth,
            String phone,
            String consentArtifactId
    ) {}

    record VerifyAadhaarCommand(
            UUID tenantId,
            String referenceId,       // customerId
            String aadhaarNumberOrVid,
            String consentArtifactId,
            String otpReference
    ) {}

    record VerifyPanCommand(
            UUID tenantId,
            String referenceId,       // customerId
            String panNumber,
            String fullName,
            String dateOfBirth
    ) {}

    record VerifyBankAccountCommand(
            UUID tenantId,
            String referenceId,       // customerId or bankAccountId
            String accountNumber,
            String ifscCode,
            String expectedAccountHolderName
    ) {}
}
