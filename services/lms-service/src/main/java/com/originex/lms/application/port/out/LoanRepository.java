package com.originex.lms.application.port.out;

import com.originex.lms.domain.model.Loan;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanRepository {

    Loan save(Loan loan);

    Optional<Loan> findById(UUID tenantId, UUID loanId);

    Optional<Loan> findByApplicationId(UUID tenantId, UUID applicationId);

    /**
     * ACTIVE loans that have not yet been accrued for {@code asOf}, ordered by
     * {@code loanId} for keyset pagination. Pass {@code afterLoanId = null} for
     * the first page, then the last {@code loanId} of the previous page. Not
     * tenant-scoped — the accrual job runs system-wide.
     */
    List<Loan> findAccruable(LocalDate asOf, UUID afterLoanId, int limit);

    /**
     * ACTIVE/NPA loans whose delinquency should be recomputed for {@code asOf} —
     * those overdue ({@code nextDueDate < asOf}) or still carrying a non-zero DPD
     * (so a loan that has since become current is reset). Ordered by
     * {@code loanId} for keyset pagination; not tenant-scoped (system-wide job).
     */
    List<Loan> findDelinquent(LocalDate asOf, UUID afterLoanId, int limit);
}
