package com.originex.lms.application.port.out;

import com.originex.lms.domain.model.Loan;

import java.util.Optional;
import java.util.UUID;

public interface LoanRepository {

    Loan save(Loan loan);

    Optional<Loan> findById(UUID tenantId, UUID loanId);

    Optional<Loan> findByApplicationId(UUID tenantId, UUID applicationId);
}
