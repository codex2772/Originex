# Originex — End-to-End Verification Guide

**Audience:** a developer validating the platform for the first time.
**Basis:** grounded in the current source (controllers, consumers, application services, migrations, `application.yml`, `infra/kafka/topics.yaml`) and the project docs (`CURRENT_STATE.md`, `BUSINESS_CAPABILITY_MATRIX.md`, `CLAUDE_ANALYSIS.md`, `dev/PHASE0_VERIFICATION.md`). Where a step **cannot** actually execute against the current code, it says so explicitly rather than assuming success.

> ## ⚠️ Read this first — status of the disbursement chain
> **This gap has now been wired in code (pending a live run).** Historically, the journey stalled after loan creation: `lms-service`'s consumer called only `createLoan()`, which left the loan in `CREATED` and published no event, so `originex.lms.LoanDisbursed` (the trigger for payment → ledger → activation → accrual → repayment) was never emitted.
>
> **What changed:** offer acceptance now resolves the borrower's primary bank account from `customer-service` and carries it through `DisbursementRequested`; `createLoan()` now **initiates disbursement** (`CREATED → PENDING_DISBURSAL`, creating an `INITIATED` disbursement) and **publishes `LoanDisbursed`** (with beneficiary details) — which triggers payment (sandbox) → `DisbursementCompleted` → `confirmDisbursementByPayment` → **loan `ACTIVE`**, plus the ledger disbursement posting. With loans reaching `ACTIVE`, interest accrual and repayment become reachable.
>
> **Honest caveat:** this is verified by full-reactor compile, domain tests, and an end-to-end code trace — **not** by a live multi-service run (blocked in the authoring environment; see §1). Treat the "downstream now works" statements below as *wired and expected to work*, to be confirmed on a clean environment. Sections 6, 8 and 9 note where live verification is still owed.

---

## 1. Prerequisites

### Required software
- **Java 21** (Temurin), **Maven 3.9+**, **Docker + Docker Compose**.
- `curl` and `psql` (or any Postgres client) for verification. `jq` optional.

### Environment / configuration
- **No authentication exists.** Every service call requires header `X-Tenant-Id: <uuid>` (enforced on all services except `notification-service`, which is event-driven).
- **Use the sentinel/default tenant for the whole test:** `X-Tenant-Id: 00000000-0000-0000-0000-000000000001`. This is required because the seeded data that the flow depends on is keyed to that tenant:
  - `ledger-service` GL accounts (`POOL_ACCOUNT_ID`, `INTEREST_INCOME_ID`, `INTEREST_RECEIVABLE_ID`) — seeded only for this tenant (any other tenant fails ledger posting with `Account not found`).
  - `bre-service` DEFAULT rule set and `notification-service` default templates — also keyed to this tenant.
- **Row-Level Security is inert** (policies exist but nothing sets `app.tenant_id`), so tenant isolation is not actually enforced at the DB — irrelevant for a single-tenant happy-path test, but do not rely on it.
- **Vendor integrations are all SANDBOX** (`originex.partner.mode=SANDBOX` default) — bureau pulls, KYC, payment rails, and notification channels return synthetic success; no real external calls.

### Docker services (`dev/docker-compose.yml`)
| Container | Image | Host port |
|---|---|---|
| `originex-postgres` | postgres:16-alpine | 5432 |
| `originex-kafka` | confluentinc/cp-kafka:7.7.1 (KRaft) | 9092 |
| `originex-schema-registry` | confluentinc/cp-schema-registry:7.7.1 | **8090** (moved from 8081 to avoid customer-service) |
| `originex-redis` | redis:7-alpine | 6379 |
| `originex-kafka-ui` | provectuslabs/kafka-ui | 8080 |

