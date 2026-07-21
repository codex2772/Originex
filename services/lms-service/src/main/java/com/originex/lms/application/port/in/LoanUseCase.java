package com.originex.lms.application.port.in;

import com.originex.lms.domain.model.Loan;

import java.util.UUID;

public interface LoanUseCase {

    Loan createLoan(CreateLoanCommand command);

    Loan getLoan(UUID tenantId, UUID loanId);

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
