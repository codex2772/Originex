# CLAUDE_ANALYSIS.md
## Originex — Verified Architecture Analysis & Implementation Strategy

**Date:** 9 July 2026
**Method:** Every claim below was checked against actual source (Java files, Flyway SQL, `pom.xml`, `application.yml`, `topics.yaml`, `docker-compose.yml`, CI workflows) — not inferred from `CLAUDE_HANDOVER.md`. Where the handover and the code disagree, the code wins and the discrepancy is called out explicitly in §7.
**Status:** `mvn compile` was re-run and confirmed **BUILD SUCCESS** (exit 0) on the current tree.

This document is the baseline for future work. Read §6 and §7 before touching anything.

---

## 1. Current Architecture Understanding

Originex is a Maven multi-module reactor implementing a lending platform as **9 independently deployable Spring Boot 3.4.1 / Java 21 services**, plus 3 shared/foundation modules (`proto`, `libs/common`, `libs/spring-boot-starter`). The handover's technology-stack table (§2 of the handover) is accurate on versions — Java 21, Spring Boot 3.4.1, Spring Cloud 2024.0.0, Kafka 3.7.2, Protobuf 4.29.2, gRPC 1.69.0, Resilience4j 2.2.0, Testcontainers 1.20.4, ArchUnit 1.3.0 all confirmed in root `pom.xml`.

**Correction to the module count:** the reactor has **11 modules**, not 13:
```
proto, libs/common, libs/spring-boot-starter,
services/template-service, services/customer-service, services/los-service,
services/lms-service, services/ledger-service, services/partner-integration-service,
services/payment-service, services/notification-service, services/bre-service
```
**Correction to file count:** `find . -name "*.java" -not -path "*/target/*"` returns **205 files**, not 276.

Architecturally, this is a textbook **hexagonal / ports-and-adapters** design applied uniformly:
```
domain/         — pure Java, no Spring, no JPA (enforced by ArchUnit in template-service only)
application/    — port/in (use cases), port/out (repo + external interfaces), service/ (impl)
adapter/in/     — rest/, kafka/
adapter/out/    — persistence/, rest/, {vendor}/
```
Services never depend on each other's JARs — all cross-service coupling is REST (synchronous, for request/response flows like credit-check) or Kafka via a **transactional outbox / inbox** pattern (asynchronous, for lifecycle events). This part of the handover is accurate and is the most important structural fact to internalize before writing any code: **never call `KafkaTemplate` directly** — a repo-wide grep confirms zero violations today; a new one that bypasses `OutboxPublisher` would be a regression.

The one architectural gap that matters most for anyone deploying this beyond a laptop: **there is no service mesh, API gateway, or authentication layer.** Every service trusts an unauthenticated `X-Tenant-Id` header. This is real and confirmed (§6, §7).

---

## 2. Microservice Responsibilities (Verified)

