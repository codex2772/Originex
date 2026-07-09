package com.originex.lms.adapter.in.rest;

import com.originex.common.tenant.TenantContextHolder;
import com.originex.lms.application.port.in.LoanUseCase;
import com.originex.lms.application.port.in.LoanUseCase.*;
import com.originex.lms.domain.model.Installment;
import com.originex.lms.domain.model.Loan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/loans")
public class LoanController {

    private final LoanUseCase loanUseCase;

    public LoanController(LoanUseCase loanUseCase) {
        this.loanUseCase = loanUseCase;
    }

    @GetMapping("/{loanId}")
    public ResponseEntity<LoanResponse> getLoan(@PathVariable UUID loanId) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        Loan loan = loanUseCase.getLoan(tenantId, loanId);
        return ResponseEntity.ok(LoanResponse.from(loan));
    }

    @GetMapping("/{loanId}/repayment-schedule")
    public ResponseEntity<ScheduleResponse> getSchedule(@PathVariable UUID loanId) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        Loan loan = loanUseCase.getLoan(tenantId, loanId);
        return ResponseEntity.ok(ScheduleResponse.from(loan));
    }

    @PostMapping("/{loanId}/repayments")
    public ResponseEntity<RepaymentResponse> recordRepayment(
            @PathVariable UUID loanId,
            @Valid @RequestBody RecordRepaymentRequest request) {

        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        RecordRepaymentCommand cmd = new RecordRepaymentCommand(
                tenantId, loanId, request.amount(), request.currency(), request.paymentReference()
        );

        Loan.RepaymentAllocation allocation = loanUseCase.recordRepayment(cmd);
        return ResponseEntity.ok(RepaymentResponse.from(allocation));
    }

    @PostMapping("/{loanId}/foreclosure-quote")
    public ResponseEntity<ForeclosureQuoteResponse> getForeclosureQuote(@PathVariable UUID loanId) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        Loan loan = loanUseCase.getLoan(tenantId, loanId);

        return ResponseEntity.ok(new ForeclosureQuoteResponse(
                loan.getOutstandingPrincipal().getAmount().toPlainString(),
                loan.getOutstandingInterest().getAmount().toPlainString(),
                loan.getOutstandingCharges().getAmount().toPlainString(),
                loan.getTotalOutstanding().getAmount().toPlainString(),
                loan.getCurrency()
        ));
    }

    // ─── Request/Response DTOs ───

    record RecordRepaymentRequest(
            @NotBlank String amount,
            String currency,
            @NotBlank String paymentReference
    ) {}

    record LoanResponse(
            UUID loanId,
            String loanAccountNumber,
            String status,
            String sanctionedAmount,
            String disbursedAmount,
            String outstandingPrincipal,
            String outstandingInterest,
            String outstandingCharges,
            String interestRate,
            int tenureMonths,
            String emiAmount,
            int dpd,
            String assetClassification,
            String currency,
            long version
    ) {
        static LoanResponse from(Loan l) {
            return new LoanResponse(
                    l.getLoanId(), l.getLoanAccountNumber(), l.getStatus().name(),
                    l.getSanctionedAmount().getAmount().toPlainString(),
                    l.getDisbursedAmount().getAmount().toPlainString(),
                    l.getOutstandingPrincipal().getAmount().toPlainString(),
                    l.getOutstandingInterest().getAmount().toPlainString(),
                    l.getOutstandingCharges().getAmount().toPlainString(),
                    l.getInterestRate().toPlainString(),
                    l.getTenureMonths(), l.getEmiAmount().getAmount().toPlainString(),
                    l.getDpd(), l.getAssetClassification(), l.getCurrency(), l.getVersion()
            );
        }
    }

    record ScheduleResponse(UUID loanId, List<InstallmentDto> installments) {
        static ScheduleResponse from(Loan loan) {
            List<InstallmentDto> dtos = loan.getInstallments().stream()
                    .map(i -> new InstallmentDto(
                            i.getInstallmentNumber(),
                            i.getDueDate().toString(),
                            i.getPrincipalDue().getAmount().toPlainString(),
                            i.getInterestDue().getAmount().toPlainString(),
                            i.getTotalDue().getAmount().toPlainString(),
                            i.getStatus().name()
                    )).toList();
            return new ScheduleResponse(loan.getLoanId(), dtos);
        }
    }

    record InstallmentDto(int number, String dueDate, String principal, String interest, String total, String status) {}

    record RepaymentResponse(String chargesAllocated, String interestAllocated,
                             String principalAllocated, String excess) {
        static RepaymentResponse from(Loan.RepaymentAllocation a) {
            return new RepaymentResponse(
                    a.chargesAllocated().getAmount().toPlainString(),
                    a.interestAllocated().getAmount().toPlainString(),
                    a.principalAllocated().getAmount().toPlainString(),
                    a.excess().getAmount().toPlainString()
            );
        }
    }

    record ForeclosureQuoteResponse(String principal, String interest, String charges, String total, String currency) {}
}
