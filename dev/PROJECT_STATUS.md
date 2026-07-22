# PROJECT_STATUS.md — Originex Platform Canonical Status

**Date:** 2026-07-14
**Branch:** `worktree-deep-sleeping-ripple` @ `611f1f0` (7 auth commits ahead of `origin/worktree-deep-sleeping-ripple` @ `0874cd9`; both ahead of `origin/main` @ `146fd94`).
**Basis:** Produced by reading the current repository directly — git history, `dev/` docs, `docs/architecture/`, `libs/`, all 9 services, Flyway migrations, `application.yml`, `dev/init-scripts`, and `infra/kafka/topics.yaml`. Every claim is cited to a file/class/commit. Where a fact **cannot** be verified from source it is marked **[UNVERIFIED]**.

> This document is the canonical source of truth and **supersedes** the 9-July-2026 snapshots (`CURRENT_STATE.md`, `CLAUDE_HANDOVER.md`, `CLAUDE_ANALYSIS.md`, `BUSINESS_CAPABILITY_MATRIX.md`), which predate the RLS-runtime and authentication commits and are now partly stale (see §5, §8 of the audit / drift notes below).

---

## 1. Executive summary

| Dimension | Completion | Basis |
|---|---|---|
| **Overall platform** | **~45%** (security/platform initiative); domain layer separately more mature but capped | No auth in request path, RLS inert, external integrations sandbox-only |
| **RLS** | **~90%** | DB policies + runtime wiring + harness complete and tested; enabled on **0** services |
| **Authentication** | **~50%** | Resource-server + claim/principal machinery built & unit-tested; **not on any service classpath**, no IdP |
| **Authorization** | **~25%** | RBAC core (roles/scopes/method-security) complete; **zero `@PreAuthorize`** in services; no ownership/S2S/platform-admin |
| **Testing** | **~60%** | Strong RLS Testcontainers harness + 47 starter unit tests; thin per-service, no authz/IdP coverage |
| **Documentation** | **~65%** | Design docs strong; `RLS_DESIGN.md` status banner stale; 9-July state docs superseded |

**One-line state:** mechanisms are largely built and dark (default-off); **enforcement is turned on nowhere**. The platform still trusts an unauthenticated `X-Tenant-Id` header as its live tenant boundary.

---

## 2. Service status table

Percentages are my estimate from **verified wiring** (controllers/consumers/schedulers/migrations present and reachable), **capped platform-wide** by: sandbox-only external integrations (15 adapters throw `UnsupportedOperationException`), no authentication, and inert RLS. Deep per-capability business grading lives in `BUSINESS_CAPABILITY_MATRIX.md` (9-July, treat as reference).

