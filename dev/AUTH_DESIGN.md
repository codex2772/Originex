# Originex — Authentication & Authorization Design

**Status:** proposal (no code yet). Companion to `dev/RLS_DESIGN.md` and
`dev/RLS_ENABLEMENT.md`. This document designs the perimeter and identity layer
that makes row-level security *meaningful*: RLS isolates tenants at the database,
but only if the tenant context is derived from an **authenticated, authorized**
principal rather than a self-asserted header.

Grounded in the repository at HEAD `0874cd9`:
- No Spring Security is present in any module.
- `TenantResolutionFilter` (shared starter, order `HIGHEST_PRECEDENCE+10`) trusts
  the `X-Tenant-Id` header unconditionally and sets `TenantContextHolder`.
- `TenantContext` carries `{tenantId, tenantSlug, tier}` — **no user/principal**.
- Internal service-to-service calls are synchronous REST (`RestClient`) that
  forward `X-Tenant-Id` with **no** caller authentication (e.g.
  `los-service/.../CustomerServiceAdapter`).
- Kafka `tenant_id` headers are set by `OutboxPoller` from the **persisted**
  outbox row and consumed via `TenantRecordInterceptor` (trustworthy provenance).
- RLS enforcement is fully built but gated off (`originex.rls.enabled=false`).

---

## 1. Current security posture and gaps

| Area | Today | Gap |
|---|---|---|
| Authentication (inbound) | none | Any client reaches any endpoint unauthenticated |
| Tenant establishment | `X-Tenant-Id` header, trusted | **Tenant impersonation** — a caller reads any tenant by setting the header |
| Authorization | none | No RBAC; any caller can invoke any operation |
| Service-to-service | REST forwarding `X-Tenant-Id`, no creds | A reachable service can call any other as any tenant |
| Kafka provenance | `tenant_id` from persisted outbox row | Trustworthy **iff** the write that produced it was authenticated (currently it is not) |
| RLS | built, disabled | Depth control with **no perimeter** — enabling it alone does not stop header-based impersonation |
| Auditability | `tenantId` in MDC only | No authenticated principal in logs/audit trail |

**Core conclusion:** the platform has a strong *depth* control (RLS) and no
*perimeter* control. The single highest-value fix is to establish an
authenticated principal and derive `TenantContext` from its verified claims,
eliminating trust in `X-Tenant-Id`. This must precede (or land with) RLS
enablement, or RLS provides false assurance.

---

## 2. Threat model

Assets: tenant PII (KYC, Aadhaar/PAN), loan and payment records, GL postings —
a regulated (RBI) Indian lending platform. Multi-tenant (co-lending / BaaS).

| # | Threat | Today | After this design |
|---|---|---|---|
| T1 | **Cross-tenant read via header spoofing** | Trivial: set `X-Tenant-Id: <victim>` | Tenant comes from a signed JWT claim; header ignored |
| T2 | **Unauthenticated access to any endpoint** | Open | Resource server rejects missing/invalid tokens (401) |
| T3 | **Privilege escalation** (borrower invokes admin ops) | No roles | RBAC via token scopes/roles + method security |
| T4 | **Lateral movement between services** | Any service→any service, any tenant | S2S client-credentials tokens with per-service scopes |
| T5 | **Token forgery / tampering** | n/a | RS256 signature + `iss`/`aud`/`exp` validation against IdP JWKS |
| T6 | **Token replay / theft** | n/a | Short TTL, TLS-only, audience binding; optional DPoP/mTLS (future) |
| T7 | **Kafka event tenant forgery** | Producer sets header from DB row | Provenance preserved; consumers validate presence + (optional) signed origin |
| T8 | **Key compromise / rotation failure** | n/a | JWKS rotation, short cache TTL, kid-based multi-key |
| T9 | **Repudiation** (who did what) | Only tenant in logs | Authenticated `sub`/`act` in audit + MDC |

