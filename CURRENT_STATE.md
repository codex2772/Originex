# CURRENT_STATE.md
## Originex Platform — Source-Verified State Snapshot

**Date:** 9 July 2026
**Basis:** This document was produced by reading the current repository from scratch — migrations, controllers, consumers, application services, domain models, port interfaces, `pom.xml`, and `application.yml` files. It supersedes any earlier assessment. Every claim below was verified against source; nothing is inferred from prior handover/analysis documents. The repo state reflects the 7 Phase 0 stabilization commits already merged on this branch.

### How to read the production-readiness score
A 0–10 score per service, where **10 = deployable to a regulated production lending environment**. Scores are capped platform-wide by cross-cutting gaps (below) that affect every service equally — no service can score high while there is no authentication, RLS is inert, and all external integrations are sandbox stubs. Treat the score as "how close to production," not "how good is the code" — the domain layer is generally well-built even where the score is low.

---

## Platform-Wide Facts (verified)

| Aspect | State |
|---|---|
| Modules | 11 Maven modules: `proto`, `libs/common`, `libs/spring-boot-starter`, + 9 services (incl. `template-service`) |
| Architecture | Hexagonal (domain / application / adapter) — consistently applied; ArchUnit rules exist but run **only** in `template-service` |
| Auth / IAM | **None.** No JWT, no OAuth2, no gateway. Any caller with a valid `X-Tenant-Id` header reaches any endpoint. |
| Tenant isolation | `X-Tenant-Id` header → `TenantContextHolder` (enforce=true on all services except `notification-service`). RLS policies exist in every migration, **but nothing sets `app.tenant_id` at the DB session** — verified: zero `SET LOCAL` / `set_config` / `StatementInspector` anywhere in Java. **RLS is inert at runtime.** |
| Event serialization | **JSON**, hand-built via `String.format(...).getBytes()`. Protobuf classes are generated but **referenced by zero service code** (verified). |
| Outbox/Inbox | Transactional outbox used by customer/los/lms/ledger/payment (+template). `bre`, `partner`, `notification` do **not** publish via outbox (0 usages). No direct `KafkaTemplate` use outside the starter. |
| Resilience4j | 21 `@CircuitBreaker` + 17 `@Retry` annotations across outbound adapters (bureau/KYC/bank/rails/channels/inter-service REST). |
| Scheduled jobs | 3: notification retry (10 min), payment retry (5 min), **lms interest-accrual (daily, `InterestAccrualService`)**. No DPD-aging or NPA-classification scheduler. |
| Tests | 14 test files, **all domain/unit or migration-file level**. **Zero** `@Testcontainers`, zero `*IntegrationTest`/`*IT`. No web-layer or consumer integration tests. |
| External integrations | **100% sandbox.** Every bureau/KYC/bank/payment-rail/notification-channel adapter throws `UnsupportedOperationException` in LIVE mode. |
| Local runtime (this environment) | Not bootable end-to-end here: native Postgres occupies host 5432, and `cp-kafka` KRaft fails to format on this Docker host. Both are machine-specific, documented in `dev/PHASE0_VERIFICATION.md`; not repo defects. **No full multi-service boot has been observed.** |

---

## customer-service — port 8081

**Purpose:** Source of truth for customer identity, KYC lifecycle, and bank accounts.
**Implementation status:** Substantially implemented; one documented endpoint unwired; PAN encryption is a stub.

**APIs implemented** (`CustomerController`, `/v1/customers`):
- `POST /` — register
- `GET /{customerId}`
- `PUT /{customerId}` — update profile (optimistic lock via `If-Match`)
- `POST /{customerId}/kyc` — submit KYC
- `POST /{customerId}/kyc/{kycRecordId}/complete`
- `POST /{customerId}/kyc/aadhaar-ekyc`
- `POST /{customerId}/bank-accounts` — add bank account

