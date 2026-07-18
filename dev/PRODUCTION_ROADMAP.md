# PRODUCTION_ROADMAP.md — Gap-Closure Plan to Production

**Date:** 2026-07-14
**Inputs reviewed:** `dev/PLATFORM_AUDIT.md`, `dev/PROJECT_STATUS.md`, `dev/AUTH_DESIGN.md`, `dev/RLS_DESIGN.md`, `dev/RLS_ENABLEMENT.md`, current source (`libs/`, 9 services), tests, and infra (`.github/workflows/`, `infra/helm`, `infra/terraform`, `infra/kafka/topics.yaml`, root `pom.xml`).
**Status:** Planning only — **no code changed, not committed.** Every anchor is source-verified; unverifiable items marked **[UNVERIFIED]**.

---

## 1. Production readiness score

| Dimension | % | Reasoning (source-anchored) |
|---|---|---|
| Business functionality | **55** | Onboarding→application→approval→disbursement→servicing→accrual wired (Testcontainers-proven for lms lifecycle); prepayment/collections/settlement/write-off/reporting absent |
| API completeness | **70** | 39 REST endpoints across 8 services, all Bean-Validated; a few offer/callback paths shallow |
| Core workflow completeness | **60** | Happy-path loan lifecycle runs (sandbox); collections + payment-failure depth + reporting missing |
| Database maturity | **85** | Per-service schemas, 57 indexes, RLS policies (`WITH CHECK` + fail-closed), ledger monthly partitions; **no soft-deletes**, audit fields partial |
| Kafka/event maturity | **65** | Transactional outbox + inbox idempotency solid; **no DLQ/error-handler in code**; 3 domains don't publish; ledger events unconsumed |
| Security maturity | **35** | Auth built and opted in on **7/8 services' classpaths** (oauth2 resource-server; notification excluded — no HTTP surface), but `security.enabled=false` everywhere and no IdP; **zero `@PreAuthorize`**; RLS off; plaintext Kafka; dev secrets in yaml; no CORS |
| Multi-tenancy / RLS maturity | **90** | Complete + tested; roles provisioned in dev; **enabled (config + CI-proven) on all 8 services** as of 2026-07-18 — but still **dark in every deployment** (`infra/helm` sets no profile). See Phase 2. |
| Testing maturity | **55** | 47 starter unit tests + RLS Testcontainers harness (3 ITs) **run in CI**; no e2e/contract/perf; partner=0 tests |
| Infrastructure maturity | **50** | Helm (probes/HPA/rolling), Terraform (vpc/eks/rds/redis), Jib images, Strimzi topics, CI with Testcontainers — but **no observed deploy**, no IdP, secrets externalization [UNVERIFIED] |
| **Production readiness (overall)** | **~35** | Capped by no-auth-in-path, RLS-off, sandbox integrations, no full-stack boot observed |

**Headline:** the platform is *architecturally* well-built and *operationally* dark. The distance to production is dominated by **security enablement** and **integration validation**, not by missing domain code.

---

## 2. Critical blockers (real customer, full journey)

