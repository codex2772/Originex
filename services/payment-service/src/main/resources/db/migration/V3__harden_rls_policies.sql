-- ═══════════════════════════════════════════════════════════════════════════
-- Harden pre-existing RLS policies (see dev/RLS_DESIGN.md §8)
--
-- V1 created payment_orders/nach_mandates policies with
-- USING (tenant_id = current_setting('app.tenant_id')::uuid) and no WITH CHECK.
-- Recreate them fail-closed (',true' missing_ok form) and add write-side
-- enforcement. Ships dark: inert until the app connects as originex_app.
-- ═══════════════════════════════════════════════════════════════════════════

DROP POLICY IF EXISTS tenant_isolation_payments ON payment_orders;
CREATE POLICY tenant_isolation_payments ON payment_orders
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

DROP POLICY IF EXISTS tenant_isolation_nach ON nach_mandates;
CREATE POLICY tenant_isolation_nach ON nach_mandates
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