**Kafka producers:** `originex.customer.CustomerRegistered`, `originex.customer.KYCCompleted` (published from both `completeKyc` and `initiateAadhaarEkyc`).
**Kafka consumers:** none.
**Database:** `customers`, `kyc_records`, `bank_accounts`, `addresses`, `outbox_events`. RLS on customers/kyc_records/bank_accounts (3 policies). No `inbox_events` (no consumer).
**Flyway:** `V1` only.
**Domain aggregates:** `Customer` (root), `KycRecord`, `BankAccount`, `Address`.
**State machines:** `CustomerStatus`, `KycStatus`, `KycRecord.KycRecordStatus` (PENDING/VERIFIED/REJECTED/EXPIRED) — simple enums, no `canTransitionTo` guard.
**External integrations:** partner-integration-service via 3 REST adapters (PAN, Aadhaar, bank verify), each with `@CircuitBreaker`+`@Retry` and fail-closed fallbacks.
**Missing functionality:** bank-account verification endpoint (method exists, no route); real PAN encryption; pagination on any list; auth.
**Dead code:** `CustomerUseCase.verifyBankAccount(...)` — fully implemented, invoked by no controller.
**TODOs:** `CustomerApplicationService.encryptPan()` — `TODO Phase 4: AWS KMS`.
**Known risks:** PAN "encryption" is `"ENC:" + pan.substring(0,5) + "XXXXX"` — not encryption. Aadhaar tokenized as `SHA-256(aadhaar + tenantId)` (tenant UUID as salt, not a secret).
**Test coverage:** `CustomerTest` (domain only). No service/web tests.
**Production readiness: 4/10.**

---

## los-service — port 8082

**Purpose:** Loan application intake, credit-check orchestration (bureau + BRE), auto-decisioning, offer generation.
**Implementation status:** Rich orchestration implemented; the manual-review (approve/reject) path is now wired.

**APIs implemented** (`LoanApplicationController`, `/v1/loan-applications`):
- `POST /` — submit (returns 202)
- `GET /{applicationId}`
- `POST /{applicationId}/documents`
- `POST /{applicationId}/credit-check` — bureau pull + BRE eval + auto-decision
- `POST /{applicationId}/approve` — manual approve with supplied offer terms
- `POST /{applicationId}/reject` — manual reject with reason
- `POST /{applicationId}/offer/accept`
- `DELETE /{applicationId}` — withdraw

**Kafka producers:** `ApplicationSubmitted`, `CreditCheckCompleted`, `ApplicationRejected`, `ApplicationApproved`, `DisbursementRequested` (all `originex.los.*`).
**Kafka consumers:** none.
**Database:** `loan_applications`, `loan_offers`, `application_documents`, `outbox_events`. RLS 3.
**Flyway:** `V1` only.
**Domain aggregates:** `LoanApplication` (root), `LoanOffer` (VO), `ApplicationDocument`.
**State machine:** `ApplicationStatus` — 11 states with `canTransitionTo` guard: DRAFT, SUBMITTED, IN_PROGRESS, REFERRED, APPROVED, REJECTED, OFFER_PENDING, OFFER_ACCEPTED, OFFER_EXPIRED, DISBURSEMENT_REQUESTED, WITHDRAWN. `DRAFT` is in the enum but `submit()` starts at SUBMITTED, so DRAFT is unreachable in practice. `REFERRED` is reached when the credit-check gets `REFER_TO_UNDERWRITER` (now wired via `LoanApplication.refer`) and is exited via the approve/reject endpoints. `OFFER_EXPIRED` is reached **lazily** — `acceptOffer()` → `LoanApplication` checks `currentOffer.isExpired()` and transitions; there is no scheduler.
**External integrations:** customer-service (GET eligibility), partner (bureau pull), bre (evaluate) — 3 REST adapters w/ CB+Retry. If BRE is down, fallback returns REFER_TO_UNDERWRITER (fail-safe).
**Missing functionality:** referral queue/listing endpoint; pagination; auth. (The REFERRED dead-end — no API to resolve a referred application — is resolved: approve/reject endpoints now exist.)
**Dead code:** `LoanApplicationUseCase.recordCreditResult(...)` — implemented, invoked by no controller/consumer. (`approveAndGenerateOffer` is now wired via `POST /approve`.)
**TODOs:** none material in this service.
**Known risks:** referred applications have no listing/queue endpoint yet (must be fetched by id); decision endpoints are unauthenticated (no auth platform-wide).
**Test coverage:** `LoanApplicationTest` (incl. referral→approve/reject cases), `EndToEndDomainFlowTest` (both pure-domain).
**Production readiness: 4/10.**