| Service | Purpose | ~% | REST APIs | Kafka producers | Kafka consumers | Scheduled jobs | DB (Flyway) | RLS | Auth | Remaining work |
|---|---|---|---|---|---|---|---|---|---|---|
| **customer** (8081) | Customer identity, KYC, bank accounts | 55% | 8 (`/v1/customers` CRUD, KYC, aadhaar-ekyc, bank-accounts) | ✅ outbox (`originex.customer.customers.events`) | — | — | V1–V3 (customers, kyc_records, bank_accounts, addresses, outbox) | policies hardened, **inert** | off (superuser) | live KYC/PAN (PAN encrypt is a stub), enable RLS, `@PreAuthorize` |
| **los** (8082) | Loan application intake, state machine, offers | 55% | 8 (`/v1/loan-applications`: create, docs, credit-check, approve, reject, offer/accept, delete) | ✅ outbox (`originex.los.applications.events`) | — | — | V1–V2 (loan_applications, loan_offers, application_documents, outbox) | hardened, inert | off | wire remaining offer paths, RLS enable, `@PreAuthorize` |
| **lms** (8083) | Loan lifecycle, schedule, repayment, accrual, DPD | 55% | 4 (`/v1/loans`: get, schedule, repayments, foreclosure-quote) | ✅ outbox (`originex.lms.loans.events`) | ✅ 2: `DisbursementRequestedConsumer`←los events, `PaymentEventConsumer`←payments events | ✅ 2: `InterestAccrualService` (cron 00:30 IST), `DpdAgingService` (cron 01:00 IST) | V1–V4 (loans, installments, disbursements, loan_charges, inbox/outbox) | hardened + **gap tables closed** (V3), inert | off | RLS enable, `@PreAuthorize`, remove redundant in-consumer `set` |
| **ledger** (8084) | Double-entry event-sourced accounting | 50% | 4 (`/v1/ledger`: accounts, get, journal-entries, reverse) | ✅ outbox (`originex.ledger.journal-entries.events`) — **no in-repo consumer** | ✅ 1: `LmsEventConsumer`←lms events | — | V1–V5 (account_snapshots, journal_entries, postings, ledger_events partitions, inbox/outbox) | hardened, inert | off | verify downstream consumer, RLS enable, `@PreAuthorize` |
| **payment** (8085) | Disbursement (NEFT/RTGS/IMPS), NACH collection | 50% | 6 (`/v1/payments`: disbursements, get, inbound, callbacks, mandates, collect) | ✅ outbox (`originex.payments.orders.events`) | ✅ 1: `LmsPaymentEventConsumer`←lms events | ✅ 1: `retryFailedPayments` (fixedDelay 5 min) | V1–V3 (payment_orders, nach_mandates, inbox/outbox) | hardened, inert | off | live rails, RLS enable, `@PreAuthorize` |
| **bre** (8086) | Rules engine / auto-decisioning | 55% | 1 (`POST /v1/bre/evaluate`) | ❌ none (topic reserved, unused) | — | — | V1–V2 (bre_rule_sets, bre_rules) | hardened, inert | off | outbox publish (optional), RLS enable, tests (1 file) |
| **partner-integration** (8087) | Bureau/Aadhaar/PAN/bank verification | 45% | 4 (`/v1/partner`: credit-bureau, aadhaar, pan, bank-account) | ❌ none | — | — | V1–V2 (integration_requests) | hardened, inert | off | **0 tests**, live vendors (sandbox stubs), RLS enable |
| **notification** (8088) | SMS/Email/WhatsApp/Push dispatch | 50% | none (event-driven) | ❌ none | ✅ 1: `DomainEventNotificationConsumer`←customer/los/lms/payments events | ✅ 1: `retryFailed` (fixedDelay 10 min) | V1–V2 (notification_requests, notification_templates, channel_dispatches, idempotency) | hardened, inert; `tenant.enforce=false` (correct) | **ceremonial** — machine ctx for uniformity, **NO authz gate** (pure side-effect sink: no use-case port, no `@PreAuthorize`); consumer proven unaffected under `security.enabled=true`. Do **not** score as "enforced". | live channels, RLS enable (relies solely on interceptor) |
| **template** (n/a) | Scaffold for new services | n/a | 4 sample CRUD | ✅ sample `KafkaEventPublisherAdapter` | — | — | V1–V2 (samples) | hardened | off | reference module only; **only ArchUnit test lives here** |

*Shared modules:* `proto` (protobuf — generated, **referenced by zero service code** per prior audit; unverified re-check this pass), `libs/common` (context holders, Money, events), `libs/spring-boot-starter` (auth + RLS + outbox + tenant), `libs/test-support` (RLS Testcontainers harness). Reactor = **13 modules** (4 foundation + 9 services).

---

## 3. End-to-end business flows

Status legend: ✅ wired end-to-end (sandbox infra acceptable) · 🟡 partial/unwired path · 🔴 absent.

