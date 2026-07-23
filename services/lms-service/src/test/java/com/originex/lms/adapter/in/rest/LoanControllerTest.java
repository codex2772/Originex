package com.originex.lms.adapter.in.rest;

import com.originex.common.exception.ResourceNotFoundException;
import com.originex.common.money.Money;
import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import com.originex.lms.application.port.in.LoanUseCase;
import com.originex.lms.domain.model.Loan;
import com.originex.starter.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link LoanController}'s read endpoints, exercising the
 * HTTP status mapping through the real {@link GlobalExceptionHandler}.
 *
 * <p>Covers the new {@code GET /v1/loans/by-application/{id}} (200 hit / 404 miss)
 * and pins the sibling {@code GET /v1/loans/{id}} to <b>404</b> on a miss — both
 * now surface a missing loan as 404 rather than diverging (the by-application
 * endpoint's 404 is the frontend's "still processing" polling signal).
 */
@DisplayName("LoanController — loan read endpoints (HTTP status mapping)")
class LoanControllerTest {

    private final FakeLoanUseCase useCase = new FakeLoanUseCase();
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new LoanController(useCase))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void setTenant() {
        TenantContextHolder.set(TenantContext.of(UUID.randomUUID().toString(), "acme-bank"));
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    private static Loan sampleLoan() {
        return Loan.createFromApplication(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PERSONAL_LOAN",
                Money.of("500000", "INR"), new BigDecimal("12.5"), "FIXED",
                24, Money.of("23536.74", "INR"));
    }

    @Test
    @DisplayName("by-application: loan exists → 200 with body")
    void byApplicationHit() throws Exception {
        useCase.loan = sampleLoan();

        mockMvc.perform(get("/v1/loans/by-application/{id}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.sanctionedAmount").value("500000.0000"));
    }

    @Test
    @DisplayName("by-application: no loan yet → 404 (still-processing signal)")
    void byApplicationMiss() throws Exception {
        useCase.loan = null; // triggers ResourceNotFoundException

        mockMvc.perform(get("/v1/loans/by-application/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("by-id: missing loan → 404 (aligned with by-application, was 400)")
    void byIdMiss() throws Exception {
        useCase.loan = null;

        mockMvc.perform(get("/v1/loans/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    /** Fake — returns {@link #loan} when set, else throws the 404-mapped exception. */
    static final class FakeLoanUseCase implements LoanUseCase {
        Loan loan;

        private Loan requireLoan(UUID id) {
            if (loan == null) {
                throw new ResourceNotFoundException("not found: " + id);
            }
            return loan;
        }

        @Override public Loan getLoan(UUID tenantId, UUID loanId) { return requireLoan(loanId); }
        @Override public Loan getLoanByApplicationId(UUID tenantId, UUID applicationId) { return requireLoan(applicationId); }

        @Override public Loan createLoan(CreateLoanCommand command) { throw new UnsupportedOperationException(); }
        @Override public Loan.RepaymentAllocation recordRepayment(RecordRepaymentCommand command) { throw new UnsupportedOperationException(); }
        @Override public Loan disburseLoan(UUID tenantId, UUID loanId, String beneficiaryAccount) { throw new UnsupportedOperationException(); }
        @Override public Loan confirmDisbursement(UUID tenantId, UUID loanId, UUID disbursementId, String paymentRef) { throw new UnsupportedOperationException(); }
        @Override public Loan confirmDisbursementByPayment(UUID tenantId, UUID loanId, UUID paymentOrderId, String utr) { throw new UnsupportedOperationException(); }
        @Override public Loan.RepaymentAllocation allocateRepaymentFromPayment(UUID tenantId, UUID loanId, String amount, String currency) { throw new UnsupportedOperationException(); }
    }
}