| Service | Port | Confirmed Responsibility | Verified Deviation from Handover |
|---|---|---|---|
| `customer-service` | 8081 | Customer identity, KYC lifecycle, bank accounts | `isEligibleForLoan()` exists on `Customer` but is **never called** — the KYC-before-loan rule is enforced only in `los-service`, not here. `POST /bank-accounts/{id}/verify` is documented but **does not exist** as a route (the method is implemented, just unwired). |
| `los-service` | 8082 | Application intake, credit-check orchestration, offer generation | `ApplicationStatus` has **11 states**, not ~8 — `REFERRED` and `OFFER_EXPIRED` are real states the handover omits. `DRAFT` exists in the enum but `submit()` never produces it (apps start at `SUBMITTED`). `approveAndGenerateOffer()` (manual underwriter approval) has no REST endpoint — orphaned port. |
| `bre-service` | 8088 | Configurable rule engine, offer calculator | Accurate. gRPC cleanly absent (no trace, not even a removal comment). One nuance: Flyway seeds **16 rules across 3 rule sets** (DEFAULT + `PERSONAL_LOAN_SALARIED` + an empty `PERSONAL_LOAN_SELF_EMPLOYED`), not just the 9-rule DEFAULT set the handover tables. `BREDomainTest` has 11 cases, not 10. |
| `lms-service` | 8083 | Post-disbursement lifecycle, EMI schedule, repayment | **Two significant findings** (see §7 detail): (a) the repayment waterfall in actual code is **Charges → Interest → Principal — there is no Penal Interest step**, despite it being asserted in the class Javadoc; (b) the REST API has **no create-loan endpoint and no disburse endpoint** — `disburseLoan()` is dead code, loan creation only happens via the Kafka `DisbursementRequestedConsumer`. `LoanStatus` has 11 states including undocumented `DISBURSEMENT_FAILED`, `RESTRUCTURED`, `CANCELLED`, and `NPA→ACTIVE` is a legal cure transition. |
| `ledger-service` | 8084 | Event-sourced double-entry ledger | Double-entry invariant (`SUM(debits)==SUM(credits)`) is real and correctly enforced in `JournalEntry.create()`. Account types are actually `ASSET/LIABILITY/EQUITY/REVENUE/EXPENSE` (handover says `INCOME` instead of `REVENUE` and omits `EQUITY`). **Three** hardcoded unresolved UUIDs exist (`POOL_ACCOUNT_ID`, `INTEREST_INCOME_ID`, `INTEREST_RECEIVABLE_ID`), not two — none are seeded, so postings against them throw `IllegalArgumentException: Account not found`. Additionally, `inbox_events` table is **not created** by the ledger's Flyway migration even though `LmsEventConsumer` depends on `InboxEventRepository` — a schema-validation time bomb if `ddl-auto: validate` is active. |
| `partner-integration-service` | 8085 | Anti-corruption layer for all vendor APIs | Fully accurate — all 7 adapters, all 4 endpoints, sandbox/live mode switch, PII masking, audit table all confirmed exactly as documented. |
| `payment-service` | 8086 | Disbursement rails + NACH collection | **Rail auto-selection has a real bug**: `selectRail()` checks `amount >= 200000 → RTGS` first (no upper bound), then `amount <= 500000 → IMPS`. Because every value is either `>=200000` or `<200000` (which is always `<=500000`), **the NEFT branch is unreachable dead code** in auto-selection — it can only be hit via explicit rail override. Also: NACH collection retries are capped at 2, not 3 (3 is disbursement-only). `outbox_events`/`inbox_events` tables are **entirely absent** from this service's Flyway migration (a false comment claims they're "auto-created by the starter" — `OutboxAutoConfiguration` only wires beans, it issues no DDL). |
| `notification-service` | 8087 | Multi-channel borrower communication | 35 triggers, not 33; 12 pre-seeded template rows, not 11. Correctly reads phone/email from the Kafka event payload rather than looking up the customer profile — the code itself has a comment admitting this is a sandbox shortcut. `enforce=false` for the tenant header is correctly scoped to this service only. |
| `template-service` | **8080** | Scaffold / reference implementation | Handover claims "port not assigned" — **false**, `application.yml` explicitly sets `server.port: 8080`, which **collides with Kafka UI**, also documented at `localhost:8080`. `HexagonalArchitectureTest` exists with 4 ArchUnit rules (domain-no-application, application-no-adapter, domain-no-spring, inbound-adapter-no-outbound-adapter) — note there is no separate "no JPA" rule, only "no Spring," though JPA usage would transitively be caught. |

---

## 3. Maven Module Dependency Graph (Verified)

```
proto  (protobuf-maven-plugin generates 82 classes from 6 .proto files — confirmed exact match)
  ↓
libs/common       (com.originex.common — Money, TenantContext(Holder), DomainEvent/OutboxEvent/InboxEvent,
  ↓                ErrorResponse; pagination/ package exists but is EMPTY — no implementation despite
  ↓                being documented as containing cursor-based pagination utilities)
libs/spring-boot-starter  (com.originex.starter — OutboxPublisher, OutboxPoller, TenantResolutionFilter,
  ↓                        GlobalExceptionHandler, Inbox/Outbox JPA entities, auto-configured via
  ↓                        spring.factories-style @AutoConfiguration)
services/*  (no inter-service compile dependency; 9 services, each depends on all 3 modules above)
```

