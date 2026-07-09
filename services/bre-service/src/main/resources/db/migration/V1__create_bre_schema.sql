-- ═══════════════════════════════════════════════════════════════
-- V1__create_bre_schema.sql
-- BRE Service — rule sets and rules
-- ═══════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─── Rule Sets ───
CREATE TABLE bre_rule_sets (
    rule_set_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    rule_set_code   VARCHAR(80) NOT NULL,
    product_code    VARCHAR(40),       -- NULL = applies to all products
    employment_type VARCHAR(30),       -- NULL = applies to all employment types
    description     TEXT,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, rule_set_code)
);

CREATE INDEX idx_bre_rule_sets_lookup ON bre_rule_sets(tenant_id, product_code, employment_type)
    WHERE active = TRUE;

-- ─── Rules ───
CREATE TABLE bre_rules (
    rule_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL,
    rule_set_id         UUID NOT NULL REFERENCES bre_rule_sets(rule_set_id),
    rule_code           VARCHAR(80) NOT NULL,
    description         TEXT,
    rule_type           VARCHAR(10) NOT NULL,   -- HARD, SOFT, ADVISORY
    category            VARCHAR(20) NOT NULL,   -- CREDIT, INCOME, AGE, PRODUCT, EMPLOYMENT, DELINQUENCY, LOAN_AMOUNT
    fact_key            VARCHAR(50) NOT NULL,
    operator            VARCHAR(10) NOT NULL,   -- GTE, GT, LTE, LT, EQ, NEQ, BETWEEN, NOT_NULL, IN, NOT_IN
    threshold_value     VARCHAR(100),
    threshold_value_max VARCHAR(100),
    allowed_values      VARCHAR(500),           -- Comma-separated for IN/NOT_IN
    failure_message     TEXT,
    priority            INTEGER NOT NULL DEFAULT 100,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(rule_set_id, rule_code)
);

CREATE INDEX idx_bre_rules_ruleset ON bre_rules(rule_set_id, active, priority);

-- ═══════════════════════════════════════════════════════════════
-- Seed: DEFAULT rule set (applies to all tenants as fallback)
-- Uses sentinel tenant_id all-zeros-1
-- ═══════════════════════════════════════════════════════════════

-- Default rule set
INSERT INTO bre_rule_sets (rule_set_id, tenant_id, rule_set_code, product_code, employment_type, description)
VALUES
  ('a0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
   'DEFAULT', NULL, NULL, 'Default rule set — applies to all products and employment types'),
  ('a0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
   'PERSONAL_LOAN_SALARIED', 'PERSONAL_LOAN', 'SALARIED', 'Personal loan rules for salaried borrowers'),
  ('a0000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
   'PERSONAL_LOAN_SELF_EMPLOYED', 'PERSONAL_LOAN', 'SELF_EMPLOYED', 'Personal loan rules for self-employed borrowers');

-- ─── DEFAULT rule set rules ───

INSERT INTO bre_rules (rule_id, tenant_id, rule_set_id, rule_code, description, rule_type, category,
                        fact_key, operator, threshold_value, failure_message, priority) VALUES

-- Hard rules: immediate rejection
('b0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000001', 'MIN_CREDIT_SCORE', 'Minimum credit score',
 'HARD', 'CREDIT', 'credit_score', 'GTE', '600',
 'Credit score below minimum threshold (600). Application rejected.', 10),

('b0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000001', 'NO_WRITE_OFF', 'No write-off in credit history',
 'HARD', 'DELINQUENCY', 'has_write_off', 'EQ', 'false',
 'Active write-off found in credit bureau. Application rejected.', 20),

('b0000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000001', 'MIN_AGE', 'Minimum applicant age 21',
 'HARD', 'AGE', 'applicant_age', 'GTE', '21',
 'Applicant must be at least 21 years old.', 30),

('b0000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000001', 'MAX_AGE_AT_MATURITY', 'Age at loan maturity <= 65',
 'HARD', 'AGE', 'age_at_maturity', 'LTE', '65',
 'Applicant age at loan maturity exceeds 65 years.', 40),

