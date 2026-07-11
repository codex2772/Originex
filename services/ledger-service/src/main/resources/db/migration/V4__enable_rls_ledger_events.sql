-- ═══════════════════════════════════════════════════════════════════════════
-- Close RLS coverage gap: ledger_events (see dev/RLS_DESIGN.md §8)
--
-- ledger_events is the tenant-scoped event store; `tenant_id` is the leading
-- column of its primary key (V1) but RLS was never enabled on it. V1 enabled RLS
-- only on account_snapshots, journal_entries and postings.
--
-- ledger_events is RANGE-partitioned by occurred_at. RLS is enabled on BOTH the
-- partitioned parent AND each existing partition:
--   * A policy on the parent is enforced for rows accessed *through* the parent
--     (`SELECT ... FROM ledger_events`), which is the application's only path.
--   * A partition accessed *directly* (`SELECT ... FROM ledger_events_y2026m08`)
--     does NOT inherit the parent's policy, so without its own policy it would
--     leak across tenants. We therefore enable RLS + policy on each partition too
--     (verified: a direct partition query as the wrong tenant returns 0 rows).
--
-- IMPORTANT: Postgres cannot auto-apply a parent policy to future partitions.
-- Whatever creates the next monthly partition (a future migration or a partition
-- management job) MUST enable RLS + create the same policy on it, or direct
-- access to that partition will bypass tenant isolation. This is the same
-- "revisit if direct access is introduced" rule noted for channel_dispatches.
--
-- Created directly in the hardened form (fail-closed current_setting(...,true)
-- + WITH CHECK). Ships dark: inert until the app connects as originex_app.
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE ledger_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_ledger_events ON ledger_events
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- Existing partitions (V1 created these two). New partitions must repeat this.
ALTER TABLE ledger_events_y2026m07 ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_events_y2026m07 FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_ledger_events ON ledger_events_y2026m07
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE ledger_events_y2026m08 ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_events_y2026m08 FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_ledger_events ON ledger_events_y2026m08
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