| # | Blocker | Service | Missing component | Business impact | Effort | Dependency |
|---|---|---|---|---|---|---|
| B1 | No authentication in request path | all (starter) | `oauth2-resource-server` dep on services + IdP (Keycloak) | Anyone with an `X-Tenant-Id` header acts as any tenant | M | IdP realm |
| B2 | No operation authorization | all | `@PreAuthorize` on use-case ports + ArchUnit | Any authenticated caller can invoke any operation | L | B1 (RBAC core done) |
| B3 | Legacy tenant filter conflicts with auth | starter | gate `TenantResolutionFilter` on `security.enabled` | Enabling auth would 400 valid tokened requests (`enforce=true`) | S | — |
| B4 | RLS never proven on a booted service | per-service | canary enable + boot-level isolation test | Tenant isolation unverified outside Testcontainers | M | — |
| B5 | External integrations sandbox-only | customer, partner, payment, notification | live vendor adapters (15 throw in LIVE) | No real KYC/bureau/disbursement/collection | L | vendor creds |
| B6 | No Kafka DLQ/error-handling | starter + consumers | error-handler + DLQ routing (topics already declared) | A poison event blocks a partition; no recovery path | M | — |
| B7 | Payment failure/callback depth | payment→lms | failure reconciliation + `/callbacks` handling | Failed disbursements/collections not reliably reconciled | M | B5 |
| B8 | Collections capability absent | (new) | collections service/flow | Delinquent-loan actions impossible (DPD exists, actions don't) | L | LMS DPD (present) |
| B9 | Secrets + Kafka TLS | infra/all | externalized secrets, SASL/SSL | Plaintext brokers, dev passwords in yaml | M | infra |
| B10 | Reporting/analytics absent | (new) | reporting capability | No regulatory/business reporting | L | product scope |

---

## 3. End-to-end lifecycle validation

```
Customer → Partner → LOS → BRE → Disbursement → Ledger → LMS → Payment → Notification
```

| Step | Classification | Basis |
|---|---|---|
| **Customer onboarding** | 🟡 YELLOW | REST + outbox wired; RLS IT exists (customer); live KYC stubbed, PAN encryption stub |
| **Partner (KYC/bureau)** | 🔴 RED | Adapters throw `UnsupportedOperationException` in LIVE; **0 tests** in partner-service |
| **LOS application** | 🟡 YELLOW | Endpoints + outbox present; unit tests only, no integration validation |
| **BRE evaluation** | 🟡 YELLOW | Sync REST `credit-check`→`/bre/evaluate` wired; 1 test file; no e2e |
| **Approval → Disbursement** | 🟡 YELLOW | `approve`→`los.applications.events`→lms consumer; sandbox rails |
| **Ledger posting** | 🟡 YELLOW | `LmsEventConsumer` wired; **journal-entries events consumed by nobody in-repo** |
| **LMS loan creation** | 🟢 GREEN | `DisbursementRequestedConsumer` + schedule build; covered by `LoanLifecycleIntegrationTest` + RLS consumer/scheduler IT |
| **Payment (init/success/failure)** | 🟡 YELLOW | Endpoints + consumer wired; failure/callback depth thin; sandbox |
| **Repayment** | 🟡 YELLOW | `POST /loans/{id}/repayments` wired; prepayment RED; waterfall partial |
| **Interest accrual** | 🟢 GREEN | Scheduler `runAsSystem` + processor; RLS scheduler IT proves system-route |
| **DPD/NPA** | 🟡 YELLOW | Scheduler wired; NPA classification partial |
| **Notifications** | 🟡 YELLOW | Multi-topic consumer + retry; sandbox channels |
| **Collections** | 🔴 RED | No service/flow; only DPD aging feeds it |
| **Reports** | 🔴 RED | No capability |

**Verdict:** exactly **2 GREEN** steps (both LMS, both Testcontainers-proven). Everything else is wired-but-unvalidated (YELLOW) or absent (RED).

---

## 4. Service maturity matrix

| Service | APIs complete? | DB complete? | Kafka integrated? | Security ready? | Tests sufficient? | Prod-ready? |
|---|---|---|---|---|---|---|
| customer | ✅ (8) | ✅ | ✅ producer | ❌ | 🟡 (3, incl. RLS) | ❌ |
| partner | ✅ (4) | ✅ | ❌ none | ❌ | ❌ **0 tests** | ❌ |
| bre | 🟡 (1) | ✅ | ❌ none | ❌ | 🟡 (1) | ❌ |
| los | ✅ (8) | ✅ | ✅ producer | ❌ | 🟡 (2) | ❌ |
| disbursement (in payment+lms) | 🟡 | ✅ | ✅ | ❌ | 🟡 | ❌ |
| ledger | ✅ (4) | ✅ (partitions) | 🟡 producer, unconsumed | ❌ | 🟡 (3) | ❌ |
| lms | ✅ (4) | ✅ | ✅ 2 consumers | ❌ | ✅ (7, incl. RLS+lifecycle) | ❌ |
| payment | ✅ (6) | ✅ | ✅ consumer+producer | ❌ | 🟡 (3) | ❌ |
| notification | n/a (event) | ✅ | ✅ consumer | ❌ | 🟡 (2) | ❌ |
| template | ✅ (scaffold) | ✅ | ✅ sample | n/a | 🟡 (1, ArchUnit) | n/a |

**No service is production-ready** — every one is gated by no-auth + inert-RLS + (for external-facing) sandbox integrations.

---

## 5. Technical debt prioritization

**P0 — must fix before production**
- **Authentication** — wire oauth2 dep + IdP; make JWT authoritative.
- **Authorization** — `@PreAuthorize` on every use-case port + ArchUnit deny-by-default.
- **RLS enablement** — canary → all services; boot-level isolation proof.
- **Legacy filter conflict** — gate/retire `TenantResolutionFilter`.
- **Secrets management** — remove `originex_local` from yaml; externalize (K8s secrets / IRSA / vault).
- **TLS** — Kafka SASL/SSL; TLS termination for services [UNVERIFIED gateway].
- **Kafka DLQ handling** — error-handler + route to declared `.dlq` topics.

**P1 — should fix before beta**
- **Observability** — confirm/complete metrics+tracing pipeline (OTel deps present; wiring [UNVERIFIED]).
- **Retries** — consumer retry/backoff policy (currently none); producer already at-least-once.
- **Idempotency** — inbox exists; extend to all consumers + verify dedup keys.
- **Contract testing** — none today; add for REST + event schemas.
- **CORS** — add explicit policy if browser clients exist [UNVERIFIED clients].
- **Live integrations** — replace sandbox adapters per vendor.
- **partner-integration tests** — currently 0.

**P2 — improve after launch**
- Soft-deletes / retention policy (none today; los hard-deletes).
- Remove redundant in-consumer `TenantContextHolder.set` (4).
- Reporting/analytics, collections, prepayment/settlement/write-off.
- ArchUnit rules applied beyond template-service.
- Doc drift cleanup (`RLS_DESIGN.md` banner; retire 9-Jul snapshots).

---

## 6. Recommended implementation sequence (commit-level)

**Phase 1 — Security foundation**
- Commits: (1) gate `TenantResolutionFilter` off when `security.enabled=true`; (2) add oauth2 dep to services (opt-in); (3) dev Keycloak (compose + `realm-export.json`); (4) `@PreAuthorize` per service (one commit each) + (5) ArchUnit deny-by-default.
- Services: all 9 + starter. Tests: authz slice tests per service; ArchUnit green; PERMISSIVE observe IT.
- Success: authenticated request reaches an authorized op; unauthorized → 403; unauth → 401; header ignored in ENFORCED.

**Phase 2 — Enable RLS canary + full rollout**  — *canary and rollout **DONE** 2026-07-18 (all 8 services, CI-confirmed); deployment enablement outstanding*
- Commits: (1) **DONE** `d1af8ad` — enable `rls` profile for customer-service; (2) **DONE** `7ba6391` — JWT-driven boot-level isolation IT (`CustomerRlsJwtIsolationIntegrationTest`, CI 3/3); (3) **DONE** — rolled to the remaining seven, each a per-service flip with its own boot-level RLS IT: ledger `dbb80fc`, payment `d295293`, los `4b9ac9d`, notification `4c55123`, bre `70eeaee`, partner-integration `1614b9f`, and lms `81ab24e` (last, after migrating `LoanLifecycleIntegrationTest` to `RlsPostgresSupport` in `3f3d702`). **All eight services carry `spring.profiles.include: rls`; every enablement is proven by a CI-green integration test.**
- Prerequisite landed: `7e8ca84` — pgcrypto created at cluster init for every service DB. Without it, Flyway-as-`originex_owner` fails V1 with SQLSTATE 42501 and the service cannot boot under `rls`. Any environment enabling RLS must provision it (dev script done; **DBA/IaC still owes prod**).
- Success criterion — **met** for all eight: `current_user=originex_app`, `app.tenant_id` set per tx, cross-tenant read returns 0 rows on a booted service. Three isolation shapes emerged, each matched to the service's surface: HTTP JWT read-back (customer, ledger, payment, los), Kafka-header (notification, no HTTP surface), and write-side WITH CHECK (partner-integration); lms's full loan lifecycle runs under RLS via `LoanLifecycleIntegrationTest` + the consumer/scheduler wiring in `LmsRlsConsumerAndSchedulerIntegrationTest`.

> **Lessons that held throughout — keep them for future RLS work.**
> 1. **A test is not an enablement.** `@ActiveProfiles("rls")` activates the profile for that test's context only. This conflation caused two mis-assessments this phase (customer's commit (1), and prematurely counting `lms` as done on `1614b9f`). Check `services/<svc>/src/main/resources/` and `infra/helm/`, not the test tree.
> 2. **Header-driven RLS tests prove nothing about the authenticated path.** `TenantResolutionFilter` (`X-Tenant-Id`) exists only while `originex.security.enabled=false`; with security on, `TenantClaimResolutionFilter` reads the verified `tenant_id` claim. Each HTTP service therefore needs a JWT-driven IT. Full rationale: `dev/RLS_ENABLEMENT.md`.

