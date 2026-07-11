-- ═══════════════════════════════════════════════════════════════════════════
-- Close RLS coverage gap: disbursements, loan_charges (see dev/RLS_DESIGN.md §8)
--
-- Both tables carry `tenant_id UUID NOT NULL` (V1) but V1 enabled RLS only on
-- loans and installments. disbursements records money movement and loan_charges
-- records fees — both tenant-scoped — so they must be isolated too.
--
-- Created directly in the hardened form (fail-closed current_setting(...,true)
-- + WITH CHECK write-side enforcement). Ships dark: inert until the app connects
-- as originex_app.
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE disbursements ENABLE ROW LEVEL SECURITY;
ALTER TABLE disbursements FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_disbursements ON disbursements
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

ALTER TABLE loan_charges ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_charges FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_loan_charges ON loan_charges
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