| # | Flow | Services | Status | Missing pieces | Automated tests |
|---|---|---|---|---|---|
| 1 | **Customer onboarding** | customer (+partner for KYC) | 🟡 | Live Aadhaar/PAN/bank (sandbox stubs throw in LIVE); PAN encryption stub | `CustomerHttpRlsIsolationIntegrationTest`, `CustomerRlsSemanticsIntegrationTest`, unit |
| 2 | **Loan application** | los (+customer, +partner) | 🟡 | Some offer paths; live bureau | los unit tests (2 files) |
| 3 | **BRE evaluation** | bre ← los (sync REST `/credit-check` → BRE) | 🟡 | No event publication (reserved topic unused) | bre (1 test file) |
| 4 | **Approval** | los (`/approve`,`/reject`) | 🟡 | Approval → disbursement handoff via `originex.los.applications.events` | los tests |
| 5 | **Disbursement** | los→(event)→lms→payment | 🟡 | Live rails; end-to-end only via Testcontainers | `LoanLifecycleIntegrationTest` (lms) |
| 6 | **LMS loan creation** | lms (`DisbursementRequestedConsumer`←los events) | ✅ (sandbox) | — | `LmsRlsConsumerAndSchedulerIntegrationTest`, `LoanLifecycleIntegrationTest` |
| 7 | **Repayment** | lms (`POST /{loanId}/repayments`) → payment events | 🟡 | Prepayment 🔴 (per matrix); waterfall partial | lms tests |
| 8 | **Ledger posting** | ledger (`LmsEventConsumer`←lms events) | 🟡 | Publishes journal-entries events consumed by **nobody in-repo** | ledger tests (3 files) |
| 9 | **Interest accrual** | lms `InterestAccrualService` (daily 00:30, `runAsSystem`) | ✅ | — | `LmsRlsConsumerAndSchedulerIntegrationTest` |
| 10 | **DPD/NPA** | lms `DpdAgingService` (daily 01:00, `runAsSystem`) | 🟡 | NPA classification partial (per matrix); collections 🔴 | lms scheduler IT |
| 11 | **Notifications** | notification ← customer/los/lms/payments events; `retryFailed` (10 min) | 🟡 | Live channels (sandbox); no outbox publish | notification tests (2 files) |

**Kafka topology (verified):** customer→`customer.customers.events`; los→`los.applications.events`→(lms disbursement consumer, notification); lms→`lms.loans.events`→(ledger, payment, notification); payment→`payments.orders.events`→(lms, notification); ledger→`ledger.journal-entries.events`→(no in-repo consumer). `bre`, `partner`, `notification` do **not** publish via outbox (topics reserved in `infra/kafka/topics.yaml`). DLQ topics declared for lms/payments.

---

## 4. API inventory summary

**REST (all under `/v1`, tenant via `X-Tenant-Id` header today):**
- **customer** (8): `POST /customers`, `GET /customers/{id}`, `GET /customers/{id}/bank-accounts/primary`, `PUT /customers/{id}`, `POST /customers/{id}/kyc`, `POST /customers/{id}/kyc/{kycId}/complete`, `POST /customers/{id}/kyc/aadhaar-ekyc`, `POST /customers/{id}/bank-accounts`
- **los** (8): `POST /loan-applications`, `GET /{id}`, `POST /{id}/documents`, `POST /{id}/credit-check`, `POST /{id}/approve`, `POST /{id}/reject`, `POST /{id}/offer/accept`, `DELETE /{id}`
- **lms** (4): `GET /loans/{id}`, `GET /{id}/repayment-schedule`, `POST /{id}/repayments`, `POST /{id}/foreclosure-quote`
- **ledger** (4): `POST /ledger/accounts`, `GET /accounts/{id}`, `POST /journal-entries`, `POST /journal-entries/{id}/reverse`
- **payment** (6): `POST /payments/disbursements`, `GET /{id}`, `POST /inbound`, `POST /callbacks`, `POST /mandates`, `POST /mandates/{id}/collect`
- **partner** (4): `POST /partner/credit-bureau/pull`, `/aadhaar/verify`, `/pan/verify`, `/bank-account/verify`
- **bre** (1): `POST /bre/evaluate`
- **template** (4): sample CRUD (scaffold)
- **notification**: none (event-driven)

