package com.originex.partner.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * IntegrationRequest Aggregate Root — tracks every outbound call to an external
 * partner (bureau, KYC provider, bank verification, etc.).
 *
 * <p>Invariants (per Partner Integration bounded context spec):
 * <ul>
 *   <li>Every partner call is logged for audit — request + response (PII masked)</li>
 *   <li>Responses are cached with a configurable TTL to avoid redundant paid calls</li>
 *   <li>Circuit breaker state prevents cascading failures to a single partner</li>
 * </ul>
 */
public class IntegrationRequest {

    private UUID requestId;
    private UUID tenantId;
    private PartnerType partnerType;
    private String partnerName;          // CIBIL, EXPERIAN, DIGILOCKER, NSDL, etc.
    private String referenceId;          // e.g., applicationId, customerId
    private IntegrationStatus status;
    private String requestPayloadMasked; // PII-masked for audit
    private String responsePayloadMasked;
    private String errorMessage;
    private int attemptCount;
    private Instant requestedAt;
    private Instant respondedAt;
    private Instant cacheExpiresAt;

    public static IntegrationRequest initiate(UUID tenantId, PartnerType partnerType,
                                              String partnerName, String referenceId,
                                              String requestPayloadMasked) {
        IntegrationRequest req = new IntegrationRequest();
        req.requestId = UUID.randomUUID();
        req.tenantId = tenantId;
        req.partnerType = partnerType;
        req.partnerName = partnerName;
        req.referenceId = referenceId;
        req.requestPayloadMasked = requestPayloadMasked;
        req.status = IntegrationStatus.PENDING;
        req.attemptCount = 1;
        req.requestedAt = Instant.now();
        return req;
    }

    public void succeed(String responsePayloadMasked, int cacheTtlSeconds) {
        this.status = IntegrationStatus.SUCCESS;
        this.responsePayloadMasked = responsePayloadMasked;
        this.respondedAt = Instant.now();
        if (cacheTtlSeconds > 0) {
            this.cacheExpiresAt = Instant.now().plusSeconds(cacheTtlSeconds);
        }
    }

    public void fail(String errorMessage) {
        this.status = IntegrationStatus.FAILED;
        this.errorMessage = errorMessage;
        this.respondedAt = Instant.now();
    }

    public void retry() {
        this.attemptCount++;
        this.status = IntegrationStatus.PENDING;
    }

    public boolean isCacheValid() {
        return status == IntegrationStatus.SUCCESS
                && cacheExpiresAt != null
                && Instant.now().isBefore(cacheExpiresAt);
    }

    // Accessors
    public UUID getRequestId() { return requestId; }
    public UUID getTenantId() { return tenantId; }
    public PartnerType getPartnerType() { return partnerType; }
    public String getPartnerName() { return partnerName; }
    public String getReferenceId() { return referenceId; }
    public IntegrationStatus getStatus() { return status; }
    public String getRequestPayloadMasked() { return requestPayloadMasked; }
    public String getResponsePayloadMasked() { return responsePayloadMasked; }
    public String getErrorMessage() { return errorMessage; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getRespondedAt() { return respondedAt; }
    public Instant getCacheExpiresAt() { return cacheExpiresAt; }

    public IntegrationRequest() {}
    public void setRequestId(UUID id) { this.requestId = id; }
    public void setTenantId(UUID id) { this.tenantId = id; }
    public void setPartnerType(PartnerType t) { this.partnerType = t; }
    public void setPartnerName(String s) { this.partnerName = s; }
    public void setReferenceId(String s) { this.referenceId = s; }
    public void setStatus(IntegrationStatus s) { this.status = s; }
    public void setRequestPayloadMasked(String s) { this.requestPayloadMasked = s; }
    public void setResponsePayloadMasked(String s) { this.responsePayloadMasked = s; }
    public void setErrorMessage(String s) { this.errorMessage = s; }
    public void setAttemptCount(int i) { this.attemptCount = i; }
    public void setRequestedAt(Instant i) { this.requestedAt = i; }
    public void setRespondedAt(Instant i) { this.respondedAt = i; }
    public void setCacheExpiresAt(Instant i) { this.cacheExpiresAt = i; }

    public enum PartnerType { CREDIT_BUREAU, AADHAAR_EKYC, PAN_VERIFICATION, BANK_ACCOUNT_VERIFICATION, VIDEO_KYC, ESIGN, EMANDATE }
    public enum IntegrationStatus { PENDING, SUCCESS, FAILED, CIRCUIT_OPEN }
}
