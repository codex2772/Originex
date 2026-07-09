package com.originex.los.application.port.in;

import com.originex.los.domain.model.LoanApplication;

import java.util.UUID;

/**
 * Inbound port — Loan Application use cases.
 */
public interface LoanApplicationUseCase {

    LoanApplication submitApplication(SubmitApplicationCommand command);

    LoanApplication getApplication(UUID tenantId, UUID applicationId);

    LoanApplication addDocument(AddDocumentCommand command);

    LoanApplication acceptOffer(UUID tenantId, UUID applicationId);

    LoanApplication withdrawApplication(UUID tenantId, UUID applicationId);

    /**
     * Internal: called by credit check async flow after bureau response.
     */
    LoanApplication recordCreditResult(RecordCreditResultCommand command);

    /**
     * Triggers a live credit bureau pull via the Partner Integration Service,
     * then automatically records the result on the application.
     */
    LoanApplication initiateCreditCheck(UUID tenantId, UUID applicationId, String consentArtifactId);

    /**
     * Approve an application and generate its offer using the supplied
     * (manually decided) offer terms. Also reused by the auto-decision flow.
     */
    LoanApplication approveAndGenerateOffer(ApproveCommand command);

    /**
     * Reject an application with a reason. Orchestration only — the state
     * transition and decision-notes rule live in {@code LoanApplication.reject}.
     */
    LoanApplication rejectApplication(RejectCommand command);

    // ─── Commands ───

    record SubmitApplicationCommand(
            UUID tenantId,
            UUID customerId,
            String productCode,
            String amount,
            String currency,
            int tenureMonths,
            String purpose,
            String channel,
            String applicantName,
            String applicantPan,
            String employmentType,
            String monthlyIncome
    ) {}

    record AddDocumentCommand(
            UUID tenantId,
            UUID applicationId,
            String documentType,
            String fileName,
            String storageUrl
    ) {}

    record RecordCreditResultCommand(
            UUID tenantId,
            UUID applicationId,
            int creditScore,
            String bureau,
            String reportRef
    ) {}

    record ApproveCommand(
            UUID tenantId,
            UUID applicationId,
            String sanctionedAmount,
            String interestRate,
            int tenureMonths,
            String emi,
            String processingFee,
            String apr,
            String notes
    ) {}

    record RejectCommand(
            UUID tenantId,
            UUID applicationId,
            String reason
    ) {}
}
