package com.originex.lms.application.port.in;

import com.originex.lms.domain.model.Loan;

import java.util.UUID;

public interface LoanUseCase {

    Loan createLoan(CreateLoanCommand command);

    Loan getLoan(UUID tenantId, UUID loanId);

    /**
     * Look up the loan created from a given LOS application. Used by the frontend
     * to poll for the loan once an approved application has been disbursed.
     *
     * <p><b>404 semantics:</b> a miss means the loan has not yet materialised for
     * this application — LMS knows only about loans, not application lifecycle, so
     * it cannot distinguish "not created yet (still processing)" from "never
     * (application rejected/withdrawn)". The terminal-vs-transient signal lives in
     * LOS ({@code GET /v1/loan-applications/{id}} → status). Callers polling on the
     * 404 must stop when the LOS application status is terminal.
     */
    Loan getLoanByApplicationId(UUID tenantId, UUID applicationId);

    Loan.RepaymentAllocation recordRepayment(RecordRepaymentCommand command);

    Loan disburseLoan(UUID tenantId, UUID loanId, String beneficiaryAccount);

    Loan confirmDisbursement(UUID tenantId, UUID loanId, UUID disbursementId, String paymentRef);

    /** Called by PaymentEventConsumer when DisbursementCompleted arrives from Payment Service */
    Loan confirmDisbursementByPayment(UUID tenantId, UUID loanId, UUID paymentOrderId, String utr);

    /** Called by PaymentEventConsumer when PaymentReceived arrives from Payment Service */
    Loan.RepaymentAllocation allocateRepaymentFromPayment(UUID tenantId, UUID loanId, String amount, String currency);

    record CreateLoanCommand(
            UUID tenantId,
            UUID customerId,
            UUID applicationId,
            String productCode,
            String sanctionedAmount,
            String interestRate,
            String rateType,
            int tenureMonths,
            String emiAmount,
            String currency,
            String beneficiaryAccount,
            String beneficiaryIfsc,
            String beneficiaryName,
            String beneficiaryBank
    ) {}

    record RecordRepaymentCommand(
            UUID tenantId,
            UUID loanId,
            String amount,
            String currency,
            String paymentReference
    ) {}
}
