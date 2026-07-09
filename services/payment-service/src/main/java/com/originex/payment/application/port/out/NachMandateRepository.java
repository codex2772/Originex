package com.originex.payment.application.port.out;

import com.originex.payment.domain.model.NachMandate;

import java.util.Optional;
import java.util.UUID;

public interface NachMandateRepository {
    NachMandate save(NachMandate mandate);
    Optional<NachMandate> findById(UUID tenantId, UUID mandateId);
    Optional<NachMandate> findActiveByLoanId(UUID tenantId, UUID loanId);
}
