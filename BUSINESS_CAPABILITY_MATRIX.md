# BUSINESS_CAPABILITY_MATRIX.md
## Originex — Lending Capability Coverage (source-verified)

**Date:** 9 July 2026
**Basis:** Verified against current source only — domain models, application services, controllers, Kafka consumers, inbound-port interfaces, and Flyway migrations. A capability is judged on whether its own core logic is **implemented and wired end-to-end** (sandbox infra is acceptable). "Wired" was confirmed by cross-referencing domain/use-case methods against their actual callers; several capabilities have domain logic that exists but is invoked by nothing at runtime — those are called out explicitly.

### Legend
- ✅ **Complete** — core function implemented and reachable end-to-end.
- 🟡 **Partially Implemented** — core present but with gaps, unwired paths, or stubs.
- 🔴 **Not Implemented** — essentially absent (at most a label, enum value, or reserved topic).

### Summary
| # | Capability | Status |
|---|---|---|
| 1 | Customer Management | 🟡 |
| 2 | KYC | 🟡 |
| 3 | Loan Application | 🟡 |
| 4 | Credit Bureau | 🟡 |
| 5 | BRE | ✅ |
| 6 | Underwriting (manual) | 🟡 |
| 7 | Offer Management | 🟡 |
| 8 | Disbursement | 🟡 |
| 9 | EMI Schedule | 🟡 |
| 10 | Repayment | 🟡 |
| 11 | Prepayment | 🔴 |
| 12 | Foreclosure | 🟡 |
| 13 | Charges | 🟡 |
| 14 | Penalties | 🔴 |
| 15 | Collections | 🔴 |
| 16 | Settlement | 🔴 |
| 17 | Write-off | 🔴 |
| 18 | Restructure | 🔴 |
| 19 | NPA | 🟡 |
| 20 | Accounting | 🟡 |
| 21 | Notifications | 🟡 |
| 22 | Audit | 🔴 |
| 23 | Security | 🔴 |
| 24 | Multi-tenancy | 🟡 |
| 25 | Reporting | 🔴 |

**Tally:** ✅ 1 · 🟡 15 · 🔴 9. No capability is fully production-ready because of platform-wide gaps (no auth, RLS inert at runtime, all external integrations sandbox) — see individual "Missing components".

---

## 1. Customer Management — 🟡
- **Business objective:** Maintain a single source of truth for customer identity, profile, and bank accounts.
- **Current implementation:** Register / get / update-profile (optimistic-locked) and add-bank-account are implemented and wired. `Customer` aggregate with `Address`, `BankAccount` children.
- **Missing components:** Bank-account **verification** endpoint (`verifyBankAccount` use-case method exists but is wired to no route); pagination on lists; auth.
- **Responsible services:** customer-service.
- **Database tables:** `customers`, `addresses`, `bank_accounts`, `outbox_events`.
- **REST APIs:** `POST /v1/customers`, `GET /v1/customers/{id}`, `PUT /v1/customers/{id}`, `POST /v1/customers/{id}/bank-accounts`.
- **Kafka events:** produces `originex.customer.CustomerRegistered`.
- **Recommended next work:** Wire the bank-account verify endpoint; add pagination.

## 2. KYC — 🟡
- **Business objective:** Verify customer identity (Aadhaar/PAN) to regulatory standard before lending.
- **Current implementation:** KYC submit / complete / live Aadhaar-eKYC wired; `KycRecord` lifecycle (PENDING→VERIFIED, 2-year expiry); Aadhaar tokenized as irreversible `SHA-256(aadhaar + tenantId)`; PAN verified on registration via partner (sandbox).
- **Missing components:** Real DigiLocker/NSDL integrations (sandbox only — LIVE throws); video KYC (`VIDEO_KYC` enum only); KYC re-verification on expiry.
- **Responsible services:** customer-service (orchestrator), partner-integration-service (adapters).
- **Database tables:** `kyc_records`; partner `integration_requests`.
- **REST APIs:** `POST /v1/customers/{id}/kyc`, `POST /v1/customers/{id}/kyc/{recordId}/complete`, `POST /v1/customers/{id}/kyc/aadhaar-ekyc`; partner `POST /v1/partner/aadhaar/verify`, `POST /v1/partner/pan/verify`.
- **Kafka events:** produces `originex.customer.KYCCompleted`.
- **Recommended next work:** Implement LIVE DigiLocker/NSDL; expiry-driven re-KYC.

