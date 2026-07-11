-- ═══════════════════════════════════════════════════════════════════════════
-- RLS role provisioning for role-aware integration tests (Testcontainers).
--
-- Run once by PostgreSQLContainer.withInitScript(...) as the container superuser,
-- against the container's single database, BEFORE Spring/Flyway start. It is the
-- test-scoped, single-database analogue of dev/init-scripts/init-databases.sql —
-- the three roles and their passwords MUST match the defaults baked into the
-- shared "rls" profile (libs/spring-boot-starter/application-rls.yml) so a test
-- only has to activate that profile.
--
-- CONNECT is not granted explicitly: PostgreSQL grants CONNECT to PUBLIC by
-- default, and this image does not revoke it, so all three roles can log in.
-- Default privileges are declared FOR ROLE originex_owner because Flyway runs as
-- the owner under the rls profile, so the owner is what creates the tables.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE ROLE originex_owner  WITH LOGIN PASSWORD 'originex_owner_local'  NOSUPERUSER BYPASSRLS CREATEROLE;
CREATE ROLE originex_system WITH LOGIN PASSWORD 'originex_system_local' NOSUPERUSER BYPASSRLS;
CREATE ROLE originex_app    WITH LOGIN PASSWORD 'originex_app_local'    NOSUPERUSER NOBYPASSRLS;

GRANT USAGE, CREATE ON SCHEMA public TO originex_owner;
GRANT USAGE ON SCHEMA public TO originex_system, originex_app;

ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