No service imports another service's JAR — confirmed by inspecting every service `pom.xml`. Cross-service coupling is 100% runtime (REST for synchronous calls, Kafka+Outbox for events).

**Shared-library internals worth knowing before modifying them:**

- **`Money.java`**: `BigDecimal`-backed, `DEFAULT_SCALE=4`, `RoundingMode.HALF_EVEN`, `MathContext.DECIMAL128` for multiply, currency-safe (throws `CurrencyMismatchException` cross-currency). This is used consistently — no `double`/`float` money handling found anywhere in the audit.
- **`OutboxPublisher.publish(...)`**: annotated `@Transactional(propagation = Propagation.MANDATORY)` — will throw if called outside an existing transaction. This is why every application-service method that publishes an event is itself `@Transactional`.
- **`OutboxPoller`**: polls every 500ms (`originex.outbox.poll-interval-ms`, default 500), batch size 100, plus a daily 2am cleanup job removing published events older than 7 days. Its `resolveTopicFromEventType()` has **only 5 domain-prefix mappings**: `originex.los.*`, `originex.lms.*`, `originex.customer.*`, `originex.ledger.*`, `originex.payments.*`. **There is no mapping for `originex.bre.*`, `originex.notification.*`, or `originex.partner.*`** — any such event falls through to a fallback topic `originex.platform.unrouted-events` with a warning log. This matters directly: if you add BRE or notification-originated events later, you must add a routing case here or they silently land in the wrong topic.
- **`TenantResolutionFilter`**: reads `X-Tenant-Id` (configurable name, default confirmed), sets `TenantContextHolder` + MDC, returns HTTP 400 (RFC 7807 `missing-tenant` problem) when the header is absent **and** `originex.tenant.enforce=true` (the default, true in 8 of 9 services; `notification-service` is the sole `enforce=false` service since it's Kafka-driven, not HTTP-driven).

---

## 4. Important Business Workflows (Verified Against Code)

### Customer → Loan → Disbursement → Repayment, end to end

```
1. POST /v1/customers (customer-service:8081)
     → PAN hashed (uniqueness check) + "encrypted" via encryptPan() [STUB — see §6]
     → CustomerRegistered published

2. POST /v1/customers/{id}/kyc/aadhaar-ekyc
     → Aadhaar tokenized: SHA-256(aadhaar + tenantId.toString())  [tenant ID itself is the "salt," not a secret]
     → KYCCompleted published

3. POST /v1/loan-applications (los-service:8082) → status SUBMITTED
     (note: DRAFT state exists in the enum but is never actually reached by this flow)

4. POST /v1/loan-applications/{id}/credit-check → status IN_PROGRESS
     a. Bureau pull: partner-integration-service :8085 /v1/partner/credit-bureau/pull
        (ApplicationCreditCheckCompleted published regardless of bureau success/failure —
         only a warning is logged on bureau failure)
     b. BRE evaluation: bre-service :8088 /v1/bre/evaluate
        (if BRE itself is down, fallbackEvaluate() returns REFER_TO_UNDERWRITER, never
         auto-approve/auto-reject — a fail-safe default worth preserving in any refactor)
     c. Decision branch:
        - REJECTED        → app.reject(), ApplicationRejected published (terminal)
        - APPROVED         → app.approve() + generateOffer() (7-day expiry), ApplicationApproved published
        - REFER_TO_UNDERWRITER → app stays IN_PROGRESS, NO event published, awaits REFERRED transition
                                  (a real state the handover's diagram omits)

5. POST /v1/loan-applications/{id}/offer/accept → OFFER_ACCEPTED then immediately
     DISBURSEMENT_REQUESTED (both transitions + the DisbursementRequested event happen
     in the same acceptOffer() call — there is no intermediate manual step)

6. lms-service:8083 DisbursementRequestedConsumer (Kafka, inbox-idempotent) creates the
     Loan aggregate + calls ScheduleGenerator.generate() (reducing-balance EMI) in one
     @Transactional method — this is the ONLY way a Loan is created; there is no
     POST /v1/loans REST route despite one being documented.

7. LMS publishes LoanDisbursed → payment-service:8086 LmsPaymentEventConsumer
     selects a rail via selectRail() — verify the NEFT-dead-code bug in §7 before
     relying on "the system picks NEFT for mid-size amounts," because today it never will.

8. Payment rail completes (sandbox = immediate) → DisbursementCompleted published
     → LMS PaymentEventConsumer.confirmDisbursementByPayment() → loan status ACTIVE
     → ledger-service LmsEventConsumer attempts DR Loan Receivable / CR Pool Account
       — CR leg will throw IllegalArgumentException today because POOL_ACCOUNT_ID
       is not seeded in account_snapshots (see §6, this is a live break, not theoretical).

9. Repayment: payment-service records PaymentReceived → LMS
     allocateRepaymentFromPayment() → waterfall (Charges → Interest → Principal,
     NOT "→ Penal Interest →" as documented) → RepaymentAllocated published
     → ledger auto-posts (same POOL/INTEREST_INCOME/INTEREST_RECEIVABLE seeding gap applies)
     → notification-service DomainEventNotificationConsumer sends SMS/email using
       phone/email straight from the event payload (works only because sandbox events
       happen to carry these fields; a real customer-lookup is not implemented).
```

### BRE evaluation (accurate as documented)
Rules load by `(productCode, employmentType)` with fallback to DEFAULT rule set, are sorted ascending by `priority`, every rule is evaluated (not short-circuited), then decision is derived: any failed HARD rule → REJECTED; else any failed SOFT rule → REFER_TO_UNDERWRITER; else APPROVED. `OfferCalculator` computes EMI via the standard amortization formula, rate by credit-score band + risk-grade spread, and a simplified APR (`annualRate + processingFeeRate/tenureYears`, not a true IRR).

### NPA / DPD
`Loan.updateDpd()` sets `NPA` + `SUB_STANDARD` at 90+ DPD (only from `ACTIVE`), escalates to `DOUBTFUL` at 365+, `LOSS` at 730+. **This bypasses the `canTransitionTo()` guard** — status is assigned directly, as is the `MATURED` transition inside `allocateRepayment()` when a loan is paid off. Any future change to `LoanStatus.canTransitionTo()` needs to also account for these two direct-assignment call sites or they'll silently diverge from the guarded state machine.

---

## 5. Existing Coding Patterns (What To Imitate)

These patterns are consistent enough across the 9 services that deviating from them in new code would be a real regression, not a style nit:

- **Money**: always `Money`, never raw `BigDecimal` in domain/application code, never `double`/`float`. `HALF_EVEN` rounding, scale 4, `NUMERIC(19,4)` in Postgres.
- **Aggregates**: plain Java, no Spring/JPA annotations, static factory methods (`create`/`register`/`submit`), state transitions gated through a `transitionTo()` + `canTransitionTo()` pair — **except** the two documented bypasses in `Loan` (NPA classification, MATURED-on-payoff). Treat those as tech debt, not as license to bypass the guard elsewhere.
- **JPA entities**: separate class under `adapter/out/persistence`, with `fromDomain()`/`toDomain()` mappers, no business logic.
- **No Lombok** anywhere — explicit getters/setters throughout.
- **DTOs**: `record` types nested inside the controller class, Jakarta Bean Validation annotations, response records expose a static `from(DomainObject)`.
- **Events**: constructed as hand-built JSON via `String.format(...).getBytes(UTF_8)` and passed to `outboxPublisher.publish(aggregateType, aggregateId, eventType, tenantId, payloadBytes)` from inside an `@Transactional` method. **Not** Protobuf, despite the schemas existing in `proto/` — do not assume protobuf serialization is wired up anywhere; it isn't, in any of the 9 services.
- **Kafka consumers**: `@KafkaListener` + `@Transactional`, inbox check (`inboxRepository.existsById(eventUuid)`) before processing, `inboxRepository.save(...)` after — `DisbursementRequestedConsumer` in lms-service is the clean reference implementation; `LmsEventConsumer` (ledger) and `PaymentEventConsumer`/`LmsPaymentEventConsumer` (payment) follow the same shape.
- **Exceptions**: domain exceptions in `domain/exception/`, no `@ExceptionHandler` in individual controllers — `GlobalExceptionHandler` in the starter is the single mapping point (`IllegalArgumentException`→400, `IllegalStateException`→422/409, uncaught→500). Confirmed followed everywhere audited.
- **Resilience4j**: `@CircuitBreaker` + `@Retry` on every outbound adapter (bureau, KYC, bank, payment rails, notification channels, inter-service REST clients), with matching `resilience4j.circuitbreaker.instances`/`retry.instances` names in each service's `application.yml` — no orphaned annotation names found. Minor inconsistency: `FcmPushAdapter`, `RtgsRailAdapter`, `ImpsRailAdapter`, `NachRailAdapter` have `@CircuitBreaker` but no `@Retry` — worth normalizing if touching those classes, not urgent otherwise.
- **RLS SQL**: every service's `V1__create_*.sql` consistently uses `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` + a `USING (tenant_id = current_setting('app.tenant_id')::uuid)` policy — but see §6, this is schema-only today.

---

## 6. Potential Risks When Making Changes

Ranked by how likely they are to bite during near-term development, not by RBI-compliance severity (the handover's own §14 already ranks those well and remains accurate).

**Will break immediately / already broken:**
1. ~~**Ledger postings fail today.**~~ **RESOLVED — Phase 0 commit 1.** `POOL_ACCOUNT_ID`, `INTEREST_INCOME_ID`, `INTEREST_RECEIVABLE_ID` are now seeded in `account_snapshots` (`V2__seed_chart_of_accounts_and_inbox_table.sql`). Original finding, for history: any `LoanDisbursed`/`RepaymentAllocated`/`InterestAccrued` event reaching `ledger-service` threw `IllegalArgumentException: Account not found` for the pool/interest legs because these hardcoded UUIDs had no corresponding seed rows.
2. ~~**`ledger-service` and `payment-service` are missing `inbox_events`/`outbox_events` tables**~~ **RESOLVED — Phase 0 commits 1 and 2.** `ledger-service` got `inbox_events` in commit 1; `payment-service` got both `outbox_events` and `inbox_events` in commit 2. Note: a narrower related gap remains open — `ledger-service`'s `outbox_events` is still missing the `published_at` column the other three outbox-using services have (tracked in §9).
3. ~~**Fresh local dev is broken for 3 of the 8 "complete" services.**~~ **RESOLVED — Phase 0 commit 3.** `dev/init-scripts/init-databases.sql` now creates exactly the 9 databases the 9 real Maven-module services connect to (`originex_bre`, `originex_partner`, `originex_notification` added; the stale `originex_collections`/`originex_iam` entries for non-existent services removed).
4. ~~**Payment rail auto-selection never picks NEFT.**~~ **RESOLVED — Phase 0 commit 4.** `selectRail()` now implements a genuine three-way, non-overlapping split: `>₹5L→RTGS`, `₹2L–₹5L→IMPS`, `<₹2L→NEFT`. Original finding, for history: the old threshold ordering made the NEFT branch unreachable dead code. Note: `RtgsRailAdapter`'s own Javadoc claimed RTGS applies "for high-value transfers >= ₹2 lakhs" (no upper bound), which conflicted with this fix — confirmed with the user before implementing, and the adapter's Javadoc was corrected to match rather than silently picking a side.

**Silent/logic risk if you extend without checking:**
5. **RLS is schema-only.** `CREATE POLICY ... USING (tenant_id = current_setting('app.tenant_id')::uuid)` exists in every migration, but nothing in the codebase (`grep` for `SET LOCAL`, `StatementInspector`, `HibernateInterceptor` returns zero hits outside the SQL itself) ever sets that Postgres session variable. Today, queries presumably work only because `FORCE ROW LEVEL SECURITY` combined with an unset `current_setting` either errors or (depending on Postgres version/settings) evaluates the policy to false — worth confirming empirically before assuming multi-tenant isolation is real at the DB layer. Do not build features that lean on RLS for tenant isolation until this is wired.
6. **`OutboxPoller.resolveTopicFromEventType()` has no route for BRE, notification, or partner-integration event prefixes.** If you add an event published by one of those three services, it will land in `originex.platform.unrouted-events` unless you add a case. Check this file before adding any new event type.
7. **`originex.customer.customers.events`** — the topic `customer-service` publishes to and `notification-service` is meant to consume from — **has no Strimzi `KafkaTopic` CRD** in `infra/kafka/topics.yaml`. It presumably works locally today via Kafka's auto-topic-creation default, but will not exist in a Strimzi-managed cluster without adding the CRD.
8. **Two state transitions bypass the guarded state machine**: `Loan.updateDpd()` (NPA/DOUBTFUL/LOSS classification) and the `MATURED`-on-payoff branch of `allocateRepayment()` both assign `this.status` directly instead of going through `transitionTo()`. If `LoanStatus.canTransitionTo()` is ever tightened, these two call sites need matching updates or they'll silently produce states the guard would otherwise reject.
9. **The repayment waterfall has no penal-interest bucket in code**, only in comments. If collections/dunning work (currently unbuilt) is added later assuming a penal-interest field exists on `Loan`, it doesn't — `outstandingPenalInterest` does not exist anywhere in the class.
10. **Two REST use-cases are implemented but unreachable**: `CustomerApplicationService.verifyBankAccount()` and `LoanApplicationService.approveAndGenerateOffer()` (manual underwriter approval) both exist at the application-service layer with no controller route. If a frontend or test assumes these are callable via HTTP today, they aren't — either wire them or remove the dead code, don't assume they're already exposed.
11. **`disburseLoan()` in lms-service is fully dead code** — implemented, never called from any controller or consumer. Combined with finding #6 in this list (no `POST /v1/loans`), the entire "manual loan creation/disbursement via REST" surface the handover describes does not exist; only the Kafka-driven path is live.
12. **template-service's port (8080) collides with Kafka UI's port (8080)**, both documented at `localhost:8080` elsewhere in the handover. If template-service is ever actually run locally alongside the dev stack, one will fail to bind.
13. **No authentication anywhere** (confirmed, matches handover) — any caller with a well-formed `X-Tenant-Id` UUID can call any endpoint on any service. Do not build features that assume caller identity/authorization exists; there is none yet.

---

## 7. Where CLAUDE_HANDOVER.md Is Incomplete or Incorrect

Everything here was independently verified against source. Load-bearing production-readiness claims (RLS not wired, JSON-not-Protobuf, no Testcontainers, no auth, direct-KafkaTemplate-never-used, Resilience4j names matched, OTel BOM-only) were all **confirmed accurate** — the handover is trustworthy on architecture-level claims. The errors are concentrated in specific counts and a handful of business-logic details:

| # | Handover Claim | Actual | Impact |
|---|---|---|---|
| 1 | "13 modules" | **11 modules** | Cosmetic |
| 2 | "276 Java files" | **205 Java files** | Cosmetic |
| 3 | `pagination/` package contains "cursor-based pagination utilities" | Directory exists, **completely empty** | Misleading — don't assume pagination helpers exist |
| 4 | Ledger account types: ASSET/LIABILITY/INCOME/EXPENSE | Actually ASSET/LIABILITY/**EQUITY**/**REVENUE**/EXPENSE | Wrong enum names, missing a type |
| 5 | Ledger hardcoded UUIDs: 2 (`POOL_ACCOUNT_ID`, `INTEREST_INCOME_ID`) | **3** — also `INTEREST_RECEIVABLE_ID` | Under-scoped known-issue |
| 6 | Ledger DB tables include `inbox_events` | Migration does **not** create it; consumer depends on it anyway | Real bug, unreported |
| 7 | Payment DB tables include `outbox_events`, `inbox_events` | **Neither exists** in the migration; a comment falsely claims auto-creation | Real bug, unreported, worse than #6 |
| 8 | Payment rail selection ≥2L→RTGS, ≤5L→IMPS, else NEFT | Thresholds correct, but **NEFT is unreachable dead code** | Real logic bug, unreported |
| 9 | Payment retries: "max 3 (configurable)" | 3 for disbursements, **2** for NACH collections | Partial claim |
| 10 | LOS state machine: 8-ish states, simple chain | **11 states** incl. `REFERRED`, `OFFER_EXPIRED`; `DRAFT` never reached in practice | Missing real states |
| 11 | LMS state machine: `CREATED→PENDING_DISBURSAL→ACTIVE→NPA→{terminal}` | **11 states** incl. `DISBURSEMENT_FAILED`, `RESTRUCTURED`, `CANCELLED`; `ACTIVE` can skip `NPA` entirely; `NPA→ACTIVE` cure path exists | Oversimplified, missing states |
| 12 | Repayment waterfall: Charges → **Penal Interest** → Interest → Principal | Actual code: Charges → Interest → Principal — **no penal-interest step exists**, only in stale Javadoc | **High-impact** — business logic mismatch |
| 13 | LMS REST API: `POST /v1/loans`, `POST /v1/loans/{id}/disburse` exist | **Neither exists**; both underlying use-case methods are dead/orphaned; loan creation is Kafka-only | **High-impact** — API surface doesn't match |
| 14 | Customer REST API includes `POST /bank-accounts/{id}/verify` | **Not wired** to any route; method exists, unreachable via HTTP | API surface mismatch |
| 15 | BRE seeded rules: 9 (implies this is the complete rule inventory) | 9 in DEFAULT + 7 more in `PERSONAL_LOAN_SALARIED` = **16 rows across 3 rule sets** | Incomplete picture |
| 16 | `BREDomainTest`: "10 test cases" | **11** `@Test` methods | Cosmetic |
| 17 | Notification: "33 triggers" | **35** | Cosmetic |
| 18 | Notification: "11 pre-seeded templates" | **12** rows | Cosmetic |
| 19 | `template-service` port "not assigned" | **`server.port: 8080`** is explicitly set — and collides with Kafka UI's documented port 8080 | Real, reproducible port conflict, mis-described as absent |
| 20 | Kafka topics: "30+ topics defined" | `infra/kafka/topics.yaml` defines exactly **12** `KafkaTopic` CRDs | Overstated by ~2.5x |
| 21 | (implicit) `originex.customer.customers.events` topic exists | **No Strimzi CRD** defines it, despite being referenced by name in §4/§9 of the handover itself | Infra/code drift |
| 22 | Data architecture table: 8 databases (`originex_customer`, `..._los`, `..._lms`, `..._ledger`, `..._partner`, `..._payment`, `..._notification`, `..._bre`) | **RESOLVED — Phase 0 commit 3.** `dev/init-scripts/init-databases.sql` was originally missing `..._bre`, `..._partner`, `..._notification` and had stale `..._collections`/`..._iam` entries for services with no Maven module; now creates exactly the 9 databases the 9 real services connect to (customer, los, lms, ledger, partner, payment, notification, bre, template). | Was **high-impact**, now fixed |
| 23 | CI: "compile + unit tests + integration tests" (implies one workflow) | Two workflows exist: `ci.yml` (as described) plus `build-service.yml` (SpotBugs, OWASP dependency-check, Trivy, Gitleaks, Jib image build) | Understated CI scope, not wrong |
| 24 | `infra/kafka/topics.yaml` implicitly assumes only the 9 built services | Actually references a `collections` domain topic for a service that doesn't exist in the Maven reactor at all | Minor infra/code drift, consistent with `collections-service` being genuinely unbuilt |

**What the handover got right and shouldn't be re-litigated:** RLS-not-wired-to-Hibernate, JSON-not-Protobuf event payloads, zero Testcontainers/integration tests, zero authentication, OpenTelemetry BOM-only, transactional outbox used consistently with no direct `KafkaTemplate` calls, `TenantResolutionFilter` behavior and per-service `enforce` flags, PAN-encryption-is-a-stub (verified byte-for-byte: `"ENC:" + pan.substring(0,5) + "XXXXX"`), Aadhaar SHA-256 tokenization, partner-integration-service's 7 adapters and 4 endpoints, Resilience4j naming consistency, Money/BigDecimal/HALF_EVEN discipline, and the hexagonal package structure. These are all confirmed exactly as documented and can be relied on.

---

## 8. Recommended Order of Operations for New Work

If the next task is a feature (not a fix), sequence any nontrivial change as:

1. **Before touching a domain model**, read its `domain/model/` package fully, including the state machine enum and its `canTransitionTo()` — and check for direct-assignment bypasses like the two in `Loan.java` (§6, item 8).
2. **Before adding a Kafka event**, check `OutboxPoller.resolveTopicFromEventType()` (§3) for routing and `infra/kafka/topics.yaml` for a matching CRD — both are currently incomplete for `bre`/`notification`/`partner` domains.
3. **Before relying on ledger postings working**, seed `POOL_ACCOUNT_ID`, `INTEREST_INCOME_ID`, `INTEREST_RECEIVABLE_ID` in `account_snapshots`, or expect `IllegalArgumentException`.
4. ~~**Before running fresh local dev**, patch `dev/init-scripts/init-databases.sql`~~ **Done as of Phase 0 commit 3** — the script now creates exactly the 9 databases the real services need.
5. ~~**If the task touches payment rail selection**, decide whether NEFT's unreachability is intentional~~ **Done as of Phase 0 commit 4** — `selectRail()` now correctly reaches NEFT below ₹2L.
6. **If the task touches LMS's REST surface**, decide whether to wire `disburseLoan()`/add a create-loan endpint, or formally remove the dead code — right now the domain has capability the API doesn't expose.
7. **Never** treat RLS as enforced, protobuf as wired, or authentication as present, regardless of what other documentation in this repo (including its own README and `docs/architecture/`) implies — those describe target-state, not current-state, exactly as the handover itself warns, but the README/architecture docs are more optimistic than the handover and should not be trusted over source code.

---

## 9. Phase 0 Backlog (Live Tracking)

Items discovered during Phase 0 implementation that are explicitly deferred
— confirmed real, not fixed in the commit that found them, tracked here so
they don't get lost. Update this table as Phase 0 commits land; move an
item to "Done" with the commit hash once fixed, don't delete the row.

| Status | Task | Reason | Found in |
|---|---|---|---|
| Open | Align ledger `outbox_events` schema with shared `OutboxEventJpaEntity` | `ledger-service`'s `outbox_events` table (from V1) is missing the `published_at` column that `OutboxEventJpaEntity` maps and that `customer-service`/`los-service`/`lms-service`'s `outbox_events` tables all correctly have. With `ddl-auto: validate` set on every service (confirmed across all 9 `application.yml` files), this means `ledger-service` likely fails Hibernate schema validation at boot, independent of the account-seeding fix already shipped in commit 1. | Commit 2 (`dev/PHASE0_VERIFICATION.md`, "AFTER commit 2" section) |

---

*This document was produced by dispatching parallel source-code audits across all 9 services, the 3 shared modules, and all infra/CI/config files, then cross-checking every factual claim in CLAUDE_HANDOVER.md against the actual repository state as of 9 July 2026. No claim above is inferred from the handover alone — each was independently verified by reading the relevant source.*