## 3. Loan Application — 🟡
- **Business objective:** Capture applications and drive them through an auditable lifecycle to an offer.
- **Current implementation:** Full 11-state FSM (`ApplicationStatus`) with `canTransitionTo`; submit → credit-check → auto-decision → offer → accept → disbursement-request all wired; documents; withdraw.
- **Missing components:** No API to resolve a `REFERRED` application (see Underwriting); `DRAFT` state unreachable (submit starts at SUBMITTED); pagination; auth.
- **Responsible services:** los-service.
- **Database tables:** `loan_applications`, `loan_offers`, `application_documents`, `outbox_events`.
- **REST APIs:** `POST /v1/loan-applications`, `GET /{id}`, `POST /{id}/documents`, `POST /{id}/credit-check`, `POST /{id}/offer/accept`, `DELETE /{id}`.
- **Kafka events:** produces `ApplicationSubmitted`, `CreditCheckCompleted`, `ApplicationApproved`, `ApplicationRejected`, `DisbursementRequested`.
- **Recommended next work:** Add the underwriter decision endpoint (unblocks REFERRED).

## 4. Credit Bureau — 🟡
- **Business objective:** Pull applicant credit history for eligibility and pricing.
- **Current implementation:** LOS orchestrates a bureau pull via partner-integration during credit-check; 4 bureau adapters (CIBIL/Experian/Equifax/CRIF) with CB+Retry; every call logged (PII-masked) to `integration_requests`.
- **Missing components:** All 4 bureaus are **sandbox** (LIVE throws `UnsupportedOperationException`); response caching (columns exist, logic unwired); consent-artifact validation.
- **Responsible services:** partner-integration-service (adapters), los-service (caller).
- **Database tables:** `integration_requests`.
- **REST APIs:** `POST /v1/partner/credit-bureau/pull`.
- **Kafka events:** none (synchronous REST).
- **Recommended next work:** Implement at least one LIVE bureau; wire the 24h response cache.

## 5. BRE — ✅
- **Business objective:** Deterministic, per-product eligibility decisioning and offer calculation.
- **Current implementation:** `POST /v1/bre/evaluate` fully wired; `RuleEvaluationEngine` (priority-ordered, HARD→reject / SOFT→refer / all-pass→approve) + `OfferCalculator` (reducing-balance EMI, rate bands, APR) — all BigDecimal/HALF_EVEN; 16 rules seeded across 3 rule sets; best-tested service (`BREDomainTest`, 11 cases).
- **Missing components:** No rule/rule-set **management API** (rules only via SQL/migration — an enhancement, not a gap in the evaluation function); auth. `EMPLOYMENT_TYPE` seed uses a fragile `UPDATE`-after-`INSERT`.
- **Responsible services:** bre-service.
- **Database tables:** `bre_rule_sets`, `bre_rules`.
- **REST APIs:** `POST /v1/bre/evaluate`.
- **Kafka events:** none.
- **Recommended next work:** Add rule CRUD API for runtime configurability.