**Kafka interfaces:** 6 outbox producers (customer, los, lms, ledger, payment, template) via `OutboxPublisher`/`OutboxPoller`; 5 consumers (`@KafkaListener`) as mapped in §3. No direct `KafkaTemplate` use outside the starter. Serialization: `byte[]` payloads with headers (`event_id`, `event_type`, `tenant_id`).

---

## 5. Architecture status

- **Hexagonal (ports & adapters):** ✅ uniformly applied (`domain/`, `application/port/{in,out}`, `application/service`, `adapter/{in,out}`). Enforced by ArchUnit **only in `template-service`** (`HexagonalArchitectureTest`) — not applied to real services.
- **Shared libraries:** ✅ `libs/common` (context holders, Money, events), `libs/spring-boot-starter` (auto-config for tenant/outbox/RLS/security), `libs/test-support` (RLS harness), `proto` (generated; usage by services **[UNVERIFIED this pass]**, prior audit found zero).
- **RLS:** ✅ complete + dark. `RlsTenantTransactionManager` (LOCAL `set_config`), `TenantRoutingDataSource` (app/system, fail-loud), `RlsKafkaAutoConfiguration`+`TenantRecordInterceptor`, all gated `originex.rls.enabled=true`. Policies fail-closed (`current_setting('app.tenant_id', true)`) + `WITH CHECK` across all 9 services. Roles in `dev/init-scripts/init-databases.sql`. **Enabled on 0 services.**
- **Authentication:** 🟡 built in starter (`SecurityAutoConfiguration`: RS256-allowlist decoder, issuer+audience validators, ENFORCED/PERMISSIVE chains; `TenantClaimResolutionFilter`; `JwtPrincipalResolver`). **`oauth2-resource-server` is optional and declared only in the starter pom — no service depends on it**, so `@ConditionalOnClass(JwtDecoder)` backs the whole chain off everywhere. No IdP (`dev/keycloak/` absent; no Keycloak in `dev/docker-compose.yml`).
- **Authorization:** 🟡 RBAC core only. `OriginexRoles` (9), `OriginexScopes` (23), `KeycloakRealmRoleConverter`, nested `@EnableMethodSecurity`. **Zero `@PreAuthorize`/ownership/S2S/platform-admin in services.**
- **Observability:** 🟡 Micrometer counters in security filter (`tenant_mismatch`, `permissive.header_fallback`); MDC `tenantId`/`sub`. Full metrics/tracing stack **[UNVERIFIED]** (see `docs/architecture/11-observability`).
- **Testing:** 🟡 47 starter unit tests; RLS Testcontainers harness + 3 `@Tag("rls")` ITs; per-service coverage thin (partner = 0 tests).

---

## 6. Remaining work

**🔴 Critical**
- Resolve `TenantResolutionFilter` vs auth conflict + retire `X-Tenant-Id`: the legacy header filter is registered unconditionally at an order earlier than Spring Security and, with `tenant.enforce=true` (8/9 services), would reject a valid tokened-but-headerless request with **400** before auth runs. Blocks enabling auth.
- Prove RLS enablement on ≥1 service end-to-end on a **booted** service (not only Testcontainers).

**🟠 High**
- Apply `@PreAuthorize` across all services + ArchUnit deny-by-default rule (RBAC mechanism is inert without it — any authenticated caller can reach any operation).
- Stand up dev Keycloak (compose + realm export) and wire `oauth2-resource-server` into services (PERMISSIVE observe).

**🟡 Medium**
- Ownership authorization (`@access`, D2) for CUSTOMER principals.
- Extend RLS ITs to los/ledger/payment/notification/bre/partner.
- Fix documentation drift (`RLS_DESIGN.md` "not implemented" banner; refresh 9-July state docs).
- Add tests to partner-integration (currently 0).

**🟢 Nice-to-have**
- Service-to-service auth (private_key_jwt/mTLS) + audited platform-admin cross-tenant route.
- Remove now-redundant in-consumer `TenantContextHolder.set` (4 consumers).
- Wire outbox publication for bre/partner/notification (topics already reserved).