---

## bre-service — port 8088

**Purpose:** Configurable per-tenant rule engine for eligibility, auto-decisioning, and offer calculation.
**Implementation status:** The most self-contained and complete service; cleanly REST-only.

**APIs implemented:** `POST /v1/bre/evaluate` (`BREController`).
**Kafka producers:** none. **Kafka consumers:** none. (No outbox/inbox usage — verified.)
**Database:** `bre_rule_sets`, `bre_rules` (2 RLS policies). Seeds **16 rule rows across 3 rule sets** (DEFAULT with 9, PERSONAL_LOAN_SALARIED with 7, PERSONAL_LOAN_SELF_EMPLOYED empty). No outbox/inbox tables.
**Flyway:** `V1` only.
**Domain aggregates:** `Rule`, `RuleSet`, `EvaluationFacts` (record, derives FOIR/age-at-maturity), `EvaluationResult`. Engines: `RuleEvaluationEngine`, `OfferCalculator`.
**State machines:** none. Enums: `Decision` (APPROVED/REJECTED/REFER_TO_UNDERWRITER), `RuleType` (HARD/SOFT/ADVISORY), `RuleCategory`, `RuleOperator`.
**External integrations:** none.
**Missing functionality:** rule/rule-set CRUD API (rules only seedable via SQL); gRPC endpoint (removed earlier); auth.
**Dead code:** none identified.
**TODOs:** none material.
**Known risks:** the `EMPLOYMENT_TYPE` seed rule uses a fragile `UPDATE`-after-`INSERT` for `allowed_values`.
**Test coverage:** `BREDomainTest` (11 cases — engine + offer math). Best-tested business service.
**Production readiness: 5/10** (highest — but no rule-management API and no auth).

---

## lms-service — port 8083

**Purpose:** Post-disbursement loan lifecycle — loan creation, EMI schedule, repayment allocation, DPD/NPA.
**Implementation status:** Disbursement is now wired — `createLoan` initiates disbursement and publishes `LoanDisbursed` (with the beneficiary propagated from customer-service at offer acceptance), so the loan reaches `ACTIVE` and interest accrual/repayment become reachable (previously the loan stalled at `CREATED`; not yet confirmed by a live run). REST surface and several lifecycle behaviors still incomplete.

**APIs implemented** (`LoanController`, `/v1/loans`):
- `GET /{loanId}`
- `GET /{loanId}/repayment-schedule`
- `POST /{loanId}/repayments`
- `POST /{loanId}/foreclosure-quote`
(No `POST /v1/loans` create, no `disburse` — loan creation happens **only** via the Kafka consumer.)

**Kafka producers:** `originex.lms.LoanDisbursed`, `RepaymentAllocated`, `DisbursementConfirmed`.
**Kafka consumers:**
- `DisbursementRequestedConsumer` — `originex.los.DisbursementRequested` → `createLoan()` + EMI schedule (inbox-idempotent).
- `PaymentEventConsumer` — `originex.payments.DisbursementCompleted` → confirm; `PaymentReceived` → allocate repayment; `PaymentFailed` → logged only.