Out of scope (infra/platform, referenced not designed here): TLS termination,
WAF, DDoS, broker transport auth (SASL/mTLS), secret storage backend.

---

## 3. Authentication architecture

### 3.1 Shape: per-service OAuth2 Resource Server (Spring Security 6)

Each service becomes a **stateless OAuth2 Resource Server** validating a bearer
JWT on every request. Rationale over a single gateway-only check:
- Defense in depth — every service independently rejects invalid tokens, so an
  internal caller cannot bypass the edge.
- Matches the existing per-service starter model (auto-configuration in
  `libs/spring-boot-starter`), so it ships uniformly and dark, exactly like RLS.
- A gateway is still recommended (§7) for TLS, routing, and coarse rate-limiting,
  but it is **not** the sole trust boundary.

### 3.2 Wiring (shared starter, gated)

New `SecurityAutoConfiguration` in `libs/spring-boot-starter`, gated by
`originex.security.enabled` (default `false`), contributing:
- `spring-boot-starter-oauth2-resource-server` dependency (added to the starter).
- A `SecurityFilterChain` that: requires authentication for all non-actuator
  routes; permits `/actuator/health|info|prometheus`; is `STATELESS`; disables
  CSRF (token-based, no cookies); sets a custom `JwtAuthenticationConverter`.
- A `JwtDecoder` built from the IdP's JWKS URI (`issuer-uri` autodiscovery).
- Re-ordering: the Spring Security filter chain runs **before**
  `TenantResolutionFilter`, so the JWT is validated and the principal established
  before tenant context is derived (§5).

When `originex.security.enabled=false`, none of this is contributed and behavior
is identical to today (header-trust) — the same dark-ship discipline as RLS.

### 3.3 JWT validation

- **Algorithm:** RS256 (asymmetric) — services never hold a signing secret; they
  verify with the IdP's public keys.
- **Key discovery:** OIDC `issuer-uri` → `.well-known/openid-configuration` →
  `jwks_uri`. Keys cached in-process; multi-`kid` supported for rotation.
- **Validators:** signature, `exp`/`nbf` (with bounded clock skew, e.g. 30s),
  `iss` (exact match), **`aud`** (must include `originex-api`), and a custom
  validator asserting the tenant claim is present and a well-formed UUID.
- **Offline:** validation is fully offline (no per-request IdP call) → no latency
  or availability coupling to the IdP on the hot path.
- **Converter:** `JwtAuthenticationConverter` maps claims → `GrantedAuthority`
  (§4) and exposes the tenant claim for §5.

### 3.4 OIDC provider options

Compared in §14. Recommendation: **self-hosted Keycloak**.

---

## 4. Authorization model

### 4.1 RBAC

Two authority sources in the JWT, both mapped to Spring authorities:
- **Roles** (coarse, tenant-scoped): `ROLE_TENANT_ADMIN`, `ROLE_LOAN_OFFICER`,
  `ROLE_CUSTOMER`, `ROLE_AUDITOR`, plus platform-level `ROLE_PLATFORM_ADMIN`.
- **Scopes** (fine, capability): e.g. `loans:read`, `loans:disburse`,
  `payments:initiate`, `customers:write`, `kyc:submit`.

Enforcement: method security (`@EnableMethodSecurity` + `@PreAuthorize`) on
application-service/use-case entry points, which is where domain operations
already converge (hexagonal ports). URL-pattern rules cover coarse cases; method
security covers the business rules (e.g. only `loans:disburse` may initiate a
disbursement). A starter-provided role/scope taxonomy keeps services consistent.

Role → scope mapping is owned by the IdP (composite roles / client scopes), so
the taxonomy is centrally administered, not hard-coded per service.

### 4.2 Service-to-service authentication

Internal REST adapters (customer→partner, los→{bre,customer,partner}) move from
"forward `X-Tenant-Id`, no creds" to **OAuth2 client-credentials**:
- Each service is an OAuth2 **client** with its own `client_id`/secret and a set
  of granted scopes (e.g. `los-service` may hold `customers:read`, `bre:evaluate`).
