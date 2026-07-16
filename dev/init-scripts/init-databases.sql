-- ═══════════════════════════════════════════════════════════════════════════
-- Initialize databases and RLS roles for each bounded context (local dev only)
--
-- This script runs once, as the bootstrap superuser (POSTGRES_USER=originex),
-- via docker-entrypoint-initdb.d. It provisions:
--   1. The three RLS roles (owner / system / app) — see dev/RLS_DESIGN.md §5.
--   2. One database per bounded context.
--   3. Per-database CONNECT + schema privileges and default privileges so the
--      app and system roles automatically receive DML on objects Flyway creates.
--   4. The pgcrypto extension in each database. Every service's V1 migration runs
--      CREATE EXTENSION IF NOT EXISTS "pgcrypto", which works today only because
--      Flyway connects as the bootstrap superuser. Once a service enables RLS,
--      the rls profile points Flyway at originex_owner — NOSUPERUSER and without
--      CREATE on the database — which cannot create an extension (SQLSTATE 42501),
--      so the service would fail to boot. Creating it here (as the superuser, at
--      cluster init) makes that migration statement a harmless no-op. Idempotent,
--      and required before any service can be switched to the rls profile. The
--      test harness does the same — see libs/test-support rls/test-roles.sql.
--      Production provisioning (DBA/IaC) must reproduce this too.
--
-- The database list must match spring.datasource.url in each service's
-- application.yml exactly. Verified against the actual database name each of the
-- 9 services in the root pom.xml <modules> currently connects to — do not add a
-- database for a service that isn't a real Maven module yet (e.g.
-- collections-service, iam-service are planned but don't exist), and do not
-- remove one for a service that has a real Flyway migration and
-- ddl-auto: validate configured (template-service included — it has both).
--
-- NOTE: this is the LOCAL DEV provisioning only. Production role/credential
-- provisioning is owned by the DBA/IaC layer; the attributes below (especially
-- BYPASSRLS on owner and system, and NOBYPASSRLS on app) are the security
-- contract that provisioning must reproduce. Phase 0 ships dark: the roles exist
-- but no service connects as them until originex.rls.enabled=true is set during
-- the per-service rollout, so creating them now changes no running behavior.
-- ═══════════════════════════════════════════════════════════════════════════

-- ─── RLS roles (cluster-wide; see dev/RLS_DESIGN.md §5) ─────────────────────
--
-- owner  : owns schema objects; runs Flyway (DDL *and* seed DML). Because the
--          policy tables use FORCE ROW LEVEL SECURITY, even the table owner is
--          subject to RLS, so the migration role must bypass it to seed
--          reference data (BRE rules, GL accounts, notification templates).
-- system : cross-tenant background workers (accrual, DPD aging, retry pollers).
--          Routed to via SystemContextHolder; must see all tenants → BYPASSRLS.
-- app    : request/consumer runtime path. Subject to RLS (NOBYPASSRLS) — this
--          is the role tenant isolation actually depends on.
CREATE ROLE originex_owner  WITH LOGIN PASSWORD 'originex_owner_local'  NOSUPERUSER BYPASSRLS   CREATEROLE;
CREATE ROLE originex_system WITH LOGIN PASSWORD 'originex_system_local' NOSUPERUSER BYPASSRLS;
CREATE ROLE originex_app    WITH LOGIN PASSWORD 'originex_app_local'    NOSUPERUSER NOBYPASSRLS;

-- ─── Per-database provisioning ─────────────────────────────────────────────
-- grant_context(db): a repeatable block applied after each CREATE DATABASE. It
-- must run *inside* the target database (\connect) because schema-level grants
-- and default privileges are database-local. Default privileges are declared
-- for both the current Flyway login (originex, used in Phase 0) and the future
-- owner login (originex_owner, used once rollout points Flyway at it) so the
-- app/system roles get DML on new objects regardless of which role creates them.

