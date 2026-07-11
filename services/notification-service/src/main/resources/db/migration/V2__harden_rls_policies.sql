-- ═══════════════════════════════════════════════════════════════════════════
-- Harden pre-existing RLS policies (see dev/RLS_DESIGN.md §8)
--
-- V1 created notification_requests/notification_templates policies with
-- USING (tenant_id = current_setting('app.tenant_id')::uuid) and no WITH CHECK.
-- Recreate them fail-closed (',true' missing_ok form) and add write-side
-- enforcement. Ships dark: inert until the app connects as originex_app.
--
-- channel_dispatches is intentionally NOT covered here: it has no tenant_id
-- column and is reached only through its RLS-protected parent
-- (notification_requests, FK ON DELETE CASCADE), so it is protected
-- transitively. Revisit if a direct-access path to channel_dispatches is ever
-- introduced (see dev/RLS_DESIGN.md §8).
-- ═══════════════════════════════════════════════════════════════════════════

DROP POLICY IF EXISTS tenant_isolation_notif ON notification_requests;
CREATE POLICY tenant_isolation_notif ON notification_requests
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

DROP POLICY IF EXISTS tenant_isolation_templates ON notification_templates;
CREATE POLICY tenant_isolation_templates ON notification_templates
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
