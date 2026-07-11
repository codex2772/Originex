-- ═══════════════════════════════════════════════════════════════════════════
-- Harden pre-existing RLS policies (see dev/RLS_DESIGN.md §8)
--
-- template-service is the scaffold/reference service. V1 created the samples
-- policy with USING (tenant_id = current_setting('app.tenant_id')::uuid) and no
-- WITH CHECK. Recreate it fail-closed (',true' missing_ok form) with write-side
-- enforcement so the reference implementation demonstrates the hardened pattern.
-- Ships dark: inert until the app connects as originex_app.
-- ═══════════════════════════════════════════════════════════════════════════

DROP POLICY IF EXISTS tenant_isolation_samples ON samples;
CREATE POLICY tenant_isolation_samples ON samples
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
