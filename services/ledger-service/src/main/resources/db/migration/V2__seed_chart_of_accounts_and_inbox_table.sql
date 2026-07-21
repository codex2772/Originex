-- ═══════════════════════════════════════════════════════════════
-- V2__seed_chart_of_accounts_and_inbox_table.sql
-- Phase 0 stabilization — see CLAUDE_ANALYSIS.md §6 items 1 and 2.
--
-- NOTE: V1__create_ledger_schema.sql's ledger_events PRIMARY KEY was also
-- fixed as part of this same commit (added the missing occurred_at partition
-- key column) — without that fix, V1 itself never successfully creates
-- account_snapshots on a real Postgres 16 database, so this migration's
-- INSERTs below would fail with "relation does not exist" regardless of
-- anything in this file. See dev/PHASE0_VERIFICATION.md's BEFORE baseline
-- for the verified repro.
--
-- Problem 1: LmsEventConsumer (adapter/in/kafka) uses @Transactional and
-- InboxEventRepository (from libs/spring-boot-starter) for idempotent
-- consumption, but V1 never created an inbox_events table for this
-- service. Every LMS event processed here fails once the inbox write is
-- attempted (or fails Hibernate schema validation at boot, depending on
-- ddl-auto). This migration adds the table using the exact same shape
-- InboxEventJpaEntity expects (verified against
-- libs/spring-boot-starter/.../outbox/InboxEventJpaEntity.java and
-- against lms-service's V1 migration, which has the same table correctly).
--
-- Problem 2: LmsEventConsumer references three hardcoded GL account UUIDs
-- (POOL_ACCOUNT_ID, INTEREST_INCOME_ID, INTEREST_RECEIVABLE_ID) that are
-- never seeded anywhere. LedgerApplicationService.postJournalEntry()
-- resolves every posting's account via
-- accountRepository.findById(tenantId, accountId).orElseThrow(...), so
-- every LoanDisbursed / RepaymentAllocated / InterestAccrued event throws
-- IllegalArgumentException: Account not found for these three legs. This
-- migration seeds the three accounts so posting succeeds.
--
-- NOTE ON MULTI-TENANCY (read before extending this pattern):
-- account_snapshots.account_id is the primary key, and
-- AccountRepository.findById(tenantId, accountId) filters by BOTH
-- tenant_id and account_id (see AccountJpaRepository.findByTenantAndId).
-- A single hardcoded account_id can therefore only ever belong to ONE
-- tenant — this is a structural limitation of the current hardcoded-UUID
-- design, not something this migration can fix without a real per-tenant
-- chart-of-accounts service (tracked as a Phase 2 item in
-- CLAUDE_ANALYSIS.md §2 Phase 2). This migration seeds the three accounts
-- under the platform's existing default/sentinel tenant
-- (00000000-0000-0000-0000-000000000001), which is already used
-- identically as the default tenant for bre-service's DEFAULT rule set
-- (services/bre-service/.../V1__create_bre_schema.sql) and
-- notification-service's default templates
-- (services/notification-service/.../V1__create_notification_schema.sql).
-- Any additional tenant that needs these GL accounts will still hit
-- "Account not found" until the chart-of-accounts service exists — this
-- migration unblocks the default-tenant flow, it does not make GL account
-- resolution multi-tenant-correct.
--
-- gl_code values follow the numbering already established by
-- LmsEventConsumer.resolveLoanReceivableAccount(), which auto-creates a
-- per-loan ASSET account with gl_code '1100'. This migration uses '1000'
-- for the pool/cash account and '1200' for interest receivable to stay in
-- the same 1xxx (ASSET) band, and '4000' for interest income as a 4xxx
-- (REVENUE) account, matching Account.AccountType.REVENUE's normal
-- CREDIT balance.
-- ═══════════════════════════════════════════════════════════════

-- ─── Inbox Events (idempotent consumer pattern) ───
-- Mirrors InboxEventJpaEntity exactly: event_id PK, event_type, processed_at.
-- No tenant_id column and no RLS policy, matching every other service's
-- inbox_events table (inbox rows are keyed by globally-unique Kafka event
-- IDs, not scoped per tenant).
CREATE TABLE inbox_events (
    event_id        UUID PRIMARY KEY,
    event_type      VARCHAR(200) NOT NULL,
    processed_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- ─── Seed Chart of Accounts (default tenant) ───
-- account_id values match the hardcoded constants in
-- LmsEventConsumer.java exactly.
INSERT INTO account_snapshots
    (account_id, tenant_id, account_number, name, account_type, normal_balance, currency, gl_code)
VALUES
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
     'POOL-001', 'Pool / Disbursement Account', 'ASSET', 'DEBIT', 'INR', '1000'),

    ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
     'REV-INT-001', 'Interest Income', 'REVENUE', 'CREDIT', 'INR', '4000'),

    ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
     'AST-INTREC-001', 'Interest Receivable', 'ASSET', 'DEBIT', 'INR', '1200');
