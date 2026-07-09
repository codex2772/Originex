-- ═══════════════════════════════════════════════════════════════════════════
-- Initialize databases for each bounded context (local development only)
--
-- This list must match spring.datasource.url in each service's
-- application.yml exactly. Verified against the actual database name each
-- of the 9 services in the root pom.xml <modules> currently connects to —
-- do not add a database here for a service that isn't a real Maven module
-- yet (e.g. collections-service, iam-service are planned but don't exist),
-- and do not remove one for a service that has a real Flyway migration and
-- ddl-auto: validate configured (template-service included — it has both).
-- ═══════════════════════════════════════════════════════════════════════════

-- Customer Database
CREATE DATABASE originex_customer;
GRANT ALL PRIVILEGES ON DATABASE originex_customer TO originex;

-- LOS Database
CREATE DATABASE originex_los;
GRANT ALL PRIVILEGES ON DATABASE originex_los TO originex;

-- LMS Database
CREATE DATABASE originex_lms;
GRANT ALL PRIVILEGES ON DATABASE originex_lms TO originex;

-- Ledger Database
CREATE DATABASE originex_ledger;
GRANT ALL PRIVILEGES ON DATABASE originex_ledger TO originex;

-- Partner Integration Database
CREATE DATABASE originex_partner;
GRANT ALL PRIVILEGES ON DATABASE originex_partner TO originex;

-- Payment Database
CREATE DATABASE originex_payment;
GRANT ALL PRIVILEGES ON DATABASE originex_payment TO originex;

-- Notification Database
CREATE DATABASE originex_notification;
GRANT ALL PRIVILEGES ON DATABASE originex_notification TO originex;

-- BRE (Business Rules Engine) Database
CREATE DATABASE originex_bre;
GRANT ALL PRIVILEGES ON DATABASE originex_bre TO originex;

-- Template Service Database (scaffold/reference service — still has its own
-- Flyway migration and ddl-auto: validate, so it still needs a database)
CREATE DATABASE originex_template;
GRANT ALL PRIVILEGES ON DATABASE originex_template TO originex;
