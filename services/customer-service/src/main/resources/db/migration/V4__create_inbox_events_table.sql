-- ═══════════════════════════════════════════════════════════════
-- V4__create_inbox_events_table.sql
-- Idempotent-consumer inbox table.
--
-- The platform starter registers InboxEventJpaEntity (@Table "inbox_events")
-- into every service's persistence unit, so Hibernate ddl-auto=validate requires
-- the table to exist even in a producer-only service like customer-service (which
-- publishes via the outbox but does not consume). Without it the application
-- context fails to start under schema-validation — surfaced by
-- CustomerHttpRlsIsolationIntegrationTest booting the full app.
--
-- Operational infrastructure — NOT tenant-scoped, so no RLS (same as
-- outbox_events). Definition matches the consumer services (payment/ledger/lms).
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE inbox_events (
    event_id        UUID PRIMARY KEY,
    event_type      VARCHAR(200) NOT NULL,
    processed_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
