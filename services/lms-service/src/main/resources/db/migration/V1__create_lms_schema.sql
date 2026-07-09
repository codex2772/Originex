-- ═══════════════════════════════════════════════════════════════
-- V1__create_lms_schema.sql
-- Loan Management System schema
-- ═══════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─── Loans ───
CREATE TABLE loans (
    loan_id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL,
    customer_id             UUID NOT NULL,
    application_id          UUID NOT NULL,
    product_code            VARCHAR(50) NOT NULL,
    loan_account_number     VARCHAR(30) NOT NULL,
    status                  VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    sanctioned_amount       NUMERIC(19,4) NOT NULL,
    disbursed_amount        NUMERIC(19,4) NOT NULL DEFAULT 0,
    outstanding_principal   NUMERIC(19,4) NOT NULL DEFAULT 0,
    outstanding_interest    NUMERIC(19,4) NOT NULL DEFAULT 0,
    outstanding_charges     NUMERIC(19,4) NOT NULL DEFAULT 0,
    interest_rate           NUMERIC(8,6) NOT NULL,
    rate_type               VARCHAR(10) NOT NULL DEFAULT 'FIXED',
    tenure_months           INTEGER NOT NULL,
    remaining_tenure        INTEGER NOT NULL,
    emi_amount              NUMERIC(19,4) NOT NULL,
    currency                VARCHAR(3) NOT NULL DEFAULT 'INR',
    sanction_date           DATE NOT NULL,
    first_disbursement_date DATE,
    maturity_date           DATE,
    next_due_date           DATE,
    last_payment_date       DATE,
    dpd                     INTEGER NOT NULL DEFAULT 0,
    max_dpd                 INTEGER NOT NULL DEFAULT 0,
    asset_classification    VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    version                 BIGINT NOT NULL DEFAULT 0,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_loans_account_number ON loans(tenant_id, loan_account_number);
CREATE INDEX idx_loans_customer ON loans(tenant_id, customer_id);
CREATE INDEX idx_loans_status ON loans(tenant_id, status);
CREATE INDEX idx_loans_dpd ON loans(tenant_id, dpd) WHERE status = 'ACTIVE';
CREATE INDEX idx_loans_application ON loans(tenant_id, application_id);

-- ─── Installments (Repayment Schedule) ───
CREATE TABLE installments (
    installment_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id             UUID NOT NULL REFERENCES loans(loan_id),
    tenant_id           UUID NOT NULL,
    installment_number  INTEGER NOT NULL,
    due_date            DATE NOT NULL,
    principal_due       NUMERIC(19,4) NOT NULL,
    interest_due        NUMERIC(19,4) NOT NULL,
    total_due           NUMERIC(19,4) NOT NULL,
    principal_paid      NUMERIC(19,4) NOT NULL DEFAULT 0,
    interest_paid       NUMERIC(19,4) NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL DEFAULT 'UPCOMING',
    paid_date           DATE,
    UNIQUE(loan_id, installment_number)
);

CREATE INDEX idx_installments_loan ON installments(tenant_id, loan_id);
CREATE INDEX idx_installments_due ON installments(tenant_id, due_date, status)
    WHERE status IN ('DUE', 'OVERDUE');

-- ─── Disbursements ───
CREATE TABLE disbursements (
    disbursement_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id             UUID NOT NULL REFERENCES loans(loan_id),
    tenant_id           UUID NOT NULL,
    amount              NUMERIC(19,4) NOT NULL,
    beneficiary_account VARCHAR(50),
    payment_reference   VARCHAR(100),
    status              VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    initiated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_disbursements_loan ON disbursements(tenant_id, loan_id);

-- ─── Loan Charges ───
CREATE TABLE loan_charges (
    charge_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id         UUID NOT NULL REFERENCES loans(loan_id),
    tenant_id       UUID NOT NULL,
    charge_type     VARCHAR(30) NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,
    paid_amount     NUMERIC(19,4) NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    levied_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_charges_loan ON loan_charges(tenant_id, loan_id);

-- ─── Outbox & Inbox ───
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

CREATE TABLE inbox_events (
    event_id        UUID PRIMARY KEY,
    event_type      VARCHAR(200) NOT NULL,
    processed_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- ─── Row-Level Security ───
ALTER TABLE loans ENABLE ROW LEVEL SECURITY;
ALTER TABLE loans FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_loans ON loans
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

ALTER TABLE installments ENABLE ROW LEVEL SECURITY;
ALTER TABLE installments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_installments ON installments
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
