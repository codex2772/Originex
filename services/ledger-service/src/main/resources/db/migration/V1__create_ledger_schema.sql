-- ═══════════════════════════════════════════════════════════════
-- V1__create_ledger_schema.sql
-- Event-sourced Ledger schema (append-only event store + read models)
-- ═══════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─── Event Store (append-only — source of truth) ───
CREATE TABLE ledger_events (
    event_sequence      BIGSERIAL,
    event_id            UUID NOT NULL,
    aggregate_type      VARCHAR(50) NOT NULL,   -- 'Account' or 'JournalEntry'
    aggregate_id        UUID NOT NULL,
    tenant_id           UUID NOT NULL,
    event_type          VARCHAR(100) NOT NULL,
    event_data          JSONB NOT NULL,
    occurred_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, aggregate_id, event_sequence)
) PARTITION BY RANGE (occurred_at);

-- Monthly partitions (create via pg_partman or manually)
CREATE TABLE ledger_events_y2026m07 PARTITION OF ledger_events
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE ledger_events_y2026m08 PARTITION OF ledger_events
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

CREATE INDEX idx_ledger_events_aggregate ON ledger_events(tenant_id, aggregate_id, event_sequence);

-- ─── Account Snapshots (read model — cached balance) ───
CREATE TABLE account_snapshots (
    account_id          UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    account_number      VARCHAR(30) NOT NULL,
    name                VARCHAR(200) NOT NULL,
    account_type        VARCHAR(20) NOT NULL,
    normal_balance      VARCHAR(10) NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'INR',
    balance             NUMERIC(19,4) NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    gl_code             VARCHAR(20) NOT NULL,
    loan_id             UUID,
    customer_id         UUID,
    last_event_sequence BIGINT NOT NULL DEFAULT 0,
    opened_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    closed_at           TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_account_number ON account_snapshots(tenant_id, account_number);
CREATE INDEX idx_account_gl ON account_snapshots(tenant_id, gl_code);
CREATE INDEX idx_account_loan ON account_snapshots(tenant_id, loan_id) WHERE loan_id IS NOT NULL;

-- ─── Journal Entries (read model — denormalized for queries) ───
CREATE TABLE journal_entries (
    entry_id            UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    entry_type          VARCHAR(30) NOT NULL,
    posting_date        DATE NOT NULL,
    value_date          DATE NOT NULL,
    description         TEXT,
    source_system       VARCHAR(50),
    source_id           VARCHAR(100),
    source_event_id     VARCHAR(100),
    status              VARCHAR(20) NOT NULL DEFAULT 'POSTED',
    reversal_of         UUID,
    reversed_by         UUID,
    posted_by           VARCHAR(100),
    posted_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_journal_entries_tenant ON journal_entries(tenant_id, posting_date);
CREATE INDEX idx_journal_entries_source ON journal_entries(tenant_id, source_system, source_id);

-- ─── Postings (individual debit/credit legs) ───
CREATE TABLE postings (
    posting_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id            UUID NOT NULL REFERENCES journal_entries(entry_id),
    tenant_id           UUID NOT NULL,
    account_id          UUID NOT NULL,
    side                VARCHAR(10) NOT NULL,    -- 'DEBIT' or 'CREDIT'
    amount              NUMERIC(19,4) NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'INR',
    narration           TEXT,
    posted_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_postings_entry ON postings(tenant_id, entry_id);
CREATE INDEX idx_postings_account ON postings(tenant_id, account_id, posted_at);

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
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX idx_outbox_pending ON outbox_events(status, created_at) WHERE status = 'PENDING';

-- ─── Row-Level Security ───
ALTER TABLE account_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_snapshots FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_accounts ON account_snapshots
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

ALTER TABLE journal_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE journal_entries FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_journals ON journal_entries
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

ALTER TABLE postings ENABLE ROW LEVEL SECURITY;
ALTER TABLE postings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_postings ON postings
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