## 6. Underwriting (manual) — 🟡
- **Business objective:** Approve/reject applications the engine refers to manual review.
- **Current implementation:** Wired. On `REFER_TO_UNDERWRITER` the credit-check now transitions the application to `REFERRED` (via `LoanApplication.refer`); two explicit endpoints action a referred (or in-progress) application: `POST /v1/loan-applications/{id}/approve` (reuses `approveAndGenerateOffer` with manually-supplied offer terms → offer generated) and `POST /v1/loan-applications/{id}/reject` (new `rejectApplication` orchestration → `LoanApplication.reject`). Validity is enforced by the aggregate's `transitionTo` guard, not the controller.
- **Missing components:** Referral queue/listing endpoint; assignment/work-allocation; decision audit trail; role-gating (no auth platform-wide). These keep it 🟡, not ✅.
- **Responsible services:** los-service.
- **Database tables:** `loan_applications`, `loan_offers` (reused; no migration).
- **REST APIs:** `POST /v1/loan-applications/{id}/approve`, `POST /v1/loan-applications/{id}/reject`.
- **Kafka events:** reuses `originex.los.ApplicationApproved` / `ApplicationRejected` (no new event types).
- **Recommended next work:** Add a referral queue/listing endpoint and, once auth exists, role-gate the decision endpoints.

## 7. Offer Management — 🟡
- **Business objective:** Generate, present, expire, and accept loan offers (incl. KFS).
- **Current implementation:** `LoanOffer` VO (amount/rate/tenure/EMI/APR/fee/expiry) generated on approval; `acceptOffer` wired; expiry enforced **lazily** — `acceptOffer` checks `isExpired()` and transitions to `OFFER_EXPIRED`.
- **Missing components:** No offer **re-quote/regeneration**; no scheduler to proactively expire offers (only detected on accept attempt); no KFS document generation.
- **Responsible services:** los-service.
- **Database tables:** `loan_offers`.
- **REST APIs:** `POST /v1/loan-applications/{id}/offer/accept` (generation is internal to credit-check).
- **Kafka events:** `ApplicationApproved` (carries offer); `DisbursementRequested` on accept.
- **Recommended next work:** Add re-quote endpoint and a scheduled offer-expiry sweep.

## 8. Disbursement — 🟡
- **Business objective:** Move sanctioned funds to the borrower and record it.
- **Current implementation:** Choreography is now wired end-to-end (previously it stalled at `CREATED` — `createLoan` published no `LoanDisbursed`; fixed): at offer acceptance LOS resolves the borrower's primary bank account from `customer-service` and carries it in `DisbursementRequested`; LMS `createLoan` generates the schedule, **`initiateDisbursement` (→ `PENDING_DISBURSAL`, `INITIATED` disbursement)**, and publishes `LoanDisbursed` (with beneficiary) → payment `LmsPaymentEventConsumer` selects rail (>₹5L RTGS / ₹2L–₹5L IMPS / <₹2L NEFT), sandbox completes → `DisbursementCompleted` → LMS `confirmDisbursementByPayment` → loan **ACTIVE** → ledger posts DR receivable / CR pool. **Not yet confirmed by a live multi-service run** (env blockers).
- **Missing components:** beneficiary is used **unverified** (penny-drop verify endpoint is unwired dead code); no manual disburse/retry REST (`disburseLoan`/4-arg `confirmDisbursement` remain dead); `DISBURSEMENT_FAILED` recovery path unreachable; all payment rails sandbox; the customer bank-account endpoint returns a full account number over an unauthenticated internal call.
- **Responsible services:** los, lms, payment, ledger.
- **Database tables:** `loans`, `disbursements`, `payment_orders`; ledger `journal_entries`/`postings`.
- **REST APIs:** `POST /v1/payments/disbursements` (payment side).
- **Kafka events:** `DisbursementRequested`, `LoanDisbursed`, `DisbursementInitiated`, `DisbursementCompleted`, `DisbursementConfirmed`.
- **Recommended next work:** Wire an ops disburse/retry endpoint; implement a LIVE rail.