> **Boot-first found eight pre-existing defects — none RLS-caused.** Every service needed at least one fix to boot or run correctly under RLS, surfaced only by booting against a real database: payment `CHAR(3)`; los + notification + partner missing outbox/inbox tables; los not-found→404; bre `KafkaTemplate` classpath; and bre's silent `@Transactional` correctness bug (booted, returned 200, produced wrong loan decisions under RLS). The last is a correctness failure, categorically more severe than the availability ones — see the severity note in `dev/RLS_ENABLEMENT.md`. (Issue #3, the lms `LoanLifecycleIntegrationTest` jsonb failure that once blocked the rollout, was resolved by `51d6246`.)

> **Deployment is still dark — the rollout being complete is NOT Phase 2 being closed.** No `infra/helm` values set `SPRING_PROFILES_ACTIVE`, so RLS is enabled in config and tests but in **no deployed environment**. Phase 2 closes only when RLS runs in a real deployment (profile set, roles + pgcrypto provisioned by DBA/IaC). That is outstanding.

**Phase 3 — Complete loan lifecycle integration**
- Commits: (1) end-to-end lifecycle IT (customer→…→notification) under Testcontainers; (2) ledger downstream consumer or documented terminal-sink decision; (3) payment failure/callback reconciliation; (4) repayment waterfall/prepayment.
- Services: customer, los, bre, lms, payment, ledger, notification. Tests: multi-container e2e.
- Success: a seeded customer completes onboarding→disbursement→repayment→accrual with asserted ledger + notification side-effects.

