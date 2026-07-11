-- ═══════════════════════════════════════════════════════════════════════════
-- Harden pre-existing RLS policies (see dev/RLS_DESIGN.md §8)
--
-- V1 created loans/installments policies with
-- USING (tenant_id = current_setting('app.tenant_id')::uuid) and no WITH CHECK.
-- Recreate them fail-closed (',true' missing_ok form) and add write-side
-- enforcement. (disbursements/loan_charges were already created hardened in V3.)
-- Ships dark: inert until the app connects as originex_app.
-- ═══════════════════════════════════════════════════════════════════════════

DROP POLICY IF EXISTS tenant_isolation_loans ON loans;
CREATE POLICY tenant_isolation_loans ON loans
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

DROP POLICY IF EXISTS tenant_isolation_installments ON installments;
CREATE POLICY tenant_isolation_installments ON installments
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