## 9. EMI Schedule — 🟡
- **Business objective:** Generate and maintain the amortization schedule; accrue interest.
- **Current implementation:** `ScheduleGenerator` (reducing-balance, last-installment residue settlement) runs at loan creation; `installments` persisted; tested (`ScheduleGeneratorTest`).
- **Missing components:** Installment status aging (DUE/OVERDUE) is driven by nothing; no reschedule on part-payment. (Interest accrual now runs — see below — but installment-level aging still does not.)
- **Current implementation (accrual):** a daily `InterestAccrualService` scheduler (Actual/365 Fixed, ACTIVE loans only) computes accrual via `InterestAccrualCalculator`, applies `Loan.accrueInterest`, and publishes `originex.lms.InterestAccrued`, which the ledger consumer posts. Idempotent per-loan-per-day via `last_accrual_date`; disable-able via `originex.lms.accrual.enabled`. Still 🟡 (schedule generation, accrual now wired; but installment aging, penal interest, and NPA-suspense accrual remain).
- **Responsible services:** lms-service (schedule + accrual), ledger-service (posts accruals).
- **Database tables:** `installments`, `loans`.
- **REST APIs:** `GET /v1/loans/{id}/repayment-schedule`.
- **Kafka events:** `InterestAccrued` (producer + consumer now both wired).
- **Recommended next work:** Drive installment status aging (DUE/OVERDUE); reschedule on part-payment.

## 10. Repayment — 🟡
- **Business objective:** Accept repayments and allocate them correctly against the loan.
- **Current implementation:** `allocateRepayment` waterfall wired two ways — manual (`POST /v1/loans/{id}/repayments`) and event-driven (`PaymentReceived` → `allocateRepaymentFromPayment`); reduces outstanding; publishes `RepaymentAllocated`; ledger auto-posts. Repayment now also **settles the amortization schedule** oldest-installment-first (interest-due then principal-due per installment, marking `PAID`/`PARTIALLY_PAID`) and advances `next_due_date`, which the DPD job reads.
- **Missing components:** Actual waterfall is **Charges → Interest → Principal** (no penal step — see Penalties); no arrears tracking; installment interest settlement uses scheduled interest, which can diverge from Actual/365 accrued interest (the documented rate/accrual reconciliation debt).
- **Responsible services:** lms-service, payment-service, ledger-service.
- **Database tables:** `loans`, `installments`, `payment_orders`; ledger `postings`.
- **REST APIs:** `POST /v1/loans/{id}/repayments`.
- **Kafka events:** consumes `PaymentReceived`; produces `RepaymentAllocated`.
- **Recommended next work:** Drive installment aging; reconcile allocation to installments.

## 11. Prepayment — 🔴
- **Business objective:** Allow partial/full early repayment with schedule regeneration and fee.
- **Current implementation:** None. `PREPAYMENT_FEE` is a `LoanCharge` type **string label** and `PREPAYMENT_PROCESSED` is a notification trigger — no prepayment method, API, or schedule-regeneration logic exists.
- **Missing components:** Entire capability (part-prepay, schedule regen, prepayment fee calc).
- **Responsible services:** lms-service (would host it).
- **Database tables:** `loans`, `installments`, `loan_charges` (reused).
- **REST APIs:** none.
- **Kafka events:** none.
- **Recommended next work:** Implement part-prepayment with `ScheduleGenerator` regeneration and fee.

## 12. Foreclosure — 🟡
- **Business objective:** Compute and execute full early closure of a loan.
- **Current implementation:** `POST /v1/loans/{id}/foreclosure-quote` computes principal+interest+charges; `Loan.foreclose(amount)` domain method exists (transitions to FORECLOSED).
- **Missing components:** **No execute-foreclosure path** — `foreclose()` is called by nothing (no endpoint/consumer); no foreclosure-fee logic; no ledger closure posting.
- **Responsible services:** lms-service, ledger-service.
- **Database tables:** `loans`.
- **REST APIs:** `POST /v1/loans/{id}/foreclosure-quote` (quote only).
- **Kafka events:** none for foreclosure.
- **Recommended next work:** Add `POST /{id}/foreclosure` that calls `foreclose()` and posts closure to the ledger.

## 13. Charges — 🟡
- **Business objective:** Levy and track fees (processing, late, bounce, etc.) on a loan.
- **Current implementation:** `LoanCharge` model + `loan_charges` table + `Loan.levyCharge()` domain method; charges participate in the repayment waterfall (`outstandingCharges` allocated first).
- **Missing components:** **`levyCharge()` is called by nothing** — no service/controller/consumer levies a charge at runtime, so `outstandingCharges` is always zero in practice; no charge API; no charge-configuration.
- **Responsible services:** lms-service.
- **Database tables:** `loan_charges`, `loans`.
- **REST APIs:** none (needed: levy/waive charge).
- **Kafka events:** none.
- **Recommended next work:** Wire charge levying (API + event-driven, e.g. on NACH bounce → BOUNCE_CHARGE) and waiver.

