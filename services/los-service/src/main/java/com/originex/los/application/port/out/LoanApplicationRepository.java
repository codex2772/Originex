package com.originex.los.application.port.out;

import com.originex.los.domain.model.LoanApplication;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — LoanApplication persistence.
 */
public interface LoanApplicationRepository {

    LoanApplication save(LoanApplication application);

    Optional<LoanApplication> findById(UUID tenantId, UUID applicationId);

    boolean existsByCustomerAndProduct(UUID tenantId, UUID customerId, String productCode, int recentDays);
}
