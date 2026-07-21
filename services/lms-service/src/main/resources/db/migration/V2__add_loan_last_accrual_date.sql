-- ═══════════════════════════════════════════════════════════════
-- V2__add_loan_last_accrual_date.sql
-- Interest Accrual v1 — see InterestAccrualCalculator / Loan.accrueInterest.
--
-- Adds the per-loan "interest accrued through" marker that drives daily
-- accrual and guarantees idempotency (a loan is accrued at most once per
-- day: the accrual eligibility query selects only rows where
-- last_accrual_date < today). Seeded to the first-disbursement date when a
-- loan is activated (see Loan.confirmDisbursement); NULL for loans activated
-- before this feature — those establish a baseline (accrue 0) on first
-- encounter rather than back-dating, so no retroactive catch-up is posted.
--
-- Day-count convention is Actual/365 Fixed (v1); NPA interest-suspense
-- treatment is deferred (documented on Loan.accrueInterest).
--
-- V1 applies cleanly, so this is an additive ALTER in a new migration
-- (historical migrations are never modified).
-- ═══════════════════════════════════════════════════════════════

ALTER TABLE loans
    ADD COLUMN last_accrual_date DATE;

-- Supports the accrual eligibility query
-- (status = 'ACTIVE' AND (last_accrual_date IS NULL OR last_accrual_date < :asOf)
--  ORDER BY loan_id) — partial index keeps it small (only active loans).
CREATE INDEX idx_loans_accrual ON loans (last_accrual_date, loan_id)
    WHERE status = 'ACTIVE';
