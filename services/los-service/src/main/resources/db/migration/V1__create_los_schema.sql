-- ═══════════════════════════════════════════════════════════════
-- V1__create_los_schema.sql
-- Loan Origination System initial schema
-- ═══════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─── Loan Applications ───
CREATE TABLE loan_applications (
    application_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL,
    customer_id           UUID NOT NULL,
    product_code          VARCHAR(50) NOT NULL,
    status                VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED',
    requested_amount      NUMERIC(19,4) NOT NULL,
    requested_currency    VARCHAR(3) NOT NULL DEFAULT 'INR',
    requested_tenure      INTEGER NOT NULL,
    purpose               VARCHAR(255),
    channel               VARCHAR(50),
    applicant_name        VARCHAR(200),
    applicant_pan         VARCHAR(20),
    employment_type       VARCHAR(30),
    monthly_income        NUMERIC(19,4),
    monthly_income_currency VARCHAR(3) DEFAULT 'INR',
    credit_score          INTEGER,
    credit_bureau         VARCHAR(30),
    credit_report_ref     VARCHAR(255),
    credit_check_at       TIMESTAMP WITH TIME ZONE,
    assigned_to           VARCHAR(100),
    decision_notes        TEXT,
    version               BIGINT NOT NULL DEFAULT 0,
    submitted_at          TIMESTAMP WITH TIME ZONE,
    decided_at            TIMESTAMP WITH TIME ZONE,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_amount_positive CHECK (requested_amount > 0),
    CONSTRAINT chk_tenure_range CHECK (requested_tenure BETWEEN 1 AND 360)
);

CREATE INDEX idx_applications_tenant_status ON loan_applications(tenant_id, status);
CREATE INDEX idx_applications_customer ON loan_applications(tenant_id, customer_id);
CREATE INDEX idx_applications_submitted ON loan_applications(tenant_id, submitted_at);

-- Duplicate check: same customer + product within 30 days
CREATE INDEX idx_applications_dedup ON loan_applications(tenant_id, customer_id, product_code, submitted_at)
    WHERE status NOT IN ('REJECTED', 'WITHDRAWN', 'OFFER_EXPIRED');

-- ─── Loan Offers ───
CREATE TABLE loan_offers (
    offer_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id        UUID NOT NULL REFERENCES loan_applications(application_id),
    tenant_id             UUID NOT NULL,
    sanctioned_amount     NUMERIC(19,4) NOT NULL,
    interest_rate         NUMERIC(8,6) NOT NULL,
    tenure_months         INTEGER NOT NULL,
    emi                   NUMERIC(19,4) NOT NULL,
    processing_fee        NUMERIC(19,4) NOT NULL DEFAULT 0,
    apr                   NUMERIC(8,6) NOT NULL,
    total_interest        NUMERIC(19,4),
    total_repayment       NUMERIC(19,4),
    currency              VARCHAR(3) NOT NULL DEFAULT 'INR',
    generated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at            TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_offers_application ON loan_offers(tenant_id, application_id);

-- ─── Application Documents ───
CREATE TABLE application_documents (
    document_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id        UUID NOT NULL REFERENCES loan_applications(application_id),
    tenant_id             UUID NOT NULL,
    document_type         VARCHAR(50) NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    file_name             VARCHAR(255) NOT NULL,
    storage_url           TEXT NOT NULL,
    rejection_reason      TEXT,
    uploaded_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    verified_at           TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_documents_application ON application_documents(tenant_id, application_id);

-- ─── Outbox Events ───
CREATE TABLE outbox_events (
    event_id              UUID PRIMARY KEY,
    aggregate_type        VARCHAR(100) NOT NULL,
    aggregate_id          UUID NOT NULL,
    event_type            VARCHAR(200) NOT NULL,
    tenant_id             UUID NOT NULL,
    payload               BYTEA NOT NULL,
    metadata              JSONB NOT NULL DEFAULT '{}',
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at          TIMESTAMP WITH TIME ZONE,
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX idx_outbox_pending ON outbox_events(status, created_at) WHERE status = 'PENDING';

-- ─── Row-Level Security ───
ALTER TABLE loan_applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_applications FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_applications ON loan_applications
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

ALTER TABLE loan_offers ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_offers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_offers ON loan_offers
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

ALTER TABLE application_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE application_documents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_documents ON application_documents
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
