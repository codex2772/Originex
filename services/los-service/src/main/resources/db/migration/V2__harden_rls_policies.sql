-- ═══════════════════════════════════════════════════════════════════════════
-- Harden pre-existing RLS policies (see dev/RLS_DESIGN.md §8)
--
-- V1 created these with USING (tenant_id = current_setting('app.tenant_id')::uuid)
-- and no WITH CHECK. Recreate them fail-closed (',true' missing_ok form) and add
-- write-side enforcement. Ships dark: inert until the app connects as originex_app.
-- ═══════════════════════════════════════════════════════════════════════════

DROP POLICY IF EXISTS tenant_isolation_applications ON loan_applications;
CREATE POLICY tenant_isolation_applications ON loan_applications
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

DROP POLICY IF EXISTS tenant_isolation_offers ON loan_offers;
CREATE POLICY tenant_isolation_offers ON loan_offers
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

DROP POLICY IF EXISTS tenant_isolation_documents ON application_documents;
CREATE POLICY tenant_isolation_documents ON application_documents
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
