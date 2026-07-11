-- ═══════════════════════════════════════════════════════════════════════════
-- Harden pre-existing RLS policies (see dev/RLS_DESIGN.md §8)
--
-- V1 created account_snapshots/journal_entries/postings policies with
-- USING (tenant_id = current_setting('app.tenant_id')::uuid) and no WITH CHECK.
-- Recreate them fail-closed (',true' missing_ok form) and add write-side
-- enforcement. (ledger_events was already created hardened in V4.)
-- Ships dark: inert until the app connects as originex_app.
-- ═══════════════════════════════════════════════════════════════════════════

DROP POLICY IF EXISTS tenant_isolation_accounts ON account_snapshots;
CREATE POLICY tenant_isolation_accounts ON account_snapshots
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

DROP POLICY IF EXISTS tenant_isolation_journals ON journal_entries;
CREATE POLICY tenant_isolation_journals ON journal_entries
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

DROP POLICY IF EXISTS tenant_isolation_postings ON postings;
CREATE POLICY tenant_isolation_postings ON postings
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
