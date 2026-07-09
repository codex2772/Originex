-- ═══════════════════════════════════════════════════════════════
-- V2__create_outbox_and_inbox_tables.sql
-- Phase 0 stabilization — see CLAUDE_ANALYSIS.md §6 item 2 / §7 item 7.
--
-- V1's final comment claims "outbox_events and inbox_events tables are
-- created by OutboxAutoConfiguration" — that's incorrect.
-- OutboxAutoConfiguration (libs/spring-boot-starter) only registers the
-- OutboxPublisher/OutboxPoller beans and JPA repositories; it issues no
-- DDL. Reproduced empirically: applying V1 alone against a clean
-- postgres:16-alpine leaves only payment_orders and nach_mandates —
-- neither outbox_events nor inbox_events exists.
--
-- This matters here more than in most services because payment-service
-- both PUBLISHES via OutboxPublisher (PaymentApplicationService — 5 event
-- types: DisbursementInitiated, NachMandateRegistered, CollectionInitiated,
-- PaymentReceived, PaymentFailed) and CONSUMES via InboxEventRepository
-- (LmsPaymentEventConsumer, listening for LoanDisbursed). With
-- spring.jpa.hibernate.ddl-auto: validate set (confirmed in
-- application.yml, same as every other service), Hibernate validates
-- OutboxEventJpaEntity and InboxEventJpaEntity against the schema at
-- boot — without these tables the service cannot start at all.
--
-- Column shapes and the idx_outbox_pending index match
-- customer-service/los-service/lms-service's V1 migrations exactly
-- (verified by direct comparison), including published_at, which those
-- three have but which ledger-service's V1 does NOT — a separate,
-- narrower gap in ledger-service noted for a future fix, out of scope
-- for this migration.
-- ═══════════════════════════════════════════════════════════════

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

-- ─── Inbox Events (idempotent consumer pattern) ───
-- Mirrors InboxEventJpaEntity exactly, matching lms-service's V1 and
-- ledger-service's V2 shape: event_id PK, event_type, processed_at.
-- No tenant_id column and no RLS policy — inbox rows are keyed by
-- globally-unique Kafka event IDs, not scoped per tenant, consistent
-- with every other service's inbox_events table.
CREATE TABLE inbox_events (
    event_id        UUID PRIMARY KEY,
    event_type      VARCHAR(200) NOT NULL,
    processed_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
