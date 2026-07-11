-- ═══════════════════════════════════════════════════════════════════════════
-- Close RLS coverage gap: addresses (see dev/RLS_DESIGN.md §8)
--
-- addresses carries `tenant_id UUID NOT NULL` (V1) and holds address PII, but
-- V1 never enabled row-level security on it. Every other tenant-scoped table in
-- this service is protected; this brings addresses in line.
--
-- Created directly in the hardened form used going forward:
--   * fail-closed  — current_setting('app.tenant_id', true) returns NULL when
--                    the session variable is unset, so the predicate is never
--                    true and no rows leak (reads return nothing, writes reject).
--   * WITH CHECK   — write-side enforcement: a row's tenant_id must match the
--                    active tenant, so a caller cannot INSERT/UPDATE across tenants.
--
-- Ships dark: while services still connect as a superuser (Phase 0), RLS is not
-- enforced, so this migration is inert until the app connects as originex_app.
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE addresses ENABLE ROW LEVEL SECURITY;
ALTER TABLE addresses FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_addresses ON addresses
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
