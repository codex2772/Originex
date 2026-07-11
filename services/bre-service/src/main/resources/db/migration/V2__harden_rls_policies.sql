-- ═══════════════════════════════════════════════════════════════════════════
-- Harden pre-existing RLS policies (see dev/RLS_DESIGN.md §8)
--
-- V1 created bre_rule_sets/bre_rules policies with
-- USING (tenant_id = current_setting('app.tenant_id')::uuid) and no WITH CHECK.
-- Recreate them fail-closed (',true' missing_ok form) and add write-side
-- enforcement. Ships dark: inert until the app connects as originex_app.
-- ═══════════════════════════════════════════════════════════════════════════

DROP POLICY IF EXISTS tenant_isolation_rule_sets ON bre_rule_sets;
CREATE POLICY tenant_isolation_rule_sets ON bre_rule_sets
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

DROP POLICY IF EXISTS tenant_isolation_rules ON bre_rules;
CREATE POLICY tenant_isolation_rules ON bre_rules
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