**Database:** `loans`, `installments`, `disbursements`, `loan_charges`, `outbox_events`, `inbox_events`. RLS on loans/installments (2 — note disbursements/loan_charges not RLS-protected).
**Flyway:** `V1` only.
**Domain aggregates:** `Loan` (root), `Installment`, `Disbursement`, `LoanCharge`. Domain service: `ScheduleGenerator` (reducing-balance EMI).
**State machine:** `LoanStatus` — 11 states with `canTransitionTo`. **Two transitions bypass the guard via direct field assignment:** NPA/DOUBTFUL/LOSS in `updateDpd()`, and MATURED-on-payoff in `allocateRepayment()`.
**External integrations:** none direct (Kafka only).
**Missing functionality:**
- **Penal-interest bucket does not exist.** `Loan` has `outstandingPrincipal/Interest/Charges` only — no `outstandingPenal` field — yet the class Javadoc and method comment both claim the waterfall is "Charges → Penal → Interest → Principal." Actual waterfall is **Charges → Interest → Principal**.
- **NPA aging never runs at runtime.** `updateDpd()` is defined and unit-tested but invoked by **no scheduler or consumer** — DPD/NPA classification cannot happen in a running system.
- `RESTRUCTURED` is a reachable state with no schedule-regeneration logic behind it.
- No create/disburse REST surface; pagination; auth.

**Dead code:** `LoanUseCase.disburseLoan(...)` (zero callers anywhere) and `LoanUseCase.confirmDisbursement(...)` (4-arg application method — no adapter calls it; only `confirmDisbursementByPayment` is wired). Note: the **domain** method `loan.confirmDisbursement(...)` is used internally and is not dead.
**TODOs:** none material (comments only).
**Known risks:** downstream reconciliation assuming a penal bucket will be wrong; NPA reporting is impossible until a scheduler invokes `updateDpd()`.
**Test coverage:** `LoanTest`, `ScheduleGeneratorTest` (domain).
**Production readiness: 3.5/10.**

---

## ledger-service — port 8084

**Purpose:** Event-sourced double-entry accounting ledger; financial source of truth.
**Implementation status:** Double-entry invariant solid; now bootstrappable (Phase 0); chart-of-accounts is hardcoded and single-tenant.

**APIs implemented** (`LedgerController`, `/v1/ledger`):
- `POST /accounts`, `GET /accounts/{accountId}`
- `POST /journal-entries`, `POST /journal-entries/{entryId}/reverse`

**Kafka producers:** `originex.ledger.JournalEntryPosted`.
**Kafka consumers:** `LmsEventConsumer` — `originex.lms.LoanDisbursed` / `RepaymentAllocated` / `InterestAccrued` → auto-posts balanced journal entries (inbox-idempotent).
**Database:** `ledger_events` (RANGE-partitioned by `occurred_at`, **only 2 monthly partitions: y2026m07, y2026m08**), `account_snapshots`, `journal_entries`, `postings`, `outbox_events`, `inbox_events`. RLS 3. **3 GL accounts seeded** for the default tenant (Phase 0 commit 1).
**Flyway:** `V1` (schema; PK partition-key bug fixed), `V2` (seed accounts + inbox), `V3` (outbox `published_at`).
**Domain aggregates:** `JournalEntry` (root — enforces `SUM(debits)=SUM(credits)`, ≥2 postings, immutable/reversal-only), `Account`.
**State machines:** `EntryStatus` (POSTED/REVERSED), `AccountStatus` (ACTIVE/FROZEN/CLOSED). AccountType is ASSET/LIABILITY/EQUITY/REVENUE/EXPENSE.
**External integrations:** none.
**Missing functionality:** configurable chart-of-accounts (the 3 GL account UUIDs are hardcoded constants in `LmsEventConsumer` and seeded for **one** tenant only — any other tenant will hit "Account not found"); partition automation (`ledger_events` has no partition beyond Aug 2026 — inserts will fail Sept 2026 without `pg_partman` or a job); auth.
**Dead code:** none identified.
**TODOs:** none material.
**Known risks:** single-tenant GL accounts; partition exhaustion after Aug 2026; RLS inert like everywhere.
**Test coverage:** `JournalEntryTest` (double-entry invariant), `LmsEventConsumerBootstrapTest`, `OutboxSchemaMigrationTest` (schema regression guards from Phase 0).
**Production readiness: 3.5/10.**

---

## partner-integration-service — port 8085

**Purpose:** Anti-corruption layer for all external vendor APIs (bureau, Aadhaar, PAN, bank).
**Implementation status:** Cleanly structured, but 100% sandbox and untested.

