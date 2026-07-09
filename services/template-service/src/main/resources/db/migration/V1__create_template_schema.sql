-- ═══════════════════════════════════════════════════════════════
-- V1__create_template_schema.sql
-- Template service initial schema (reference implementation)
-- ═══════════════════════════════════════════════════════════════

-- Enable UUID generation
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─── Sample Aggregate Table ───
CREATE TABLE samples (
    sample_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    status          VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    amount          NUMERIC(19,4),
    currency        VARCHAR(3) DEFAULT 'INR',
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_samples_tenant ON samples(tenant_id);
CREATE INDEX idx_samples_status ON samples(tenant_id, status);

-- ─── Outbox Table (Transactional Outbox Pattern) ───
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

CREATE INDEX idx_outbox_pending ON outbox_events(status, created_at)
    WHERE status = 'PENDING';

-- ─── Inbox Table (Idempotent Consumer Pattern) ───
CREATE TABLE inbox_events (
    event_id        UUID PRIMARY KEY,
    event_type      VARCHAR(200) NOT NULL,
    processed_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- ─── Row-Level Security ───
ALTER TABLE samples ENABLE ROW LEVEL SECURITY;
ALTER TABLE samples FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_samples ON samples
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
