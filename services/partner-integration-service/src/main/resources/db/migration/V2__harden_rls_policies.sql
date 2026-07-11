-- ═══════════════════════════════════════════════════════════════════════════
-- Harden pre-existing RLS policies (see dev/RLS_DESIGN.md §8)
--
-- V1 created the integration_requests policy with
-- USING (tenant_id = current_setting('app.tenant_id')::uuid) and no WITH CHECK.
-- Recreate it fail-closed (',true' missing_ok form) and add write-side
-- enforcement. Ships dark: inert until the app connects as originex_app.
-- ═══════════════════════════════════════════════════════════════════════════

DROP POLICY IF EXISTS tenant_isolation_integration_requests ON integration_requests;
CREATE POLICY tenant_isolation_integration_requests ON integration_requests
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