## 14. Penalties — 🔴
- **Business objective:** Apply penal interest / late-payment penalties on overdue loans.
- **Current implementation:** None functional. The `Loan` Javadoc claims a "Charges → Penal → Interest → Principal" waterfall, but there is **no `outstandingPenal` field** and no penal-interest calculation; `LATE_FEE` is only a charge-type string.
- **Missing components:** Penal-interest accrual, overdue detection driving it, and its waterfall bucket (the doc comment is misleading — no penal step exists in code).
- **Responsible services:** lms-service.
- **Database tables:** `loans`, `loan_charges` (would extend).
- **REST APIs:** none.
- **Kafka events:** none.
- **Recommended next work:** Add a penal-interest field + accrual driven by DPD; correct the stale waterfall Javadoc.

## 15. Collections — 🔴
- **Business objective:** Manage delinquent accounts (dunning, notices, agent workflows).
- **Current implementation:** None. A Strimzi topic `originex.collections.cases.events` is reserved in `infra/kafka/topics.yaml`, and `COLLECTION_NOTICE_1/2` + `SETTLEMENT_OFFER` notification triggers exist — but **no collections service, table, consumer, or logic**.
- **Missing components:** Entire service (delinquency cases, dunning workflow, agent allocation).
- **Responsible services:** none (would be a new `collections-service`).
- **Database tables:** none.
- **REST APIs:** none.
- **Kafka events:** `originex.collections.cases.events` (reserved, no producer/consumer).
- **Recommended next work:** Scaffold `collections-service` consuming NPA/DPD signals from LMS.

## 16. Settlement — 🔴
- **Business objective:** Record negotiated one-time settlements and close the account.
- **Current implementation:** `SETTLED` is a valid `LoanStatus` and a reachable transition target, but **no domain method, API, or consumer reaches it**; `SETTLEMENT_OFFER` is a notification-trigger label only.
- **Missing components:** Settlement recording, waiver accounting, closure.
- **Responsible services:** lms-service, ledger-service.
- **Database tables:** `loans`.
- **REST APIs:** none.
- **Kafka events:** none.
- **Recommended next work:** Add a settlement domain method + endpoint + ledger waiver posting.

## 17. Write-off — 🔴
- **Business objective:** Write off unrecoverable loans and post the loss.
- **Current implementation:** `WRITTEN_OFF` is a valid `LoanStatus` (reachable only from NPA in the guard), but **no domain method, API, or consumer reaches it**.
- **Missing components:** Write-off action, provisioning/loss ledger posting, approval workflow.
- **Responsible services:** lms-service, ledger-service.
- **Database tables:** `loans`.
- **REST APIs:** none.
- **Kafka events:** none.
- **Recommended next work:** Add write-off action + ledger loss entry, gated by role.

## 18. Restructure — 🔴
- **Business objective:** Restructure/reschedule a loan (revised terms/schedule).
- **Current implementation:** `RESTRUCTURED` state and `ACTIVE↔RESTRUCTURED` transitions exist in the guard, but **no restructuring logic, schedule regeneration, or API** — the state is unreachable in practice.
- **Missing components:** Restructure workflow, `ScheduleGenerator`-based schedule regen, revised-terms capture.
- **Responsible services:** lms-service.
- **Database tables:** `loans`, `installments`.
- **REST APIs:** none.
- **Kafka events:** none.
- **Recommended next work:** Implement restructure with schedule regeneration.

