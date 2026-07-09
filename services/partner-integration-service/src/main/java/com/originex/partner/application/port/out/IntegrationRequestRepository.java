package com.originex.partner.application.port.out;

import com.originex.partner.domain.model.IntegrationRequest;

import java.util.Optional;
import java.util.UUID;

public interface IntegrationRequestRepository {

    IntegrationRequest save(IntegrationRequest request);

    Optional<IntegrationRequest> findLatestValidCache(UUID tenantId, IntegrationRequest.PartnerType type, String referenceId);
}