**Phase 4 — Kafka reliability**
- Commits: (1) shared `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` in starter; (2) DLQ topics for all event topics; (3) consumer retry/backoff; (4) DLQ replay tooling.
- Services: starter + all consumers. Tests: poison-message → DLQ IT.
- Success: a failing event lands in DLQ without blocking the partition; replayable.

**Phase 5 — Production hardening**
- Commits: (1) externalized secrets; (2) Kafka SASL/SSL; (3) observability wiring verified; (4) live-integration adapters (per vendor, flagged); (5) doc cleanup.
- Services: all + infra. Tests: security scan, load/perf smoke.
- Success: launch checklist (§9) green in a staging cluster.

---

## 7. Full E2E test strategy (minimum production confidence)

**Environment:** Testcontainers on the existing CI job (`.github/workflows/ci.yml` already runs `-Pintegration-test`). **Containers:** `postgres:16-alpine` (per-service DBs or schemas), `confluentinc/cp-kafka`, and a **Keycloak testcontainer** (new, for auth). **Roles:** provision `originex_app/system/owner` via `RlsPostgresSupport`. **Auth:** issue tenant-scoped JWTs from the Keycloak container. **Data:** seed tenant A + B, one customer each, chart-of-accounts (ledger).

| # | Test | Services | Asserts |
|---|---|---|---|
| 1 | Create tenant (realm/group + roles) | Keycloak | tokens mint with `tenant_id`, roles/scopes |
| 2 | Create customer (authenticated) | customer | 201; row visible only to tenant A under RLS |
| 3 | Submit loan application | los(+customer,+bre,+partner stubs) | application persisted; BRE decision returned |
| 4 | Approve loan | los | `los.applications.events` emitted (outbox) |
| 5 | Disburse loan | lms←los, payment | loan+schedule created; disbursement order placed |
| 6 | Verify ledger entries | ledger←lms | journal_entries + postings balanced |
| 7 | Generate repayment | lms | installment paid; `lms.loans.events` emitted |
| 8 | Run LMS scheduler (accrual + DPD) | lms (`runAsSystem`) | accrual across A+B on system route; app-route still isolated |
| 9 | Process payment success + failure | payment↔lms | success reconciles; failure routes to DLQ (Phase 4) |
| 10 | Send notification | notification | dispatch row per event; retry on transient failure |
| 11 (neg) | Cross-tenant read denied | any | tenant B token cannot read tenant A rows (RLS + authz) |
| 12 (neg) | Missing scope denied | any | `@PreAuthorize` returns 403 |

---

## 8. Architecture concerns