## 19. NPA — 🟡
- **Business objective:** Classify assets per RBI norms (90+ DPD → NPA, escalating to DOUBTFUL/LOSS).
- **Current implementation:** A daily `DpdAgingService` scheduler now computes DPD as calendar days since the oldest unpaid installment's due date (Asia/Kolkata) and calls `Loan.updateDpd()`, which sets NPA at 90+ DPD, DOUBTFUL at 365+, LOSS at 730+. Idempotent (absolute recompute), keyset-paginated, `@Version`-safe, disable-able via `originex.lms.dpd.enabled`; unit-tested (`LoanTest`) and covered end-to-end by `LoanLifecycleIntegrationTest`.
- **Missing components:** v1 **persists classification only** — no provisioning ledger postings, no NPA interest-suspense reclassification, no `AssetClassificationChanged` event, and no automatic NPA→ACTIVE upgrade on cure (DPD resets to 0 but status stays NPA). The NPA transition still bypasses the FSM guard (direct field assignment).
- **Responsible services:** lms-service.
- **Database tables:** `loans` (`dpd`, `max_dpd`, `asset_classification`, `next_due_date`).
- **REST APIs:** none.
- **Kafka events:** none in v1 (a classification event is a deferred enhancement).
- **Recommended next work:** Provisioning postings + NPA interest-suspense; emit a classification event for notifications/collections; NPA→ACTIVE cure/upgrade rules.

## 20. Accounting — 🟡
- **Business objective:** Immutable double-entry ledger as financial source of truth.
- **Current implementation:** `JournalEntry` enforces `SUM(debits)=SUM(credits)`, ≥2 postings, reversal-only; ledger auto-posts on `LoanDisbursed` and `RepaymentAllocated`; event-sourced `ledger_events` + read models; 3 GL accounts seeded (Phase 0).
- **Missing components:** chart-of-accounts is **single-tenant** (3 hardcoded GL UUIDs seeded for the default tenant only — other tenants fail "Account not found"); `ledger_events` partitions exist only through Aug 2026. (`InterestAccrued` postings now occur — lms publishes the event and the ledger consumer posts DR Interest Receivable / CR Interest Income.)
- **Responsible services:** ledger-service.
- **Database tables:** `ledger_events` (partitioned), `account_snapshots`, `journal_entries`, `postings`, `outbox_events`, `inbox_events`.
- **REST APIs:** `POST /v1/ledger/accounts`, `GET /accounts/{id}`, `POST /journal-entries`, `POST /journal-entries/{id}/reverse`.
- **Kafka events:** consumes `LoanDisbursed`/`RepaymentAllocated`/`InterestAccrued`; produces `JournalEntryPosted`.
- **Recommended next work:** Per-tenant chart-of-accounts service; partition automation.

