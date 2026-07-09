-- ═══════════════════════════════════════════════════════════════
-- V1__create_payment_schema.sql
-- Payment Service — payment orders and NACH mandates
-- ═══════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE payment_orders (
    payment_order_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    loan_id                 UUID NOT NULL,
    customer_id             UUID,
    payment_type            VARCHAR(30) NOT NULL,     -- DISBURSEMENT, REPAYMENT_COLLECTION, REFUND, FEE_COLLECTION
    payment_rail            VARCHAR(20) NOT NULL,     -- NEFT, RTGS, IMPS, UPI, NACH, INTERNAL
    status                  VARCHAR(25) NOT NULL DEFAULT 'CREATED',
    amount                  NUMERIC(20,4) NOT NULL,
    currency                CHAR(3) NOT NULL DEFAULT 'INR',
    beneficiary_account_number  VARCHAR(30),
    beneficiary_ifsc        VARCHAR(12),
    beneficiary_name        VARCHAR(200),
    beneficiary_bank_name   VARCHAR(100),
    mandate_id              VARCHAR(100),
    umrn                    VARCHAR(50),
    payment_reference       VARCHAR(100) NOT NULL UNIQUE,
    external_transaction_id VARCHAR(100),             -- UTR / RRN / UPI ref
    bank_reference_number   VARCHAR(100),
    failure_reason          TEXT,
    retry_count             INTEGER NOT NULL DEFAULT 0,
    max_retries             INTEGER NOT NULL DEFAULT 3,
    scheduled_at            TIMESTAMP WITH TIME ZONE,
    initiated_at            TIMESTAMP WITH TIME ZONE,
    completed_at            TIMESTAMP WITH TIME ZONE,
    failed_at               TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version                 BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_payment_orders_tenant_loan ON payment_orders(tenant_id, loan_id);
CREATE INDEX idx_payment_orders_status ON payment_orders(status) WHERE status IN ('CREATED','INITIATED','PROCESSING','RETRY_PENDING');
CREATE INDEX idx_payment_orders_reference ON payment_orders(payment_reference);
CREATE INDEX idx_payment_orders_utr ON payment_orders(external_transaction_id) WHERE external_transaction_id IS NOT NULL;

-- NACH Mandates
CREATE TABLE nach_mandates (
    mandate_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    loan_id                 UUID NOT NULL,
    customer_id             UUID,
    umrn                    VARCHAR(50),              -- Unique Mandate Reference Number from NPCI
    bank_account_number     VARCHAR(30),
    ifsc_code               VARCHAR(12),
    bank_name               VARCHAR(100),
    account_holder_name     VARCHAR(200),
    status                  VARCHAR(30) NOT NULL DEFAULT 'PENDING_REGISTRATION',
    max_debit_amount        NUMERIC(20,4) NOT NULL,
    currency                CHAR(3) NOT NULL DEFAULT 'INR',
    frequency               VARCHAR(20) NOT NULL DEFAULT 'MONTHLY',
    start_date              TIMESTAMP WITH TIME ZONE,
    end_date                TIMESTAMP WITH TIME ZONE,
    registered_at           TIMESTAMP WITH TIME ZONE,
    cancelled_at            TIMESTAMP WITH TIME ZONE,
    cancellation_reason     TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_nach_mandates_tenant_loan ON nach_mandates(tenant_id, loan_id);
CREATE INDEX idx_nach_mandates_active ON nach_mandates(tenant_id, loan_id) WHERE status = 'ACTIVE';

-- Outbox and Inbox (auto-created by starter, included here for documentation)
-- outbox_events and inbox_events tables are created by OutboxAutoConfiguration

-- ─── Row-Level Security ───
ALTER TABLE payment_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_orders FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_payments ON payment_orders
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

ALTER TABLE nach_mandates ENABLE ROW LEVEL SECURITY;
ALTER TABLE nach_mandates FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_nach ON nach_mandates
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