- The caller obtains a short-lived **service token** (client-credentials grant),
  caches it until near expiry, and presents it as `Authorization: Bearer` on
  outbound calls. Spring Security's `OAuth2AuthorizedClientManager` +
  `RestClient`/`WebClient` interceptor handles acquisition/refresh transparently.
- The callee validates it as a resource server (§3) — same JWT path.

Tenant on S2S calls: the **caller propagates the tenant as a validated on-behalf
context**, not a trusted header. Two acceptable patterns (decision D4):
- (a) **Token exchange / act-as**: the caller requests a token carrying the
  end-user's tenant (`act`/`may_act` or a delegated `tenant_id` claim), so the
  callee derives tenant from the token exactly as for end-user calls.
- (b) **Signed context**: keep `X-Tenant-Id` for propagation but only accept it
  from an authenticated S2S principal *and* cross-check it against the token's
  allowed tenants. (a) is preferred; (b) is a pragmatic interim.

### 4.3 Machine identities

- One IdP client per deployable service (`svc-los`, `svc-customer`, …), each with
  least-privilege scopes.
- Secrets live in the platform secret store (§11), never in `application.yml`.
- Machine tokens carry a `client_id`/`azp` and no `tenant_id` unless acting for a
  tenant (then via token exchange). Consumers distinguish user vs machine
  principals by the presence of `sub` vs client-only tokens.

---

## 5. Tenant resolution (eliminate trust in `X-Tenant-Id`)

**Today:** `TenantResolutionFilter` reads `X-Tenant-Id` and sets
`TenantContextHolder`. Trust boundary = the client.

**Target:** derive tenant from the **verified JWT claim**.

- The IdP mints a `tenant_id` claim (recommended custom claim
  `https://originex.io/tenant_id`, UUID) from the user's group/attribute (or, for
  machine tokens acting for a tenant, from token exchange).
- A new/refactored resolver runs **after** the Security filter chain: it reads the
  authenticated `Jwt`/`Authentication`, extracts `tenant_id` (and optionally
  `tenant_slug`, `tier`), and populates `TenantContextHolder` and MDC.
- `X-Tenant-Id` is **ignored** when `originex.security.enabled=true`. During
  migration it may be honored only in a permissive dual-mode (§8) and only when no
  token is present; once enforced, it is dropped entirely (or, stricter, a request
  presenting both a token and a mismatching header is rejected).
- `TenantContext` gains an authenticated principal (`subject`, roles) so
  downstream code and audit can see *who*, not just *which tenant*.

### 5.1 Interaction with RLS (the whole point)

The chain becomes end-to-end trustworthy with **no change to the RLS plumbing**:

```
JWT (verified)  →  tenant_id claim  →  TenantContextHolder
      →  RlsTenantTransactionManager.doBegin: SET LOCAL app.tenant_id = <claim>
      →  Postgres RLS policies filter every row for that tenant
```

Because RLS reads `TenantContextHolder` regardless of source, switching the source
from *trusted header* to *verified claim* upgrades RLS from "isolates given a
trustworthy tenant" to "isolates a tenant that cannot be forged." This is why
auth should land **before or with** RLS enablement: the RLS canary then runs on
authenticated context and needs no re-validation.

Ordering guarantee (critical, mirrors the RLS filter/interceptor discipline):
principal established → tenant derived → **then** the `@Transactional` boundary
opens and `SET LOCAL app.tenant_id` runs. The resolver must sit between the
Security chain and the controller/service, before any transaction begins.

---

## 6. Kafka propagation

Producers already attach `tenant_id` from the **persisted** outbox row
(`OutboxPoller`), so provenance is inherently trustworthy once the *write* that
created the row was authenticated and authorized (which §5 guarantees). The
consumer path (`TenantRecordInterceptor`) already sets tenant context before the
listener transaction.