---

## 7. Merge readiness (commit groups on this branch)

| Group | Commits | State | Notes |
|---|---|---|---|
| **Domain/business baseline** | `146fd94` | **Merged** | On `origin/main` |
| **RLS Phase 0** (roles, tx-manager, routing DS, Kafka interceptor, schedulers→system, policy migrations, shared profile, ITs, docs) | `8abbddd`…`0874cd9` | **Ready to merge** (needs review) | Pushed to `origin/worktree-deep-sleeping-ripple`; fully gated off + tested; minor doc-banner fix outstanding |
| **Auth: OAuth2 foundation** | `8a67ba6` | **Needs review** | Local/unpushed; gated off |
| **Auth: tenant/subject from claims** | `80cabea` | **Needs review** | Local/unpushed; gated off |
| **Auth: principal model** | `0fd7a69` | **Needs review** | Local/unpushed; gated off |
| **Auth: PERMISSIVE dual-mode + observability** | `3f9bffb` | **Needs review** | Local/unpushed; gated off |
| **Auth: RBAC core** | `611f1f0` | **Needs review** | Local/unpushed; 47 tests green; **blocked from *enablement*** by no-oauth-dep + no IdP + legacy-filter conflict |

No commit group is **Experimental**; none is safe to *enable* in production yet. All auth commits are review-ready but functionally dark.

---

## 8. Production-readiness checklist

Everything below is **required before a production deployment** and is currently **unmet** unless noted.

**Security**
- [ ] Authentication enabled in the request path (oauth2 dep on services + real IdP).
- [ ] `X-Tenant-Id` header trust retired / legacy filter gated (resolve 400 conflict).
- [ ] `@PreAuthorize` on every use-case port + ArchUnit deny-by-default green.
- [ ] Ownership (subject-scope) enforcement for CUSTOMER principals.
- [ ] Service-to-service authentication (private_key_jwt/mTLS).
- [ ] Audited platform-admin cross-tenant route.
- [x] Fail-closed posture (RLS NULL→zero rows; fail-loud config) — implemented.
- [x] Default-off invariant — implemented/verified.

**RLS**
- [ ] Enabled per service in canary order with isolation proof on a booted service.
- [ ] RLS ITs for all 9 services (currently customer + lms).
- [ ] Three roles (owner/system/app) provisioned in **non-dev** environments (IaC — not found in `infra/` **[UNVERIFIED]**).
- [ ] Production `RLS_*` passwords set via secret store (defaults are `*_local`).

**Platform / Ops**
- [ ] Full multi-service boot observed (documented local Docker blocker; **never observed** — `CURRENT_STATE.md`).
- [ ] External integrations moved from sandbox to live (15 adapters throw in LIVE mode).
- [ ] Kafka topics applied (Strimzi CRDs in `infra/kafka/topics.yaml`) + DLQ consumers.
- [ ] Observability stack (metrics/tracing/log aggregation) verified end-to-end **[UNVERIFIED]**.
- [ ] API gateway / mesh in front of services **[UNVERIFIED — not found]**.
- [ ] PAN/PII encryption completed (currently a stub in customer-service).
- [ ] CI runs RLS + integration suites (`-Pintegration-test -Dgroups=rls`) on every PR.

**Documentation**
- [ ] `RLS_DESIGN.md` status banner corrected ("Design only — no code written" is false).
- [ ] 9-July state docs refreshed or explicitly marked superseded by this file.

---

### Verification boundaries (stated, not guessed)
- Runtime behavior of a fully-booted multi-service stack: **not observed** (documented Docker blocker).
- Non-dev role provisioning / gateway / observability wiring: **not verified** from `infra/` in this pass.
- `proto` usage by service code and exhaustive per-capability business grading: carried from prior audits; **not fully re-verified** this pass.
- All percentages are reasoned estimates from verified wiring, not measured coverage.