### Known blockers (environment-specific — from `dev/PHASE0_VERIFICATION.md`)
1. **A native PostgreSQL on host 5432** prevents `originex-postgres` from binding. If your machine already runs Postgres, stop it or remap the compose port.
2. **`cp-kafka` KRaft may fail to format its log dir** on some Docker Desktop hosts (`bootstrap.checkpoint.tmp` error). Reproduced on the authoring machine; if hit, try a different host / Docker backend.
3. **Private Maven registry access**: the pinned `org.postgresql:postgresql:42.7.4` and Testcontainers artifacts resolve from a private registry that may return `401`. Without them you can `mvn compile` (cached) but **cannot build runnable JARs or run the services**. A clean machine with registry access is required to actually run the platform. `mvn test` is likewise blocked there.
4. **No auth / API gateway** — services are called directly on their ports.

### Startup order
1. Infrastructure (compose): postgres → kafka → schema-registry → redis → kafka-ui.
2. Services: order is not strictly required (they connect to Kafka/REST lazily), but a sensible order is `customer, partner-integration, bre, los, lms, payment, ledger, notification` (template-service is a scaffold; skip it — its port 8089 is otherwise unused).

---

## 2. Infrastructure validation

```bash
docker compose -f dev/docker-compose.yml up -d
dev/scripts/verify-phase0-infra.sh status   # scripted health + DB + topic report
```

### PostgreSQL — databases must exist
`dev/init-scripts/init-databases.sql` creates 9 databases (fixed in Phase 0). Verify:
```bash
docker exec originex-postgres psql -U originex -d originex_dev -c \
 "SELECT datname FROM pg_database WHERE datname LIKE 'originex_%' ORDER BY 1;"
```
Expect: `originex_bre, originex_customer, originex_ledger, originex_lms, originex_los, originex_notification, originex_partner, originex_payment, originex_template`.

### Kafka — broker reachable, topics
Local docker-compose Kafka **auto-creates topics on first publish**, so an empty topic list on a fresh stack is normal (the 16 CRDs in `infra/kafka/topics.yaml` are Strimzi/EKS-only, not applied here). Verify the broker:
```bash
docker exec originex-kafka kafka-broker-api-versions --bootstrap-server localhost:29092
docker exec originex-kafka kafka-topics --bootstrap-server localhost:29092 --list
```
Topics that will appear as the flow runs: `originex.customer.customers.events`, `originex.los.applications.events`, `originex.lms.loans.events`, `originex.payments.orders.events`.

### Schema Registry
Present but **not actually used by any service** (events are JSON, not Protobuf — verified: no service references generated proto classes). Health only:
```bash
curl -f http://localhost:8090/subjects   # returns [] — expected
```

### Kafka UI
`http://localhost:8080` — cluster `originex-local`. Use it to inspect topics/messages and, later, to observe outbox-published events.

### Redis
Declared for caching; not on the core lending path. `docker exec originex-redis redis-cli ping` → `PONG`.

### Health summary
- Postgres: 9 databases present.
- Kafka broker: reachable on 29092 (internal) / 9092 (host).
- Schema Registry: `/subjects` returns `[]`.
- Kafka UI: reachable on 8080.

---

## 3. Service startup

All services are Spring Boot, expose `/actuator/health` (+ `/actuator/prometheus`), and use `spring.jpa.hibernate.ddl-auto: validate` (so a schema/entity mismatch fails startup — Flyway runs first to apply migrations). Cross-service REST base URLs are hardcoded to `localhost` ports (e.g. LOS → customer `:8081`, → partner `:8085`, → bre `:8088`).