('b0000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000001', 'MIN_INCOME', 'Minimum monthly income INR 15,000',
 'HARD', 'INCOME', 'monthly_income', 'GTE', '15000',
 'Monthly income below minimum threshold (INR 15,000).', 50),

-- Soft rules: refer to underwriter
('b0000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000001', 'MAX_FOIR', 'Maximum FOIR 50%',
 'SOFT', 'INCOME', 'foir', 'LTE', '0.50',
 'Fixed Obligation to Income Ratio exceeds 50%. Refer for underwriter review.', 60),

('b0000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000001', 'MAX_ENQUIRIES', 'Max credit enquiries in 6 months: 4',
 'SOFT', 'CREDIT', 'enquiries_last_6_months', 'LTE', '4',
 'More than 4 credit enquiries in last 6 months. Refer for underwriter review.', 70),

('b0000000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000001', 'NO_SETTLEMENT', 'No settlement in credit history',
 'SOFT', 'DELINQUENCY', 'has_settlement', 'EQ', 'false',
 'Settlement found in credit history. Refer for underwriter review.', 80),

-- Advisory rules: warnings only
('b0000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000001', 'MAX_ACTIVE_LOANS', 'Max active loans: 3',
 'ADVISORY', 'CREDIT', 'active_loans_count', 'LTE', '3',
 'Applicant has more than 3 active loans.', 90);

-- ─── PERSONAL_LOAN_SALARIED — stricter income, relaxed age ───

INSERT INTO bre_rules (rule_id, tenant_id, rule_set_id, rule_code, description, rule_type, category,
                        fact_key, operator, threshold_value, failure_message, priority) VALUES

('c0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000002', 'MIN_CREDIT_SCORE', 'Min credit score for salaried PL',
 'HARD', 'CREDIT', 'credit_score', 'GTE', '650',
 'Credit score below 650 for personal loan (salaried). Application rejected.', 10),

('c0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000002', 'NO_WRITE_OFF', 'No write-off',
 'HARD', 'DELINQUENCY', 'has_write_off', 'EQ', 'false',
 'Active write-off found. Application rejected.', 20),

('c0000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000002', 'MIN_AGE', 'Min age 21',
 'HARD', 'AGE', 'applicant_age', 'GTE', '21',
 'Applicant must be at least 21 years old.', 30),

('c0000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000002', 'MAX_AGE_AT_MATURITY', 'Age at maturity <= 60',
 'HARD', 'AGE', 'age_at_maturity', 'LTE', '60',
 'Applicant age at loan maturity exceeds 60 years (salaried product limit).', 40),

('c0000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000002', 'MIN_INCOME_SALARIED', 'Min income INR 20,000 for PL',
 'HARD', 'INCOME', 'monthly_income', 'GTE', '20000',
 'Monthly income below INR 20,000 for personal loan.', 50),

('c0000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000002', 'MAX_FOIR', 'Max FOIR 45% for PL',
 'SOFT', 'INCOME', 'foir', 'LTE', '0.45',
 'FOIR exceeds 45% for personal loan. Refer to underwriter.', 60),

('c0000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000001',
 'a0000000-0000-0000-0000-000000000002', 'EMPLOYMENT_TYPE', 'Must be SALARIED',
 'HARD', 'EMPLOYMENT', 'employment_type', 'IN', NULL,
 'Employment type must be SALARIED for this product.', 5);

UPDATE bre_rules
SET allowed_values = 'SALARIED,GOVERNMENT,PSU'
WHERE rule_code = 'EMPLOYMENT_TYPE'
  AND rule_set_id = 'a0000000-0000-0000-0000-000000000002';

-- ─── Row-Level Security ───
ALTER TABLE bre_rule_sets ENABLE ROW LEVEL SECURITY;
ALTER TABLE bre_rule_sets FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_rule_sets ON bre_rule_sets
    USING (tenant_id = current_setting('app.tenant_id')::uuid
        OR tenant_id = '00000000-0000-0000-0000-000000000001');

ALTER TABLE bre_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE bre_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_rules ON bre_rules
    USING (tenant_id = current_setting('app.tenant_id')::uuid
        OR tenant_id = '00000000-0000-0000-0000-000000000001');
