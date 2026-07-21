-- ═══════════════════════════════════════════════════════════════════════════
-- Create outbox_events and inbox_events — the tables partner-integration never had.
--
-- partner-integration-service could not boot against its own schema. With
-- ddl-auto=validate:
--   Schema-validation: missing table [inbox_events]
--   → Unable to build Hibernate SessionFactory → Error starting ApplicationContext
--
-- Hibernate names one table at a time; outbox_events was equally absent. Both are
-- created here. Unlike bre, partner-integration has spring-kafka on the classpath,
-- so it never hit the KafkaTemplate class-load issue — only the missing tables.
--
-- The starter's OutboxAutoConfiguration scans OutboxEventJpaEntity and
-- InboxEventJpaEntity for every JPA service unconditionally, so this service must
-- carry both even though it neither publishes nor consumes events — it is a
-- synchronous request/response integration facade. (Whether an event-free service
-- should be forced to carry these is a platform question, KI-9.)
--
-- Definitions validated against the entities, identical to the other services'.
-- idx_outbox_pending (status, created_at) matches all seven that already have it.
-- No RLS policies, matching every other service — the poller must see all tenants.
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