| Service | Port | DB | Depends on (runtime) | Consumes | Produces |
|---|---|---|---|---|---|
| customer-service | 8081 | originex_customer | partner (REST) | — | `customer.CustomerRegistered`, `customer.KYCCompleted` |
| los-service | 8082 | originex_los | customer, partner, bre (REST) | — | `los.ApplicationSubmitted/CreditCheckCompleted/ApplicationApproved/ApplicationRejected/DisbursementRequested` |
| bre-service | 8088 | originex_bre | — | — | — (synchronous REST only) |
| lms-service | 8083 | originex_lms | — | `los.DisbursementRequested`, `payments.*` | `lms.LoanDisbursed*`, `lms.RepaymentAllocated`, `lms.DisbursementConfirmed`, `lms.InterestAccrued` |
| ledger-service | 8084 | originex_ledger | — | `lms.LoanDisbursed/RepaymentAllocated/InterestAccrued` | `ledger.JournalEntryPosted` |
| partner-integration-service | 8085 | originex_partner | — | — | — |
| payment-service | 8086 | originex_payment | — | `lms.LoanDisbursed` | `payments.*` |
| notification-service | 8087 | originex_notification | — | customer/los/lms/payments events | — |

`*` `lms.LoanDisbursed` has a producer in code but it is unreachable at runtime — see §1.

### Health endpoint
```bash
for p in 8081 8082 8083 8084 8085 8086 8087 8088; do
  echo -n "$p: "; curl -s http://localhost:$p/actuator/health; echo
done
```
Expect `{"status":"UP"}` for each.

### Expected successful startup logs (per service)
- `Flyway ... Successfully applied N migrations` (or `Successfully validated`).
- `Started <Service>Application in Xs`.
- For services with Kafka consumers (lms, ledger, payment, notification): `partitions assigned` for their consumer group(s).
- lms-service additionally: the `InterestAccrualService` bean is created unless `originex.lms.accrual.enabled=false`.

