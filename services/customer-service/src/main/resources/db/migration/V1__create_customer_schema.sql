-- ═══════════════════════════════════════════════════════════════
-- V1__create_customer_schema.sql
-- Customer Service initial schema
-- ═══════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─── Customers ───
CREATE TABLE customers (
    customer_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL,
    first_name        VARCHAR(100) NOT NULL,
    last_name         VARCHAR(100) NOT NULL,
    date_of_birth     DATE,
    email             VARCHAR(255),
    phone             VARCHAR(20) NOT NULL,
    phone_verified    BOOLEAN NOT NULL DEFAULT FALSE,
    pan_encrypted     TEXT,
    pan_hash          VARCHAR(64),
    aadhaar_token     VARCHAR(64),
    status            VARCHAR(30) NOT NULL DEFAULT 'REGISTERED',
    kyc_status        VARCHAR(30) NOT NULL DEFAULT 'NOT_INITIATED',
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_customers_tenant_phone ON customers(tenant_id, phone);
CREATE UNIQUE INDEX idx_customers_tenant_pan ON customers(tenant_id, pan_hash) WHERE pan_hash IS NOT NULL;
CREATE INDEX idx_customers_tenant_status ON customers(tenant_id, status);
CREATE INDEX idx_customers_aadhaar ON customers(tenant_id, aadhaar_token) WHERE aadhaar_token IS NOT NULL;

-- ─── KYC Records ───
CREATE TABLE kyc_records (
    kyc_record_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id             UUID NOT NULL REFERENCES customers(customer_id),
    tenant_id               UUID NOT NULL,
    kyc_type                VARCHAR(30) NOT NULL,
    status                  VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    verification_reference  VARCHAR(255),
    rejection_reason        TEXT,
    submitted_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    verified_at             TIMESTAMP WITH TIME ZONE,
    expires_at              TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_kyc_customer ON kyc_records(tenant_id, customer_id);

-- ─── Bank Accounts ───
CREATE TABLE bank_accounts (
    bank_account_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id         UUID NOT NULL REFERENCES customers(customer_id),
    tenant_id           UUID NOT NULL,
    account_number      TEXT NOT NULL,       -- Encrypted
    account_number_masked VARCHAR(20),
    ifsc_code           VARCHAR(11) NOT NULL,
    bank_name           VARCHAR(100) NOT NULL,
    account_holder_name VARCHAR(200) NOT NULL,
    account_type        VARCHAR(20) NOT NULL,
    verified            BOOLEAN NOT NULL DEFAULT FALSE,
    is_primary          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bank_accounts_customer ON bank_accounts(tenant_id, customer_id);

-- ─── Addresses ───
CREATE TABLE addresses (
    address_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id   UUID NOT NULL REFERENCES customers(customer_id),
    tenant_id     UUID NOT NULL,
    address_type  VARCHAR(20) NOT NULL,
    line1         VARCHAR(255) NOT NULL,
    line2         VARCHAR(255),
    city          VARCHAR(100) NOT NULL,
    state         VARCHAR(100) NOT NULL,
    pincode       VARCHAR(10) NOT NULL,
    country       VARCHAR(2) NOT NULL DEFAULT 'IN',
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_addresses_customer ON addresses(tenant_id, customer_id);

-- ─── Outbox Events ───
CREATE TABLE outbox_events (
    event_id        UUID PRIMARY KEY,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(200) NOT NULL,
    tenant_id       UUID NOT NULL,
    payload         BYTEA NOT NULL,
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMP WITH TIME ZONE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX idx_outbox_pending ON outbox_events(status, created_at) WHERE status = 'PENDING';

-- ─── Row-Level Security ───
ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_customers ON customers
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

ALTER TABLE kyc_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE kyc_records FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_kyc ON kyc_records
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

ALTER TABLE bank_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_accounts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_bank ON bank_accounts
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
