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
}