Changes:
- **Authenticated producer origin (audit):** add an optional `act_by` /
  `principal` header carrying the authenticated `sub`/`client_id` that caused the
  event, for cross-service audit and lineage (not for authorization decisions).
- **Consumer validation:** `TenantRecordInterceptor` already fails closed on a
  missing/blank `tenant_id`; extend it to reject a malformed UUID and (optionally)
  to verify a signed origin claim if inter-cluster trust is ever required.
- **No per-message JWT:** events are internal and their tenant is DB-derived;
  attaching user JWTs to events would be a replay/PII hazard. Authorization
  happens at the write boundary, not on event consumption.
- **Broker transport auth** (SASL/SCRAM or mTLS) is infra-level and complementary;
  it authenticates *clients to the broker*, not tenants, and is tracked separately.

---

## 7. Internal service communication

- **REST (current):** `RestClient` adapters adopt client-credentials tokens (§4.2)
  via a shared, starter-provided authorized-client interceptor. Tenant travels as
  a validated on-behalf context (D4), never a bare trusted header.
- **Gateway (recommended, new):** an edge gateway (Spring Cloud Gateway or the
  platform's existing ingress) terminates TLS, does coarse rate-limiting/routing,
  and *may* pre-validate tokens — but per-service resource servers remain the
  authoritative check (defense in depth).
- **gRPC:** scaffolded in the pom but unused; if adopted later, the same JWT is
  carried in metadata and validated by a server interceptor. Out of scope now.
- **East-west TLS/mTLS:** recommended at the mesh/infra layer; app-layer JWT is
  the identity/authorization layer on top.

---

## 8. Migration strategy

Same dark-ship discipline as RLS: a single flag, staged rollout, always
reversible.

**Flag:** `originex.security.enabled` (default `false`) + a mode enum
`originex.security.mode = DISABLED | PERMISSIVE | ENFORCED`.

| Stage | Mode | Behavior | Backward compatibility |
|---|---|---|---|
| 0 | DISABLED | Resource server not wired; header-trust as today | Full — no change |
| 1 | PERMISSIVE (observe) | Validate JWT **if present**; derive tenant from claim; else fall back to `X-Tenant-Id`. Log auth outcome + any header/claim mismatch. No rejections. | Full — legacy callers still work |
| 2 | PERMISSIVE (require-auth) | Reject unauthenticated requests (401); tenant strictly from claim; `X-Tenant-Id` ignored | Callers must send tokens; internal S2S switched to client-credentials first |
| 3 | ENFORCED | RBAC enforced (`@PreAuthorize`); least-privilege scopes required | Roles/scopes provisioned in IdP |
| 4 | ENFORCED + RLS | Turn on `originex.rls.enabled` per service, now on authenticated tenant context | Coordinated with the RLS canary (§ RLS_ENABLEMENT) |

- **Feature flags** are per-service (config), so rollout is service-by-service and
  independently reversible.
- **Backward compatibility:** stage 1 is a pure observability stage — dashboards
  confirm every caller sends valid tokens and claim-derived tenant matches the
  legacy header *before* any rejection. Internal S2S is migrated to
  client-credentials during stage 1→2 so services keep working when the header is
  dropped.
- **Coupling with RLS:** enable auth (to at least stage 2, tenant-from-claim)
  **before** flipping RLS for a given service, so the RLS canary runs on
  authenticated context.

---

## 9. Local development

- **Keycloak in `dev/docker-compose.yml`**: a pre-seeded realm (`originex`) with
  clients (`originex-api`, one `svc-*` per service), a couple of test tenants as
  groups/attributes, and seed users per role. Realm exported to
  `dev/keycloak/realm-export.json` for reproducible startup.
- **Frictionless default:** local runs default to `originex.security.mode=DISABLED`
  (unchanged DX) or `PERMISSIVE`; a documented `SPRING_PROFILES_ACTIVE=...,auth`
  turns it on against local Keycloak, mirroring the `rls` profile switch.
- **Token helper:** a `dev/scripts/token.sh` to fetch a user or service token from
  local Keycloak for `curl`/Postman.
- Interplay with RLS local dev: the `auth` + `rls` profiles compose — claim-derived
  tenant feeds `app.tenant_id`.

---

## 10. CI / Testcontainers strategy

Two layers, mirroring the RLS test approach and reusing `libs/test-support`:

- **Unit / slice (fast, no IdP):** mint **self-signed JWTs** in-test against a
  static test RSA keypair, and point the resource server at a static JWKS (local
  in-memory `JwtDecoder` or a `WireMock` JWKS endpoint). A new
  `test-support` helper (`AuthTestTokens`) issues tokens with arbitrary
  tenant/roles/scopes. This covers the vast majority of authz assertions without a
  container and stays out of the offline-fragile broker deps.
- **Integration (real IdP):** a **Keycloak Testcontainer** with the exported realm
  for a handful of end-to-end tests (token issuance → resource-server validation →
  tenant-from-claim → RLS isolation). Tagged `@Tag("auth")` (and reuse
  `@Tag("rls")` where combined) so it runs under the existing
  `-Pintegration-test` failsafe profile and can be selected via `-Dgroups`.
- **Combined RLS+auth IT:** extend the existing role-aware harness so the tenant
  is supplied by a signed JWT rather than a raw `set_config`, proving the full
  chain. CI already runs `mvn verify -Pintegration-test` on every PR (Java 21,
  Docker), so these execute automatically.
- **Non-interference:** existing tests run in `DISABLED` mode (no token required),
  so they are unaffected; auth tests opt in via profile/tag.

---

## 11. Operational considerations

- **JWKS rotation:** cache with short TTL (e.g. 5–15 min) + `kid` lookup so key
  rotation is seamless; pre-publish new keys before switching signing keys.
- **Token lifetime:** short access tokens (5–15 min); refresh handled by clients
  (end users) and automatic re-acquisition (services). Short TTL is the primary
  revocation mechanism (stateless).
- **Revocation:** for high-assurance operations, optional token introspection
  (RFC 7662) or a deny-list on `jti`; default is short-TTL only.
- **Clock skew:** bounded leeway (30s); NTP on all hosts.
- **Observability:** authenticated `sub`/`client_id` in MDC + audit log; metrics
  for auth success/failure, 401/403 rates, token-validation latency, JWKS refresh.
  Alert on 401/403 spikes (rollout regressions) and JWKS fetch failures.
- **IdP availability:** validation is offline (JWKS cached), so a brief IdP outage
  does not fail request validation; only token *issuance* (login/refresh, S2S
  token acquisition) depends on IdP uptime → IdP must be HA.
- **Secrets:** service client secrets and IdP admin creds in the platform secret
  store; rotate on a schedule; never in the repo (dev uses throwaway local creds).
- **Data residency / compliance:** for RBI, tenant identity data and tokens stay
  in-region; favor an IdP deployment that guarantees this (influences §14).
- **Rate limiting:** coarse at the gateway; per-principal limits possible using the
  authenticated subject.

---

## 12. Rollback plan

- **Instant, per service:** set `originex.security.mode=DISABLED` (or
  `originex.security.enabled=false`) and redeploy/restart → the resource server and
  auth resolver back off; behavior reverts to the current header-trust model. No
  schema or data changes are involved, so rollback is config-only.
- **Staged reversibility:** each stage (§8) is independently reversible —
  ENFORCED→PERMISSIVE drops RBAC rejections while keeping authentication;
  PERMISSIVE→DISABLED restores legacy behavior.
- **RLS coupling:** if auth is rolled back for a service that already has RLS on,
  RLS continues to work on whatever `TenantContextHolder` source is active; to
  fully revert, disable RLS for that service first (its own flag), then auth.
- **Kill-switch discipline:** the flags are the single control points; no code
  path silently upgrades/downgrades trust.

---

## 13. Open design decisions

- **D1 — IdP hosting:** self-hosted Keycloak (recommended §14) vs managed
  (Auth0/Cognito/Okta). Trade cost/control/residency vs ops burden.
- **D2 — Tenant claim shape:** single realm with a `tenant_id` claim (scalable for
  many tenants) vs realm-per-tenant (stronger isolation, heavier ops). Recommend
  single realm + claim, revisit for very large/regulated tenants.
- **D3 — Gateway role:** authoritative token check at the gateway vs
  defense-in-depth per-service resource servers (recommend the latter; gateway for
  TLS/routing/rate-limit only).
- **D4 — S2S tenant propagation:** token exchange / act-as (preferred) vs
  authenticated-and-cross-checked `X-Tenant-Id` (interim).
- **D5 — Token format:** JWT (stateless, recommended) vs opaque + introspection
  (central revocation, more coupling).
- **D6 — Authority granularity:** roles only vs roles + fine scopes (recommend
  both; scopes for capability gating).
- **D7 — mTLS / DPoP:** add sender-constrained tokens later for T6, or rely on
  short TTL + TLS initially.
- **D8 — End-user vs machine tenant binding:** users get tenant from group;
  machines get it via token exchange only when acting for a tenant.

---

## 14. IdP comparison and recommendation

| Criterion | **Keycloak** (self-hosted) | **Auth0** (SaaS) | **AWS Cognito** (SaaS) | Okta (SaaS)* |
|---|---|---|---|---|
| OIDC / OAuth2 RS | Full | Full | Full (some quirks) | Full |
| Client-credentials (S2S) | Yes, first-class | Yes (M2M, priced per token) | Yes | Yes |
| Fine-grained authz / roles / groups | Rich (composite roles, groups, mappers) | Good (rules/actions) | Basic (groups, limited) | Good |
| Custom claims / token mappers | Very flexible | Flexible | Limited/awkward | Flexible |
| Multi-tenant modelling | Realms + groups/attrs | Orgs / tenants | User pools | Orgs |
| Cost model | Infra only (no per-MAU) | **Per-MAU + per-M2M** (scales with users) | Low per-MAU, cheap | Per-MAU (enterprise) |
| Data residency (RBI/India) | Full control (self-host in-region) | Region choice, but SaaS | AWS region control | Region choice |
| Testcontainers / local dev | Excellent (official image, realm export) | Harder (SaaS; needs mocks) | Harder (LocalStack partial) | Harder |
| Ops burden | **You run/patch/HA it** | None (managed) | Low (managed) | None (managed) |
| Vendor lock-in | None (OSS) | High | High (AWS) | High |

\*Okta included for completeness; commercially similar to Auth0 (Okta owns Auth0).

### Recommendation: **self-hosted Keycloak**

Rationale for Originex specifically:
1. **Cost at multi-tenant scale.** A co-lending/BaaS platform can accumulate large
   MAU and heavy M2M token volume; per-MAU/per-token SaaS pricing (Auth0/Okta)
   becomes a significant, usage-coupled cost. Keycloak is infra-only.
2. **Data residency & compliance (RBI).** Self-hosting in-region gives full
   control over where identity data and tokens live — the cleanest posture for
   Indian financial-services compliance.
3. **Flexibility for the tenant-claim model.** Keycloak's composite roles, groups,
   and protocol mappers make the `tenant_id`/scope claim design (D2/D6)
   straightforward and centrally administered.
4. **Local/CI parity.** An official container image + realm export gives
   deterministic local dev and Testcontainers integration (§10) — matching the
   pattern already established for Postgres/Kafka in this repo.
5. **No lock-in.** Standard OIDC means the resource-server code is IdP-agnostic; if
   ops burden later argues for managed, migrating to Auth0/Cognito is a config
   change (issuer/JWKS), not a rewrite.

Accepted trade-off: **operational ownership** (run, patch, and make Keycloak HA).
Mitigation: managed Postgres for its store, standard HA deployment, and — because
resource-server validation is offline — a brief IdP outage does not break request
authorization, only new logins/token issuance. If the org prefers zero identity
ops over cost/residency control, **Auth0** is the fallback (best developer
experience of the SaaS options); the design is portable either way.

---

## 15. Effort, risks, and commit plan

### Effort (overall: **L**, ~6–9 dev-weeks incl. rollout)

| Workstream | Effort |
|---|---|
| Starter `SecurityAutoConfiguration` (RS, decoder, converter, gated) | M |
| Tenant-from-claim resolver + `TenantContext` principal | S–M |
| RBAC taxonomy + `@PreAuthorize` on use-case entry points (×9 services) | M–L |
| S2S client-credentials for internal REST adapters | M |
| Keycloak realm export + docker-compose + secrets wiring | M |
| `test-support` JWT minting + Keycloak Testcontainer ITs | M |
| Kafka `act_by` audit header + consumer UUID validation | S |
| Migration flags, dashboards, staged rollout | M |

### Risks

- **R1 — Ordering bug** (tenant derived after tx begins) → fail-closed empty
  results. Mitigation: resolver strictly between Security chain and controller;
  covered by a combined auth+RLS IT.
- **R2 — S2S breakage when the header is dropped.** Mitigation: migrate internal
  adapters to client-credentials during stage 1 (PERMISSIVE) before stage 2.
- **R3 — RBAC over-restriction** locking out legitimate flows. Mitigation:
  PERMISSIVE observe stage with mismatch dashboards before ENFORCED.
- **R4 — Keycloak HA/ops** immaturity. Mitigation: offline validation limits blast
  radius; standard HA runbook; managed store.
- **R5 — Claim/tenant drift** (IdP tenant attribute ≠ DB `tenant_id`).
  Mitigation: tenant provisioning writes the same UUID to both; a reconciliation
  check in stage 1.
- **R6 — Token size / PII in tokens.** Keep tokens minimal (ids + roles + scopes),
  no PII.

### Commit plan (small, reviewable, dark-shipped — mirrors the RLS sequence)

1. `docs(auth)`: this design (`dev/AUTH_DESIGN.md`). *(this commit)*
2. `feat(auth)`: security config properties + `SecurityAutoConfiguration` (RS,
   `JwtDecoder`, gated on `originex.security.enabled`, default off). No behavior
   change.
3. `feat(auth)`: `JwtAuthenticationConverter` (roles/scopes → authorities) +
   tenant-from-claim resolver; `TenantContext` gains principal. Gated.
4. `feat(auth)`: PERMISSIVE dual-mode + observability (claim/header mismatch
   logging, auth metrics).
5. `feat(auth)`: S2S client-credentials interceptor for internal `RestClient`
   adapters.
6. `feat(auth)`: RBAC taxonomy + `@PreAuthorize` on use-case ports (per service or
   batched), still gated/PERMISSIVE.
7. `feat(kafka)`: `act_by` audit header + consumer UUID validation.
8. `test(auth)`: `test-support` JWT minting + slice tests; Keycloak Testcontainer
   IT proving JWT → tenant claim → RLS isolation (`@Tag("auth")`).
9. `chore(dev)`: Keycloak docker-compose + realm export + token script + docs.
10. `docs(auth)`: `dev/AUTH_ENABLEMENT.md` (operator guide, env vars, stage
    runbook) — the auth analogue of `dev/RLS_ENABLEMENT.md`.

Enablement (flag flips to PERMISSIVE→ENFORCED per service, then coordinate RLS
canary) is operational and follows the RLS rollout order.

---

*This document is design-only; no runtime code is written or modified. Next step
is approval of the IdP choice (D1) and the tenant-claim model (D2) before starting
commit 2.*