- **Is the microservice split correct?** Largely yes — the split maps to bounded contexts (customer/los/bre/lms/ledger/payment/notification/partner) with REST for sync and outbox/inbox for async; no service-JAR coupling. Healthy.
- **Are service boundaries healthy?** Mostly. **Concern:** "disbursement" is split across payment + lms with the orchestration implicit in events — acceptable but under-tested. **Concern:** ledger publishes events **no in-repo service consumes** — either a missing consumer (audit/reporting) or it should be a documented terminal sink.
- **Any unnecessary services?** `template-service` is a scaffold (not a runtime service) and also **holds the only ArchUnit test** — that responsibility is misplaced (arch rules should be shared/applied to real services).
- **Missing services?** **Collections**, **Reporting/Analytics**, and an **Audit** service (audit topic reserved, no consumer) — all required for a regulated lender. An **IdP** (Keycloak) is a missing platform dependency, not a service.
- **Misplaced responsibilities?** BRE invoked synchronously from LOS is fine for decisioning; consider event-emitting BRE for auditability (topic already reserved). notification correctly event-only (`enforce=false`).

---

## 9. Production launch checklist

**Application**
- [ ] Auth enabled (ENFORCED) on all services; `@PreAuthorize` coverage + ArchUnit green
- [ ] Live integration adapters (no `UnsupportedOperationException` in prod profile)
- [ ] PAN/PII encryption completed (customer stub)

**Database**
- [ ] `originex_app/system/owner` roles provisioned in prod (IaC — not found in `infra/` **[UNVERIFIED]**)
- [ ] RLS enabled + isolation verified on booted services
- [ ] Backups + PITR configured (RDS module present; policy **[UNVERIFIED]**)
- [ ] Retention/soft-delete policy defined

**Security**
- [ ] Secrets externalized (no `*_local` in yaml)
- [ ] Kafka SASL/SSL; TLS in transit end-to-end
- [ ] CORS policy (if browser clients)
- [ ] Actuator locked (already limited to health/info/prometheus/metrics)
- [ ] S2S auth (private_key_jwt/mTLS)

**Infrastructure**
- [ ] Images built/published (Jib) + scanned
- [ ] Helm values per service (probes/HPA present in chart)
- [ ] Strimzi topics + DLQ applied
- [ ] IdP (Keycloak) deployed + realm provisioned

**Monitoring**
- [ ] Metrics (Prometheus) + tracing (OTel) verified end-to-end **[UNVERIFIED]**
- [ ] Alerting on 403 rate, DLQ depth, RLS-empty-result anomalies, consumer lag

**Backup / Migration / Rollback**
- [ ] Flyway migrations idempotent + owner-role verified
- [ ] Per-service RLS rollback drill (flag/role flip) rehearsed
- [ ] Blue/green or canary deploy validated

**Operations / Compliance**
- [ ] Runbooks (RLS troubleshooting exists in `RLS_DESIGN.md §10`)
- [ ] Audit trail (audit topic has no consumer today)
- [ ] Data residency / regulatory reporting (no reporting capability)

---

## 10. Final recommendation — "8 weeks to production-ready"

**Reality check:** 8 weeks makes the platform **securely demonstrable end-to-end with real tenant isolation and a validated happy-path lifecycle** — not a fully live regulated lender (live vendors, collections, reporting exceed 8 weeks). Sequence:

**Build FIRST (Weeks 1–3) — Security + isolation, the P0 that gates everything.**
- Gate/retire legacy tenant filter; wire oauth2 + dev Keycloak; `@PreAuthorize` across services + ArchUnit; enable RLS canary → all services with boot-level isolation ITs. *Outcome:* JWT authoritative, deny-by-default, tenant isolation proven on a running service.

**Build SECOND (Weeks 4–6) — Lifecycle validation + Kafka reliability.**
- Full multi-container e2e lifecycle test (the §7 suite, incl. Keycloak); resolve ledger-consumer/terminal-sink; payment failure/callback reconciliation; shared DLQ + error-handler + consumer retries. *Outcome:* a customer completes onboarding→disbursement→repayment→accrual with asserted side-effects, and poison events fail safely.

**Build THIRD (Weeks 7–8) — Production hardening.**
- Externalized secrets + Kafka TLS; observability verified; live-integration adapters behind flags (start with disbursement + KYC); doc cleanup; staging deploy against the launch checklist. *Outcome:* deployable to staging with the checklist green; remaining live-vendor/collections/reporting tracked as post-launch.

**What is explicitly deferred past 8 weeks:** collections service, reporting/analytics, audit-event consumer, prepayment/settlement/write-off, and full live-vendor certification.

---

### Verification boundaries
- Observability pipeline, prod secret/role provisioning, backup/PITR policy, gateway/mesh, and any browser-client CORS need are **[UNVERIFIED]** from the tree.
- All percentages and week estimates are reasoned planning figures, not measured or committed schedules.
