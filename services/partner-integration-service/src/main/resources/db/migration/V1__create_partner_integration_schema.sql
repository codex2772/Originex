-- ═══════════════════════════════════════════════════════════════
-- V1__create_partner_integration_schema.sql
-- Partner Integration Service — audit trail for all external API calls
-- ═══════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE integration_requests (
    request_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    partner_type            VARCHAR(40) NOT NULL,   -- CREDIT_BUREAU, AADHAAR_EKYC, PAN_VERIFICATION, BANK_ACCOUNT_VERIFICATION, ...
    partner_name            VARCHAR(50) NOT NULL,   -- CIBIL, EXPERIAN, DIGILOCKER, NSDL, PENNY_DROP, ...
    reference_id            VARCHAR(100) NOT NULL,  -- applicationId / customerId
    status                  VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    request_payload_masked  TEXT,
    response_payload_masked TEXT,
    error_message           TEXT,
    attempt_count           INTEGER NOT NULL DEFAULT 1,
    requested_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    responded_at            TIMESTAMP WITH TIME ZONE,
    cache_expires_at        TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_integration_tenant_type_ref ON integration_requests(tenant_id, partner_type, reference_id);
CREATE INDEX idx_integration_cache_lookup ON integration_requests(tenant_id, partner_type, reference_id, status, cache_expires_at)
    WHERE status = 'SUCCESS';
CREATE INDEX idx_integration_requested_at ON integration_requests(tenant_id, requested_at);

-- ─── Row-Level Security ───
ALTER TABLE integration_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_integration_requests ON integration_requests
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
