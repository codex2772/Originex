-- ═══════════════════════════════════════════════════════════════════════════
-- Create inbox_events — the table los never had, without which it cannot boot.
--
-- los-service failed at startup with ddl-auto=validate:
--   Schema-validation: missing table [inbox_events]
--   → Unable to build Hibernate SessionFactory → Error starting ApplicationContext
--
-- The starter's OutboxAutoConfiguration declares @EntityScan("com.originex")
-- gated only on @ConditionalOnClass(jakarta.persistence.Entity), so EVERY service
-- with JPA on the classpath scans both OutboxEventJpaEntity and
-- InboxEventJpaEntity and must therefore have both tables. There is no per-service
-- opt-out. los created outbox_events in V1 and stopped, so the inbox entity had no
-- table to validate against.
--
-- Definition copied verbatim from the five services that already have it
-- (customer V4, ledger V2, lms V1, payment V2, template V1 — all byte-identical)
-- and checked against InboxEventJpaEntity itself: event_id UUID @Id, event_type
-- NOT NULL, processed_at NOT NULL.
--
-- No RLS policy, matching every other service: the inbox is a per-service
-- idempotency ledger keyed by event_id, with no tenant_id column to scope on.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE inbox_events (
    event_id        UUID PRIMARY KEY,
    event_type      VARCHAR(200) NOT NULL,
    processed_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
