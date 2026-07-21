-- ═══════════════════════════════════════════════════════════════════════════
-- Create outbox_events and inbox_events — the tables notification never had,
-- without which it cannot boot.
--
-- notification-service failed at startup with ddl-auto=validate:
--   Schema-validation: missing table [inbox_events]
--   → Unable to build Hibernate SessionFactory → Error starting ApplicationContext
--
-- Hibernate names one missing table at a time, so inbox_events surfaced first;
-- outbox_events was equally absent. Both are created here.
--
-- The starter's OutboxAutoConfiguration declares @EntityScan("com.originex") gated
-- only on @ConditionalOnClass(jakarta.persistence.Entity), so every service with
-- JPA on the classpath scans OutboxEventJpaEntity and InboxEventJpaEntity and must
-- have both tables — there is no per-service opt-out, and it applies whether or not
-- the service publishes or consumes events. That scan is deliberate, so the fix
-- belongs in the service. (Whether services that consume no events should be
-- required to carry an inbox at all is a platform question, tracked separately.)
--
-- Definitions validated against the entities themselves rather than copied from a
-- sibling on trust:
--   InboxEventJpaEntity  — event_id UUID @Id, event_type NOT NULL, processed_at NOT NULL.
--   OutboxEventJpaEntity — event_id UUID @Id, aggregate_type/aggregate_id/event_type/
--                          tenant_id/payload/status/created_at NOT NULL,
--                          metadata jsonb, published_at nullable.
-- The sibling definitions agree with this, with one wrinkle worth knowing: ledger's
-- V1 omitted published_at and added it later in V3__add_outbox_published_at.sql, so
-- a naive copy of ledger's V1 would have been short a column.
--
-- No RLS policies, matching every other service: the outbox/inbox are per-service
-- infrastructure. outbox_events does carry tenant_id (it is stamped on each event
-- for downstream routing), but no service applies a policy to it — the poller must
-- see every tenant's rows to publish them.
-- ═══════════════════════════════════════════════════════════════════════════

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

CREATE INDEX idx_outbox_pending ON outbox_events (status, created_at);

CREATE TABLE inbox_events (
    event_id        UUID PRIMARY KEY,
    event_type      VARCHAR(200) NOT NULL,
    processed_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