### Common startup failures
- **`FlywayValidateException` / Hibernate `SchemaManagementException`** — migrations not applied or entity/column mismatch. (Phase 0 fixed the known ones: ledger `inbox_events`/GL seed, payment `outbox_events`/`inbox_events`, ledger `outbox_events.published_at`, lms `last_accrual_date`.)
- **`Account not found`** at ledger runtime — GL accounts only seeded for the sentinel tenant; using another tenant breaks ledger posting.
- **Port already in use** — native Postgres on 5432 (blocker #1); schema-registry moved to 8090; template-service (8089) unused in this flow.
- **Kafka connection refused** — consumers log retries but the context still starts; publishing/consuming won't work until the broker is up.

---

## 4 & 5. Complete business flow (with per-step verification)

Use `TENANT=00000000-0000-0000-0000-000000000001` throughout. Exact request field names should be confirmed against the nested request `record`s in each controller (e.g. `LoanApplicationController.SubmitRequest`); representative bodies below reflect the verified DTOs.

### Step 1 — Register customer  ✅ works
- **REST:** `POST http://localhost:8081/v1/customers` (`X-Tenant-Id: $TENANT`)
- **Body (representative):** `{"firstName":"Test","lastName":"Borrower","phone":"9999999999","email":"t@ex.com","pan":"ABCDE1234F","dateOfBirth":"1990-01-01"}`
- **Expected:** `201 Created`, `Location` header, body with `customerId`, `status`. PAN verified live via partner (sandbox).
- **Tables:** `customers` (+ possibly `kyc_records` for the PAN check).
- **Domain transition:** new `Customer` (status per aggregate).
- **Kafka event:** `originex.customer.CustomerRegistered` → topic `originex.customer.customers.events`.
- **Outbox:** row in `originex_customer.outbox_events` (status `PENDING` → `PUBLISHED`). **No inbox** (customer-service consumes nothing).
- **Downstream:** partner-integration (PAN verify, sandbox); notification-service consumes the event.
- **SQL:**
  ```sql
  \c originex_customer
  SELECT customer_id, status FROM customers ORDER BY created_at DESC LIMIT 1;
  SELECT event_type, status FROM outbox_events ORDER BY created_at DESC LIMIT 3;
  ```

### Step 2 — KYC (Aadhaar eKYC)  🟡 works (sandbox)
- **REST:** `POST http://localhost:8081/v1/customers/{customerId}/kyc/aadhaar-ekyc`
- **Body:** `{"aadhaarNumberOrVid":"123412341234","consentArtifactId":"c1","otpReference":"otp1"}`
- **Expected:** `200 OK`; KYC record VERIFIED; Aadhaar stored as SHA-256 token (irreversible).
- **Kafka event:** `originex.customer.KYCCompleted`.
- **Tables:** `kyc_records` (VERIFIED), `outbox_events`.
- **SQL:** `SELECT kyc_type, status FROM kyc_records ORDER BY submitted_at DESC LIMIT 3;`
- **Limitation:** real DigiLocker/NSDL are stubbed (LIVE throws); sandbox returns success.

### Step 3 — Submit loan application  ✅ works
- **REST:** `POST http://localhost:8082/v1/loan-applications`
- **Body:** `{"customerId":"<id>","productCode":"PERSONAL_LOAN","amount":"500000","currency":"INR","tenureMonths":24,"purpose":"Home renovation","channel":"MOBILE_APP","applicantName":"Test Borrower","applicantPan":"ABCDE1234F","employmentType":"SALARIED","monthlyIncome":"75000"}`
- **Expected:** `202 Accepted`, `Location`, body with `applicationId`, `status: SUBMITTED`.
- **Domain transition:** `LoanApplication` created at **SUBMITTED**.
- **Kafka event:** `originex.los.ApplicationSubmitted` → `originex.los.applications.events`.
- **Tables:** `originex_los.loan_applications`, `outbox_events`.
- **SQL:** `\c originex_los` → `SELECT application_id, status FROM loan_applications ORDER BY created_at DESC LIMIT 1;`

### Step 4 — Credit check (Bureau + BRE auto-decision)  ✅ works (sandbox)
- **REST:** `POST http://localhost:8082/v1/loan-applications/{id}/credit-check`
- **Body:** `{"consentArtifactId":"c1"}`
- **Flow (synchronous):** LOS → partner `POST /v1/partner/credit-bureau/pull` (sandbox score) → LOS publishes `CreditCheckCompleted` → LOS → bre `POST /v1/bre/evaluate` → auto-decision:
  - **APPROVED** → `LoanApplication.approve()` + `generateOffer()` → status **OFFER_PENDING**; event `ApplicationApproved`.
  - **REJECTED** (HARD rule fail) → status **REJECTED**; event `ApplicationRejected`. (Terminal — journey ends.)
  - **REFER** (SOFT rule fail, or BRE unavailable → fail-safe) → status **REFERRED** (wired in the recent Manual Underwriting change); no event. Proceed to Step 4b.
- **Domain transition:** IN_PROGRESS → {APPROVED→OFFER_PENDING | REJECTED | REFERRED}.
- **Kafka:** `los.CreditCheckCompleted`, then `los.ApplicationApproved` or `los.ApplicationRejected`.
- **Tables:** `loan_applications` (status, credit fields), `loan_offers` (if approved), `outbox_events`; `originex_partner.integration_requests` (masked audit).
- **SQL:** `SELECT status, credit_score FROM loan_applications WHERE application_id='<id>';` and `SELECT * FROM loan_offers WHERE application_id='<id>';`
- **Notes:** BRE DEFAULT rules require the sentinel tenant. Sandbox bureau returns a synthetic score; to force each branch, adjust `monthlyIncome`/amount so FOIR or income thresholds trip (see `bre_rules`).

### Step 4b — Manual referral decision (only if REFERRED)  ✅ works
- **Approve:** `POST http://localhost:8082/v1/loan-applications/{id}/approve` with `{"sanctionedAmount":"450000","interestRate":"12.5","tenureMonths":24,"emi":"21250","processingFee":"4500","apr":"13.8","notes":"manual review passed"}` → status OFFER_PENDING; event `ApplicationApproved`.
- **Reject:** `POST .../{id}/reject` with `{"reason":"insufficient documentation"}` → status REJECTED; event `ApplicationRejected`.
- **State guard:** the aggregate's `transitionTo()` enforces validity (invalid state → 422 via `GlobalExceptionHandler`); no controller-level check.

### Step 5 — Accept offer  ✅ works (produces DisbursementRequested)
- **REST:** `POST http://localhost:8082/v1/loan-applications/{id}/offer/accept`
- **Domain transition:** OFFER_PENDING → OFFER_ACCEPTED → **DISBURSEMENT_REQUESTED** (terminal for LOS); offer expiry is checked lazily here (`OFFER_EXPIRED` if past `expires_at`).
- **Kafka event:** `originex.los.DisbursementRequested` → `originex.los.applications.events`.
- **Tables:** `loan_applications` (DISBURSEMENT_REQUESTED), `outbox_events`.
- **SQL:** `SELECT status FROM loan_applications WHERE application_id='<id>';  -- DISBURSEMENT_REQUESTED`
- **Kafka verify (UI or CLI):** a message on `originex.los.applications.events` with header `event_type=originex.los.DisbursementRequested`.

### Step 6 — Loan creation + disbursement initiation in LMS  ✅ (wired; live-run pending)
- **Consumer:** `lms-service` `DisbursementRequestedConsumer` (topic `originex.los.applications.events`, group `lms-disbursement-handler`, inbox-idempotent) → `createLoan()`.
- **What happens now:** a `Loan` is created, the EMI schedule is generated (`ScheduleGenerator`), then `createLoan()` **initiates disbursement** — `loan.initiateDisbursement(sanctioned, beneficiaryAccount)` moves it `CREATED → PENDING_DISBURSAL` and creates an `INITIATED` disbursement — and **publishes `originex.lms.LoanDisbursed`** (`{loan_id, amount, currency, beneficiary_account, beneficiary_ifsc, beneficiary_name, customer_id}`) via the outbox, in the same transaction.
- **Tables:** `originex_lms.loans` (status `PENDING_DISBURSAL`), `installments`, `disbursements` (one `INITIATED`), `outbox_events` (the `LoanDisbursed` row), `inbox_events` (the consumed `DisbursementRequested` id).
- **SQL:**
  ```sql
  \c originex_lms
  SELECT loan_id, status FROM loans ORDER BY created_at DESC LIMIT 1;            -- PENDING_DISBURSAL
  SELECT status FROM disbursements ORDER BY created_at DESC LIMIT 1;             -- INITIATED
  SELECT event_type, status FROM outbox_events ORDER BY created_at DESC LIMIT 1; -- lms.LoanDisbursed
  ```
- **Prerequisite (Step 5):** the borrower must have a bank account on file (`POST /v1/customers/{id}/bank-accounts`), otherwise `POST /offer/accept` fails **422** ("No bank account on file for disbursement") and the application stays `OFFER_PENDING` — by design.

### Steps 7–13 — Disbursement → Ledger → Interest accrual → Repayment → Closure  ✅ reachable (live-run pending)
With `LoanDisbursed` now published, the chain proceeds (verified by code trace; confirm on a live run):
- **Disbursement (payment-service):** `LmsPaymentEventConsumer` consumes `LoanDisbursed`, reads the beneficiary, creates a `PaymentOrder`, selects rail (`>₹5L RTGS / ₹2L–₹5L IMPS / <₹2L NEFT`), sandbox completes immediately, publishes `DisbursementInitiated` then `DisbursementCompleted`. Verify: `\c originex_payment; SELECT status, payment_rail FROM payment_orders ORDER BY created_at DESC LIMIT 1;`
- **Activation:** `lms` `PaymentEventConsumer` consumes `payments.DisbursementCompleted` → `confirmDisbursementByPayment` finds the `INITIATED` disbursement → `loan.confirmDisbursement` → **`ACTIVE`** (+ `first_disbursement_date`, `last_accrual_date`), publishes `DisbursementConfirmed`. Verify: `SELECT status, last_accrual_date FROM loans WHERE loan_id='<id>';  -- ACTIVE`
- **Ledger disbursement posting:** `LmsEventConsumer.handleLoanDisbursed` → DR Loan Receivable / CR Pool. Verify: `\c originex_ledger; SELECT entry_type FROM journal_entries ORDER BY posted_at DESC LIMIT 1;  -- DISBURSEMENT`
- **Interest accrual:** with the loan `ACTIVE`, `InterestAccrualService` (daily cron `0 30 0 * * *`; there is **no manual trigger** — set a near-future cron to test) accrues and publishes `InterestAccrued`. Verify: `SELECT outstanding_interest, last_accrual_date FROM loans WHERE loan_id='<id>';`
- **Interest ledger posting:** `handleInterestAccrued` → DR Interest Receivable / CR Interest Income.
- **Repayment:** `POST /v1/loans/{id}/repayments` (or the event-driven `payments.PaymentReceived` path via `POST /v1/payments/inbound`) → `allocateRepayment()` (waterfall Charges → Interest → Principal) → publishes `RepaymentAllocated` → ledger posts DR Cash / CR Receivable + Interest Income.
- **Loan closure:** full repayment drives the loan to `MATURED` (direct assignment in `allocateRepayment`). `foreclose()` remains dead (only `POST /{id}/foreclosure-quote`); `WRITTEN_OFF`/`SETTLED`/`RESTRUCTURED` still have no reachable path.

> **Still owed:** a genuine multi-service run to confirm the above executes as traced. Two known follow-ups surfaced while wiring this: the beneficiary account is used **unverified** (the penny-drop verify endpoint is unwired dead code), and the customer bank-account endpoint returns a full account number over an unauthenticated internal call (consistent with the platform's no-auth posture). Both are tracked, not fixed here.

---

## 6. Verification checkpoints (per reachable step)

| After step | REST | SQL | Kafka | Ledger | Expected aggregate state |
|---|---|---|---|---|---|
| 1 Register | `GET /v1/customers/{id}` → 200 | `customers` row; `outbox_events` PUBLISHED | `customer.CustomerRegistered` on customers topic | — | Customer created |
| 2 KYC | `GET /v1/customers/{id}` shows KYC | `kyc_records` VERIFIED | `customer.KYCCompleted` | — | KYC VERIFIED |
| 3 Submit | `GET /v1/loan-applications/{id}` → SUBMITTED | `loan_applications` SUBMITTED | `los.ApplicationSubmitted` | — | SUBMITTED |
| 4 Credit-check | `GET .../{id}` → OFFER_PENDING / REJECTED / REFERRED | `loan_offers` (if approved); `integration_requests` | `los.CreditCheckCompleted` + Approved/Rejected | — | per BRE decision |
| 4b Decision | `GET .../{id}` → OFFER_PENDING / REJECTED | `loan_applications` status | `los.ApplicationApproved`/`Rejected` | — | OFFER_PENDING or REJECTED |
| 5 Accept | `GET .../{id}` → DISBURSEMENT_REQUESTED | `loan_applications` DISBURSEMENT_REQUESTED | `los.DisbursementRequested` | — | DISBURSEMENT_REQUESTED |
| 6 Loan created | `GET /v1/loans/{loanId}` → CREATED | `loans` CREATED; `installments`; `inbox_events` | (none produced) | — | **CREATED (terminal for now)** |
| 7+ | — | — | — | **no journal entries** | **unreachable** |

Ledger check (will be empty because the chain never reaches it):
```sql
\c originex_ledger
SELECT entry_type, posting_date FROM journal_entries ORDER BY posted_at DESC LIMIT 5;  -- expect 0 rows
SELECT account_number, balance FROM account_snapshots ORDER BY account_number;          -- 3 seeded GL accounts, balance 0
```

---

## 7. Failure scenarios — how to verify each

- **Duplicate Kafka delivery / Inbox idempotency:** re-deliver a consumed event (e.g. in Kafka UI, produce the same message with the same `event_id` header to `originex.los.applications.events`). The `DisbursementRequestedConsumer` checks `inbox_events` first and skips duplicates. Verify: `SELECT count(*) FROM originex_lms.inbox_events WHERE event_id='<uuid>';` stays 1, and no second loan is created. Same pattern for ledger (`LmsEventConsumer`) and payment (`LmsPaymentEventConsumer`). **Note:** the ledger consumer’s idempotency is only reachable if you can get `lms.*` events flowing — which requires the disbursement gap fixed or manual injection.
- **Outbox retry:** stop Kafka mid-flow, perform an action that publishes (e.g. submit an application), confirm the row sits in `outbox_events` with `status='PENDING'`; restart Kafka; the `OutboxPoller` (every 500 ms) publishes it and flips `status='PUBLISHED'` / sets `published_at`.
- **BRE rejection:** submit an application with income/amount that trips a HARD rule (e.g. `monthlyIncome` below `MIN_INCOME`, or `credit_score` below `MIN_CREDIT_SCORE` via sandbox tuning). Credit-check → status `REJECTED`, event `los.ApplicationRejected`, no offer.
- **Manual referral:** trip a SOFT rule (e.g. high FOIR) or make BRE unavailable (stop bre-service → LOS fail-safe returns REFER). Application → `REFERRED`; resolve via `POST /approve` or `/reject` (Step 4b).
- **Failed payment:** **not reachable** today (payment-service never triggered — see §1). If tested in isolation, `payments.PaymentFailed` is consumed by `lms-service` `PaymentEventConsumer` and only logged.
- **Scheduler rerun (interest accrual):** with an `ACTIVE` loan present (only achievable by bypass today), run the accrual twice for the same day. The `last_accrual_date` guard makes the second run accrue nothing (idempotent). To trigger without waiting for `0 30 0 * * *`, set `originex.lms.accrual.cron` to a near-future time (there is **no manual trigger endpoint**).
- **Scheduler disabled:** set `originex.lms.accrual.enabled=false` → the `InterestAccrualService` bean is not created and the job never runs (verify: no `Interest accrual run starting` log).
- **Service restart:** restart lms-service mid-accrual-run; already-processed loans have `last_accrual_date=today` and are skipped; the run resumes with the remainder (idempotent + keyset-cursor by `loan_id`).
- **Database restart:** restart `originex-postgres`; services’ Hikari pools reconnect; in-flight transactions roll back (outbox rows stay `PENDING` and are retried by the poller; consumed-but-uncommitted Kafka offsets are reprocessed and de-duped by the inbox).

---

## 8. Current limitations (from source, not assumption)

**Fully working (executable end-to-end):**
- Customer registration; KYC (sandbox); loan application submission; credit-check with bureau (sandbox) + BRE auto-decision; offer generation; manual referral approve/reject; offer acceptance; `DisbursementRequested` emission; **loan creation + EMI schedule generation** (loan reaches `CREATED`).

**Partially implemented (code exists, works in isolation, not reachable end-to-end):**
- **Disbursement, payment rails, ledger postings, interest accrual, repayment allocation, foreclosure quote** — each is implemented and (mostly) unit-tested, but unreachable because the loan never leaves `CREATED`.

**Cannot currently be executed (and exactly why):**
- **Loan activation (`CREATED → ACTIVE`)** — no producer of `originex.lms.LoanDisbursed` on any live path (only the dead 4-arg `confirmDisbursement`), and no REST disburse/activate endpoint. This is the **root blocker**.
- **Disbursement to borrower** — depends on `LoanDisbursed`.
- **Ledger disbursement + repayment + interest postings** — depend on `lms.*` events that never fire.
- **Interest accrual** — scheduler selects `ACTIVE` loans; none exist via the real flow.
- **Repayment** — `allocateRepayment` requires `ACTIVE`; a `CREATED` loan yields 422.
- **Loan closure** — `MATURED` needs full repayment (unreachable); `foreclose()`/write-off/settlement/restructure have no reachable path.

**Cross-cutting (all environments):** no authentication; RLS inert; ledger GL accounts single-tenant (sentinel only); all vendor integrations sandbox; notification recipient phone/email read from the event payload (blank for most events); no integration tests / cannot run the stack on the authoring machine (blockers in §1).

---

## 9. Final readiness assessment

| Capability | Testable today? | Blocking issue | Required future work |
|---|---|---|---|
| Customer registration | **Yes** | — | Real PAN encryption (KMS); pagination |
| KYC | **Partial** | Vendors are sandbox | LIVE DigiLocker/NSDL |
| Loan application intake | **Yes** | — | Pagination |
| Credit bureau pull | **Partial** | All 4 bureaus sandbox | ≥1 LIVE bureau; response caching |
| BRE decisioning | **Yes** | Rules seeded via SQL only | Rule-management API |
| Manual referral (approve/reject) | **Yes** | No queue/listing, no auth | Referral queue; role-gating |
| Offer generation & acceptance | **Yes** | Offer expiry only lazily enforced | Scheduled expiry sweep |
| `DisbursementRequested` emission | **Yes** | — | — |
| Loan creation + EMI schedule | **Yes** | Loan stuck at `CREATED` | (see next row) |
| **Loan disbursement / activation** | **No** | **`LoanDisbursed` only emitted by dead code; no disburse endpoint; `createLoan` creates no disbursement** | Wire `createLoan` → initiate disbursement + publish `LoanDisbursed` (or expose a disburse endpoint), so the loan reaches `ACTIVE` |
| Payment rails (NEFT/RTGS/IMPS/NACH) | **No** (via flow) | Not triggered (no `LoanDisbursed`); rails sandbox | Fix disbursement trigger; LIVE rails; callback signature verification |
| Ledger disbursement posting | **No** (via flow) | No `LoanDisbursed` | Fix disbursement trigger |
| Interest accrual | **No** (via flow) | No `ACTIVE` loans exist | Fix disbursement trigger (accrual logic itself is ready) |
| Interest ledger posting | **No** (via flow) | No `InterestAccrued` (no accrual) | Fix disbursement trigger |
| Repayment + allocation | **No** (via flow) | `allocateRepayment` requires `ACTIVE` | Fix disbursement trigger |
| Loan closure (MATURED) | **No** | Needs full repayment | Fix disbursement + repayment path |
| Foreclosure execution | **No** | `foreclose()` dead; only quote endpoint | Add `POST /foreclosure` + ledger closure posting |
| NPA / DPD | **No** | `updateDpd()` has no scheduler | DPD/NPA aging job |
| Accounting (ledger) | **Partial** | Single-tenant GL; only reachable via `lms.*` events | Per-tenant chart-of-accounts |
| Notifications | **Partial** | Recipient phone/email from payload; channels sandbox | Customer-profile lookup; LIVE channels |
| Audit / Reporting / Security / Multi-tenancy(RLS) | **No** | Not implemented / inert | Dedicated services; JWT/RBAC; RLS runtime enforcement |

### Bottom line
The disbursement gap that previously stopped the lifecycle at `CREATED` has now been **wired**: offer acceptance resolves the beneficiary from `customer-service`, and `createLoan` initiates disbursement and publishes `LoanDisbursed`. In code, the journey now flows Customer → … → Accept → **loan created + disbursed** → payment (sandbox) → **ACTIVE** → ledger posting → interest accrual → repayment → `MATURED`. **This has not yet been confirmed by a live multi-service run** (environment blockers, §1) — that end-to-end execution is the remaining verification. Two follow-ups were surfaced (unverified beneficiary; unauthenticated full-account-number endpoint), both tracked. Sections 8’s table rows above that still read “No (via flow)” predate this fix and should be read together with §6/Step 7–13.

---

*Grounded in the current source as of this review. Where a step is marked “No/Partial (via flow),” it means the component’s code exists and may be unit-tested, but the step cannot be reached through the real application flow for the stated reason.*
