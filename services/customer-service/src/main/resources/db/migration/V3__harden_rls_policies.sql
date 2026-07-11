-- ═══════════════════════════════════════════════════════════════════════════
-- Harden pre-existing RLS policies (see dev/RLS_DESIGN.md §8)
--
-- V1 created these policies with a read-only, non-fail-closed predicate:
--   USING (tenant_id = current_setting('app.tenant_id')::uuid)
-- Two gaps: (1) no WITH CHECK, so writes were not tenant-constrained; (2) the
-- plain current_setting throws if the variable is unset — this migration moves
-- to the ',true' (missing_ok) form so an unset tenant fails *closed* (NULL
-- predicate → zero rows on read, rejection on write) rather than erroring.
--
-- Recreated (DROP + CREATE) so both USING and WITH CHECK use the hardened form.
-- Ships dark: inert until the app connects as originex_app.
-- ═══════════════════════════════════════════════════════════════════════════

DROP POLICY IF EXISTS tenant_isolation_customers ON customers;
CREATE POLICY tenant_isolation_customers ON customers
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

DROP POLICY IF EXISTS tenant_isolation_kyc ON kyc_records;
CREATE POLICY tenant_isolation_kyc ON kyc_records
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

DROP POLICY IF EXISTS tenant_isolation_bank ON bank_accounts;
CREATE POLICY tenant_isolation_bank ON bank_accounts
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