-- Customer Database
CREATE DATABASE originex_customer;
GRANT ALL PRIVILEGES ON DATABASE originex_customer TO originex;
\connect originex_customer
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT CONNECT ON DATABASE originex_customer TO originex_owner, originex_system, originex_app;
GRANT USAGE, CREATE ON SCHEMA public TO originex_owner;
GRANT USAGE ON SCHEMA public TO originex_system, originex_app;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
\connect originex_dev

-- LOS Database
CREATE DATABASE originex_los;
GRANT ALL PRIVILEGES ON DATABASE originex_los TO originex;
\connect originex_los
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT CONNECT ON DATABASE originex_los TO originex_owner, originex_system, originex_app;
GRANT USAGE, CREATE ON SCHEMA public TO originex_owner;
GRANT USAGE ON SCHEMA public TO originex_system, originex_app;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
\connect originex_dev

-- LMS Database
CREATE DATABASE originex_lms;
GRANT ALL PRIVILEGES ON DATABASE originex_lms TO originex;
\connect originex_lms
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT CONNECT ON DATABASE originex_lms TO originex_owner, originex_system, originex_app;
GRANT USAGE, CREATE ON SCHEMA public TO originex_owner;
GRANT USAGE ON SCHEMA public TO originex_system, originex_app;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
\connect originex_dev

-- Ledger Database
CREATE DATABASE originex_ledger;
GRANT ALL PRIVILEGES ON DATABASE originex_ledger TO originex;
\connect originex_ledger
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT CONNECT ON DATABASE originex_ledger TO originex_owner, originex_system, originex_app;
GRANT USAGE, CREATE ON SCHEMA public TO originex_owner;
GRANT USAGE ON SCHEMA public TO originex_system, originex_app;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
\connect originex_dev

-- Partner Integration Database
CREATE DATABASE originex_partner;
GRANT ALL PRIVILEGES ON DATABASE originex_partner TO originex;
\connect originex_partner
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT CONNECT ON DATABASE originex_partner TO originex_owner, originex_system, originex_app;
GRANT USAGE, CREATE ON SCHEMA public TO originex_owner;
GRANT USAGE ON SCHEMA public TO originex_system, originex_app;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
\connect originex_dev

-- Payment Database
CREATE DATABASE originex_payment;
GRANT ALL PRIVILEGES ON DATABASE originex_payment TO originex;
\connect originex_payment
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT CONNECT ON DATABASE originex_payment TO originex_owner, originex_system, originex_app;
GRANT USAGE, CREATE ON SCHEMA public TO originex_owner;
GRANT USAGE ON SCHEMA public TO originex_system, originex_app;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
\connect originex_dev

-- Notification Database
CREATE DATABASE originex_notification;
GRANT ALL PRIVILEGES ON DATABASE originex_notification TO originex;
\connect originex_notification
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT CONNECT ON DATABASE originex_notification TO originex_owner, originex_system, originex_app;
GRANT USAGE, CREATE ON SCHEMA public TO originex_owner;
GRANT USAGE ON SCHEMA public TO originex_system, originex_app;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
\connect originex_dev

-- BRE (Business Rules Engine) Database
CREATE DATABASE originex_bre;
GRANT ALL PRIVILEGES ON DATABASE originex_bre TO originex;
\connect originex_bre
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT CONNECT ON DATABASE originex_bre TO originex_owner, originex_system, originex_app;
GRANT USAGE, CREATE ON SCHEMA public TO originex_owner;
GRANT USAGE ON SCHEMA public TO originex_system, originex_app;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
\connect originex_dev

-- Template Service Database (scaffold/reference service — still has its own
-- Flyway migration and ddl-auto: validate, so it still needs a database)
CREATE DATABASE originex_template;
GRANT ALL PRIVILEGES ON DATABASE originex_template TO originex;
\connect originex_template
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
GRANT CONNECT ON DATABASE originex_template TO originex_owner, originex_system, originex_app;
GRANT USAGE, CREATE ON SCHEMA public TO originex_owner;
GRANT USAGE ON SCHEMA public TO originex_system, originex_app;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex       IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO originex_app, originex_system;
ALTER DEFAULT PRIVILEGES FOR ROLE originex_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO originex_app, originex_system;
\connect originex_dev
