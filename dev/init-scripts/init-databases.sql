-- ═══════════════════════════════════════════════════════════════════════════
-- Initialize databases for each bounded context (local development only)
-- ═══════════════════════════════════════════════════════════════════════════

-- LOS Database
CREATE DATABASE originex_los;
GRANT ALL PRIVILEGES ON DATABASE originex_los TO originex;

-- LMS Database
CREATE DATABASE originex_lms;
GRANT ALL PRIVILEGES ON DATABASE originex_lms TO originex;

-- Ledger Database
CREATE DATABASE originex_ledger;
GRANT ALL PRIVILEGES ON DATABASE originex_ledger TO originex;

-- Payment Database
CREATE DATABASE originex_payment;
GRANT ALL PRIVILEGES ON DATABASE originex_payment TO originex;

-- Customer Database
CREATE DATABASE originex_customer;
GRANT ALL PRIVILEGES ON DATABASE originex_customer TO originex;

-- Collections Database
CREATE DATABASE originex_collections;
GRANT ALL PRIVILEGES ON DATABASE originex_collections TO originex;

-- IAM Database
CREATE DATABASE originex_iam;
GRANT ALL PRIVILEGES ON DATABASE originex_iam TO originex;

-- Template Service (for dev/testing)
CREATE DATABASE originex_template;
GRANT ALL PRIVILEGES ON DATABASE originex_template TO originex;
