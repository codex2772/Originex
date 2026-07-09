package com.originex.partner.adapter.out.persistence;

import com.originex.partner.domain.model.IntegrationRequest;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "integration_requests")
public class IntegrationRequestJpaEntity {

    @Id
    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "partner_type", nullable = false)
    private IntegrationRequest.PartnerType partnerType;

    @Column(name = "partner_name", nullable = false)
    private String partnerName;

    @Column(name = "reference_id", nullable = false)
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IntegrationRequest.IntegrationStatus status;

    @Column(name = "request_payload_masked")
    private String requestPayloadMasked;

    @Column(name = "response_payload_masked")
    private String responsePayloadMasked;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "cache_expires_at")
    private Instant cacheExpiresAt;

    public static IntegrationRequestJpaEntity fromDomain(IntegrationRequest r) {
        IntegrationRequestJpaEntity e = new IntegrationRequestJpaEntity();
        e.requestId = r.getRequestId();
        e.tenantId = r.getTenantId();
        e.partnerType = r.getPartnerType();
        e.partnerName = r.getPartnerName();
        e.referenceId = r.getReferenceId();
        e.status = r.getStatus();
        e.requestPayloadMasked = r.getRequestPayloadMasked();
        e.responsePayloadMasked = r.getResponsePayloadMasked();
        e.errorMessage = r.getErrorMessage();
        e.attemptCount = r.getAttemptCount();
        e.requestedAt = r.getRequestedAt();
        e.respondedAt = r.getRespondedAt();
        e.cacheExpiresAt = r.getCacheExpiresAt();
        return e;
    }

    public IntegrationRequest toDomain() {
        IntegrationRequest r = new IntegrationRequest();
        r.setRequestId(requestId);
        r.setTenantId(tenantId);
        r.setPartnerType(partnerType);
        r.setPartnerName(partnerName);
        r.setReferenceId(referenceId);
        r.setStatus(status);
        r.setRequestPayloadMasked(requestPayloadMasked);
        r.setResponsePayloadMasked(responsePayloadMasked);
        r.setErrorMessage(errorMessage);
        r.setAttemptCount(attemptCount);
        r.setRequestedAt(requestedAt);
        r.setRespondedAt(respondedAt);
        r.setCacheExpiresAt(cacheExpiresAt);
        return r;
    }

    protected IntegrationRequestJpaEntity() {}
}
