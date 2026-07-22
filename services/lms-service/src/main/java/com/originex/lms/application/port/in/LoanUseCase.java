package com.originex.lms.application.port.in;

import com.originex.lms.domain.model.Loan;
import com.originex.starter.security.OriginexScopes;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.UUID;

/**
 * Inbound port — Loan (LMS) use cases.
 *
 * <p><b>Authorization boundary</b> (inert until {@code originex.security.enabled=true}). Scopes split on
 * privilege and, critically, on <b>whether the actor can assert something that did not demonstrably happen</b>:
 * <ul>
 *   <li>{@code loans:read} — reads (loan, schedule, foreclosure quote).</li>
 *   <li>{@code loans:create} — create a loan record from an approved application (machine path).</li>
 *   <li>{@code loans:disburse} — the disbursement lifecycle (confirming funds moved).</li>
 *   <li>{@code loans:service} — apply a <b>settlement-backed</b> repayment (driven by a verified payment event).</li>
 *   <li>{@code loans:repay-manual} — <b>manually assert a repayment WITHOUT settlement proof</b>: can mark a loan
 *       paid with no money received. A fraud-sensitive capability held only by humans, never a machine (KI-19).</li>
 * </ul>
 *
 * <p>The backed ({@code loans:service}) and unbacked ({@code loans:repay-manual}) repayment paths are the same
 * operation by name but <b>not the same privilege</b> — separating them keeps the machine service-account from
 * ever holding the capability to mark a loan paid without a real payment. Scope isolation does not by itself close
 * that surface (audit-identity and settlement/maker-checker binding do — see KI-19); it is asserted as a
 * known-behavior boundary in the tests, not papered over.
 *
 * <p>Note: {@code disburseLoan} and the 4-arg {@code confirmDisbursement} currently have no caller (the live
 * disbursement path is consumer-driven {@code createLoan} → … → {@code confirmDisbursementByPayment}); they are
 * guarded to satisfy deny-by-default but are dead API — see KI-20.
 */
public interface LoanUseCase {

    String REQUIRES_LOANS_READ =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.LOANS_READ + "')";
    String REQUIRES_LOANS_CREATE =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.LOANS_CREATE + "')";
    String REQUIRES_LOANS_DISBURSE =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.LOANS_DISBURSE + "')";
    String REQUIRES_LOANS_SERVICE =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.LOANS_SERVICE + "')";
    String REQUIRES_LOANS_REPAY_MANUAL =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.LOANS_REPAY_MANUAL + "')";

    @PreAuthorize(REQUIRES_LOANS_CREATE)
    Loan createLoan(CreateLoanCommand command);

    @PreAuthorize(REQUIRES_LOANS_READ)
    Loan getLoan(UUID tenantId, UUID loanId);

    /** Human, unbacked assertion of a repayment — the fraud-sensitive path (KI-19). */
    @PreAuthorize(REQUIRES_LOANS_REPAY_MANUAL)
    Loan.RepaymentAllocation recordRepayment(RecordRepaymentCommand command);

    /** Dead API — no caller; guarded for deny-by-default only (KI-20). */
    @PreAuthorize(REQUIRES_LOANS_DISBURSE)
    Loan disburseLoan(UUID tenantId, UUID loanId, String beneficiaryAccount);

    /** Dead API — no caller; guarded for deny-by-default only (KI-20). */
    @PreAuthorize(REQUIRES_LOANS_DISBURSE)
    Loan confirmDisbursement(UUID tenantId, UUID loanId, UUID disbursementId, String paymentRef);

    /** Called by PaymentEventConsumer when DisbursementCompleted arrives from Payment Service */
    @PreAuthorize(REQUIRES_LOANS_DISBURSE)
    Loan confirmDisbursementByPayment(UUID tenantId, UUID loanId, UUID paymentOrderId, String utr);

    /** Called by PaymentEventConsumer when PaymentReceived arrives — a settlement-backed repayment. */
    @PreAuthorize(REQUIRES_LOANS_SERVICE)
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