**APIs implemented** (`PartnerIntegrationController`, `/v1/partner`):
- `POST /credit-bureau/pull`, `POST /aadhaar/verify`, `POST /pan/verify`, `POST /bank-account/verify`

**Kafka producers:** none. **Kafka consumers:** none.
**Database:** `integration_requests` (1 RLS policy). No outbox/inbox.
**Flyway:** `V1` only.
**Domain aggregates:** `IntegrationRequest` + result VOs (`BureauReport`, `PanVerificationResult`, `AadhaarVerificationResult`, `BankAccountVerificationResult`).
**State machines:** `IntegrationStatus` (PENDING/SUCCESS/FAILED/CIRCUIT_OPEN), `PartnerType`.
**External integrations:** 7 vendor adapters, **all sandbox** — `CibilBureauAdapter`, `ExperianBureauAdapter`, `EquifaxBureauAdapter`, `CrifBureauAdapter`, `DigiLockerAadhaarAdapter`, `NsdlPanAdapter`, `PennyDropBankVerificationAdapter`. Each throws `UnsupportedOperationException` in LIVE mode. All have CB+Retry.
**Missing functionality:** every LIVE integration; response caching (the table has `cache_expires_at` but caching logic isn't wired); auth.
**Dead code:** none identified.
**TODOs:** `Phase 4` LIVE-integration TODOs in every adapter.
**Known risks:** entirely non-functional against real vendors; PII masking present in logs.
**Test coverage:** **none.**
**Production readiness: 3/10.**

---

## payment-service — port 8086

**Purpose:** Orchestrates fund movement — disbursements (NEFT/RTGS/IMPS) and EMI collection (NACH).
**Implementation status:** Order lifecycle and rail selection implemented and tested; rails are sandbox.

**APIs implemented** (`PaymentController`, `/v1/payments`):
- `POST /disbursements`, `GET /{paymentOrderId}`, `POST /inbound`, `POST /callbacks`, `POST /mandates`, `POST /mandates/{mandateId}/collect`

**Kafka producers:** `DisbursementInitiated`, `NachMandateRegistered`, `CollectionInitiated`, `PaymentReceived`, `DisbursementCompleted`, `PaymentFailed` (all `originex.payments.*`).
**Kafka consumers:** `LmsPaymentEventConsumer` — `originex.lms.LoanDisbursed` → initiate disbursement (inbox-idempotent).
**Database:** `payment_orders`, `nach_mandates` (V1), `outbox_events`, `inbox_events` (V2, Phase 0). RLS on payment_orders/nach_mandates (2).
**Flyway:** `V1` (schema), `V2` (outbox/inbox — Phase 0 commit 2).
**Domain aggregates:** `PaymentOrder` (root), `NachMandate`.
**State machines:** `PaymentOrderStatus` — CREATED/INITIATED/PROCESSING/COMPLETED/FAILED/RETRY_PENDING/PERMANENTLY_FAILED/CANCELLED (guarded via `assertStatus`). `NachMandate.MandateStatus` — PENDING_REGISTRATION/ACTIVE/PAUSED/CANCELLED/EXPIRED/REJECTED. Retries: 3 for disbursements, 2 for NACH collection.
**External integrations:** 4 rail adapters, **all sandbox** (NEFT/RTGS/IMPS/NACH); LIVE throws `UnsupportedOperationException`.
**Missing functionality:** all LIVE rails; webhook signature verification on `POST /callbacks`; auth.
**Dead code:** none identified (Phase 0 commit 4 removed the dead NEFT branch; `selectRail` is now `>₹5L→RTGS / ₹2L–₹5L→IMPS / <₹2L→NEFT`).
**TODOs:** LIVE-mode TODOs in rail adapters.
**Known risks:** callbacks are unauthenticated and unverified; sandbox completes payments instantly.
**Test coverage:** `PaymentOrderTest` (state machine), `PaymentApplicationServiceTest` (13 `selectRail` boundary/override tests — Phase 0 commit 4).
**Production readiness: 4/10.**

---

## notification-service — port 8087

**Purpose:** RBI-mandated multi-channel borrower communication driven by domain events.
**Implementation status:** Consumer + templating + retry implemented; recipient data sourced from event payloads only; channels sandbox.

**APIs implemented:** none (event-driven; `enforce=false` so no tenant header required).
**Kafka producers:** none.
**Kafka consumers:** `DomainEventNotificationConsumer` — one `@KafkaListener` on **4 topics**: `originex.customer.customers.events`, `originex.los.applications.events`, `originex.lms.loans.events`, `originex.payments.orders.events`.
**Database:** `notification_requests`, `channel_dispatches`, `notification_templates` (2 RLS policies; templates seeded with **12 rows** under sentinel tenant `00000000-…-000001`). **No outbox/inbox tables** — idempotency is via `notificationRepository.existsBySourceEventId(...)` on its own table, not the shared inbox pattern.
**Flyway:** `V1` only.
**Domain aggregates:** `NotificationRequest` (root), `ChannelDispatch`, `NotificationTemplate`. Domain service: `EventToNotificationMapper`.
**State machines:** `NotificationStatus` (PENDING/DELIVERED/PARTIALLY_DELIVERED/FAILED/SUPPRESSED), `ChannelDispatch.DispatchStatus`. `NotificationTrigger` enum: **35 triggers** (many RBI-tagged).
**External integrations:** 4 channel adapters, **all sandbox** — `Msg91SmsAdapter`, `SesEmailAdapter`, `GupshupWhatsAppAdapter`, `FcmPushAdapter`.
**Missing functionality:** **recipient lookup** — the consumer reads `phone`/`email`/`customer_name` straight from the event payload (`getField(payload, "phone")`), with an in-code note that production should query customer-service; most events don't carry these; all LIVE channels; per-tenant/per-language template overrides beyond the seeded English defaults.
**Dead code:** none identified.
**TODOs:** `Phase 4` LIVE-channel TODOs in every adapter; language hardcoded `"en"`.
**Known risks:** notifications will have empty phone/email for most real events until a customer lookup is added.
**Retry scheduler:** every 10 min, per-channel.
**Test coverage:** `NotificationRequestTest` (domain).
**Production readiness: 4/10.**

---

## template-service — port 8089 (was 8080; moved in Phase 0 commit 5)

**Purpose:** Reference/scaffold service — not a business capability. Copy to create new services.
**Contents:** `Sample` aggregate + `SampleStatus`; full REST CRUD (`/v1/samples`); `outbox_events`/`inbox_events`/`samples` tables; `HexagonalArchitectureTest` (4 ArchUnit rules: domain-no-application, application-no-adapter, domain-no-spring, inbound-adapter-no-outbound-adapter).
**Production readiness: N/A** (scaffold; not deployed as a capability).

---

## Missing Features — Master List (classified)

### Critical (blocks production go-live; regulatory or data-integrity)
| Feature | Where | Why critical |
|---|---|---|
| Authentication / authorization (IAM) | Platform-wide | Every endpoint is open to any caller with a tenant header. No service can go live. |
| RLS runtime enforcement | `libs/spring-boot-starter` (needs a `SET LOCAL app.tenant_id` interceptor) | Multi-tenant isolation is decorative — policies exist but are never activated per-session. Cross-tenant data exposure risk. |
| Real PAN/account encryption | `customer-service` (`encryptPan` stub) | Sensitive PII stored effectively in clear (`ENC:`-prefixed). DPDPA/RBI exposure. |
| LIVE external integrations | `partner-integration-service` (7 adapters), `payment-service` (4 rails) | No real bureau pulls, KYC, or fund movement is possible — all throw in LIVE mode. |
| NPA / DPD classification actually runs | `lms-service` (`updateDpd` invoked by nothing) | RBI asset-classification cannot happen in a running system; NPA reporting impossible. |

### High (blocks core lending journey completion or correctness)
| Feature | Where | Why high |
|---|---|---|
| ~~Manual decision endpoints for `REFERRED`~~ **RESOLVED** | `los-service` — `POST /approve` + `POST /reject` wired; credit-check now transitions REFER → `REFERRED` | Was: referred applications were a permanent dead-end via API. |
| Penal-interest model | `lms-service` (`Loan` waterfall) | Documented but non-existent; repayment allocation silently omits penal interest. |
| Loan create/disburse REST surface | `lms-service` (`disburseLoan` dead, no `POST /v1/loans`) | Ops has no manual lever; disbursement retry after `DISBURSEMENT_FAILED` unreachable. |
| Customer profile lookup for notifications | `notification-service` consumer | Real events lack phone/email; borrower comms will be blank. |
| Ledger chart-of-accounts (multi-tenant) | `ledger-service` (3 hardcoded GL UUIDs, one tenant) | Ledger postings work for exactly one tenant; any other tenant fails. |
| Webhook signature verification | `payment-service` `POST /callbacks` | Unauthenticated callback can flip payment state. |

### Medium (operational maturity / scale)
| Feature | Where |
|---|---|
| Protobuf event serialization (schemas exist, unused) | all producers/consumers |
| `ledger_events` partition automation (only 2 months exist) | `ledger-service` |
| Integration tests / Testcontainers (zero today) | platform-wide |
| Pagination on list endpoints | customer/los (and others) |
| Rule/rule-set management API | `bre-service` |
| Response caching for partner calls (columns exist, logic missing) | `partner-integration-service` |
| DLQ handling for poison Kafka messages | all consumers |
| `RESTRUCTURED` loan schedule-regeneration behavior | `lms-service` |

### Low (cleanup / hardening)
| Feature | Where |
|---|---|
| Remove/formalize dead use-case methods (`verifyBankAccount`, `recordCreditResult`, `disburseLoan`, `confirmDisbursement`) | customer/los/lms |
| Route state transitions through the guard (NPA/MATURED bypass `canTransitionTo`) | `lms-service` |
| `DRAFT` application state is unreachable | `los-service` |
| Fragile `EMPLOYMENT_TYPE` seed `UPDATE`-after-`INSERT` | `bre-service` |
| ArchUnit tests exist only in `template-service` (copy to all 9) | platform-wide |
| OpenTelemetry instrumentation (BOM imported, unwired) | platform-wide |
| `@Retry` missing on some `@CircuitBreaker` adapters (FCM/RTGS/IMPS/NACH) | payment/notification |

---

## Aggregate Production-Readiness

| Service | Score | One-line rationale |
|---|---|---|
| bre-service | 5/10 | Cleanest & best-tested; needs rule API + auth |
| customer-service | 4/10 | Solid domain; PAN stub, 1 dead endpoint, no auth |
| los-service | 4/10 | Rich orchestration; referral approve/reject now wired; `recordCreditResult` still dead, no auth |
| payment-service | 4/10 | Lifecycle + rail selection tested; sandbox rails, no auth |
| notification-service | 4/10 | 4 channels + 35 triggers; recipient data missing, sandbox |
| lms-service | 3.5/10 | Core Kafka flow works; no penal interest, NPA never runs, dead code |
| ledger-service | 3.5/10 | Double-entry solid; single-tenant GL accounts, partition gap |
| partner-integration-service | 3/10 | Well-structured but 100% sandbox, zero tests |
| template-service | N/A | Scaffold |

**Platform overall: ~4/10 — "internally coherent, not production-deployable."** The domain layer is genuinely well-built and the event choreography is sound and now bootstrappable (post-Phase 0). But five Critical gaps (no auth, inert RLS, PII in clear, all-sandbox integrations, NPA never runs) mean the platform cannot serve a real regulated lending workload today. The most valuable next step remains a genuine end-to-end boot of all services together (blocked in this local environment). The `REFERRED` dead-end has since been closed (manual approve/reject endpoints), so the core loan journey is now completable for referred applications, not just the auto-decisioned happy path.

---

*Verified from source on 9 July 2026 by direct inspection of migrations, controllers, Kafka consumers, application services, domain models, inbound-port interfaces, `pom.xml`, and `application.yml`. Dead code was confirmed by cross-referencing every use-case interface method against its invocations in the `adapter/in` layer. No claim is inherited from prior documents.*
