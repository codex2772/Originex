package com.originex.partner.application.port.in;

import com.originex.partner.domain.model.*;
import com.originex.starter.security.OriginexScopes;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.UUID;

/**
 * Inbound port — partner-integration use cases (external vendor verification / bureau).
 *
 * <p><b>Authorization boundary</b> (inert until {@code originex.security.enabled=true}). Split two ways, on
 * caller-capability and data class:
 * <ul>
 *   <li>{@code partner:credit-pull} — {@link #pullCreditReport}: a regulated, paid credit-bureau pull
 *       invoked by <b>los</b> during underwriting (sensitive financial data).</li>
 *   <li>{@code partner:verify} — {@link #verifyAadhaar}/{@link #verifyPan}/{@link #verifyBankAccount}:
 *       identity verification invoked by <b>customer</b> during KYC/onboarding. One scope for all three —
 *       same caller, same privilege class; splitting further would be granularity without a boundary.</li>
 * </ul>
 * The two are NOT the same privilege: a credit pull reads bureau financial data and costs money on a
 * regulated rail, distinct from an identity check — so a caller granted one must not thereby hold the
 * other. The split is asserted both ways in {@code PartnerAuthorizationTest}.
 *
 * <p><b>Why the gate stays dormant.</b> Every caller reaches this service <b>token-less</b> — los→partner
 * and customer→partner both forward only {@code X-Tenant-Id}, no bearer (threat T4,
 * {@code dev/AUTH_DESIGN.md}). partner is the only service hit by <b>two</b> synchronous callers, so it sits
 * squarely inside the S2S blast radius: with method security on, those calls fail the guard and the
 * verification paths break. So the gate is added and proven under enforcement in tests, but partner MUST NOT
 * flip to {@code security.enabled=true} in production until los and customer adopt client-credentials tokens
 * bearing these scopes. Same "armed but dormant" posture as the ledger/payment/los/lms/bre canaries.
 */
public interface PartnerIntegrationUseCase {

    String REQUIRES_PARTNER_CREDIT_PULL =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.PARTNER_CREDIT_PULL + "')";
    String REQUIRES_PARTNER_VERIFY =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.PARTNER_VERIFY + "')";

    @PreAuthorize(REQUIRES_PARTNER_CREDIT_PULL)
    BureauReport pullCreditReport(PullCreditReportCommand command);

    @PreAuthorize(REQUIRES_PARTNER_VERIFY)
    AadhaarVerificationResult verifyAadhaar(VerifyAadhaarCommand command);

    @PreAuthorize(REQUIRES_PARTNER_VERIFY)
    PanVerificationResult verifyPan(VerifyPanCommand command);

    @PreAuthorize(REQUIRES_PARTNER_VERIFY)
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