## 21. Notifications — 🟡
- **Business objective:** RBI-mandated multi-channel borrower communication on lifecycle events.
- **Current implementation:** One consumer on 4 domain topics; `EventToNotificationMapper` maps ~14 event types to `NotificationTrigger` (35 triggers); 4 channel adapters (SMS/Email/WhatsApp/Push); 12 seeded templates; idempotent via `existsBySourceEventId`; 10-min retry scheduler.
- **Missing components:** **Recipient data** — phone/email/name are read straight from the event payload (most events don't carry them); no customer-service lookup; all channels **sandbox** (LIVE throws); language hardcoded `"en"`.
- **Responsible services:** notification-service.
- **Database tables:** `notification_requests`, `channel_dispatches`, `notification_templates`.
- **REST APIs:** none (event-driven).
- **Kafka events:** consumes `originex.customer.*` / `los.*` / `lms.*` / `payments.*` events.
- **Recommended next work:** Add customer-profile lookup for recipient details; implement a LIVE channel.

## 22. Audit — 🔴
- **Business objective:** Immutable, queryable audit trail of all actions (RBI ~8-year retention).
- **Current implementation:** None. No audit service, no audit table, and the reserved `originex.platform.audit-events` topic has **no producer and no consumer**. (partner-integration's `integration_requests` logs its own vendor calls, but that is not a platform audit trail.)
- **Missing components:** Entire capability — audit event emission, immutable sink (e.g. OpenSearch/S3), query API, retention.
- **Responsible services:** none (would be a new `audit-service`).
- **Database tables:** none.
- **REST APIs:** none.
- **Kafka events:** `originex.platform.audit-events` (reserved, unused).
- **Recommended next work:** Emit audit events from state-changing operations; build an append-only audit sink.

## 23. Security — 🔴
- **Business objective:** Authenticate and authorize callers; protect PII; secure inter-service calls.
- **Current implementation:** Only tenant-context propagation (`X-Tenant-Id` → `TenantContextHolder`) and PII masking in logs. **No authentication, no authorization/RBAC, no gateway.** Aadhaar tokenization is real; PAN "encryption" is a stub (`ENC:`-prefix). Payment `POST /callbacks` is unauthenticated/unverified.
- **Missing components:** IAM/JWT/OAuth2, RBAC, API gateway, real PAN/account encryption (KMS), mTLS, webhook signature verification.
- **Responsible services:** platform-wide (would need an IAM service + starter filter).
- **Database tables:** none today.
- **REST APIs:** none today.
- **Kafka events:** none.
- **Recommended next work:** Stand up authentication (JWT validation in the shared starter) + real PAN encryption — the top two production blockers.

## 24. Multi-tenancy — 🟡
- **Business objective:** Strict per-tenant data isolation across all services.
- **Current implementation:** `X-Tenant-Id` header resolved into `TenantContextHolder` + MDC (enforce=true everywhere except notification-service); database-per-service; RLS policies (`USING tenant_id = current_setting('app.tenant_id')`) defined on tenant tables in every migration.
- **Missing components:** **RLS is inert** — nothing sets `app.tenant_id` on the DB session (no interceptor/`SET LOCAL` anywhere), so policies never activate; ledger's hardcoded GL accounts are single-tenant; no tenant onboarding/config service.
- **Responsible services:** platform-wide (`libs/spring-boot-starter`).
- **Database tables:** RLS on customer/los/lms/ledger/payment/bre/notification/template tenant tables.
- **REST APIs:** none (cross-cutting).
- **Kafka events:** `tenant_id` propagated in event headers.
- **Recommended next work:** Add a Hibernate `StatementInspector`/connection interceptor that issues `SET LOCAL app.tenant_id` per transaction from `TenantContextHolder`.

## 25. Reporting — 🔴
- **Business objective:** MIS, regulatory reports, and portfolio analytics.
- **Current implementation:** None. No reporting service, tables, endpoints, or aggregation jobs (grep hits for "report" are all credit-bureau report references, unrelated).
- **Missing components:** Entire capability — reporting store/read models, report generation, regulatory formats.
- **Responsible services:** none (would be a new `reporting-service`).
- **Database tables:** none.
- **REST APIs:** none.
- **Kafka events:** none (would consume `ledger.journal-entries.events` and domain events).
- **Recommended next work:** Build read models off ledger + domain event streams; start with a portfolio/DPD MIS.

---

## Cross-cutting themes (verified)
1. **A recurring pattern: domain logic exists but is never invoked.** `levyCharge`, `foreclose`, `updateDpd`, `disburseLoan`, and LOS `recordCreditResult` are implemented on aggregates/use-cases but have **no runtime caller** — so Charges, Foreclosure execution, NPA, and manual Disbursement remain "coded but unreachable." (Manual Underwriting — `refer`/`approveAndGenerateOffer` — and interest accrual — `accrueInterest` — have since been wired.) Wiring these is disproportionately high-value versus writing new logic.
2. **The core auto-decisioned journey works** (Customer → KYC → Application → Bureau → BRE → Offer → Disbursement → Ledger → Repayment → Notification), all in sandbox. The referral exception path is now actionable (manual approve/reject); the remaining exception paths (delinquency, restructure, settlement, write-off) are still 🔴.
3. **No capability is production-complete** because of the three platform blockers: no authentication, RLS inert at runtime, and 100%-sandbox external integrations.

*Verified from source on 9 July 2026. "Wired / not wired" determinations were made by cross-referencing each domain and use-case method against its actual callers in the application and adapter layers.*
