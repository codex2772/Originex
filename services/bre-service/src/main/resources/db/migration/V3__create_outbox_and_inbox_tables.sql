-- ═══════════════════════════════════════════════════════════════════════════
-- Create outbox_events and inbox_events — the tables bre never had.
--
-- bre-service could not boot at all. Two separate blockers were in the way,
-- surfaced one at a time:
--   1. NoClassDefFoundError: KafkaTemplate — bre is the only service without
--      spring-kafka on the classpath, and the starter's outbox poller force-loaded
--      the class. Fixed at the starter level (see fix: make the outbox Kafka poller
--      truly optional on the classpath); not a schema issue.
--   2. Once past that, ddl-auto=validate reported:
--        Schema-validation: missing table [inbox_events]
--      Hibernate names one table at a time; outbox_events was equally absent.
--
-- Both tables are created here. The starter's OutboxAutoConfiguration scans
-- OutboxEventJpaEntity and InboxEventJpaEntity for every JPA service
-- unconditionally, so bre must carry both even though it neither publishes nor
-- consumes events (it evaluates rules synchronously over HTTP). Whether an
-- event-free service should be forced to carry these is a platform question,
-- tracked separately; the fix for booting today is the table.
--
-- Definitions validated against the entities (identical to the other services'):
--   InboxEventJpaEntity  — event_id UUID @Id, event_type NOT NULL, processed_at NOT NULL.
--   OutboxEventJpaEntity — event_id UUID @Id; aggregate_type/aggregate_id/event_type/
--                          tenant_id/payload/status/created_at NOT NULL; metadata jsonb;
--                          published_at nullable.
--
-- No RLS policies, matching every other service. outbox_events carries tenant_id
-- for downstream routing, but no service applies a policy to it — the poller must
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
