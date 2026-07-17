# RLS Enablement — operator guide (Phase 1)

How to turn on PostgreSQL row-level security for a service, and the environment
variables that control it. Design rationale: `dev/RLS_DESIGN.md`. Rollout order
and per-service readiness: the Phase 1 enablement roadmap.

## The one switch

RLS is off everywhere by default (`originex.rls.enabled=false`). All RLS wiring
lives in a single shared profile, **`application-rls.yml`**, bundled in the
platform starter (`libs/spring-boot-starter`). It is loaded **only** when the
`rls` Spring profile is active. There is no per-service RLS YAML.

**To enable one service**, add `rls` to its active profiles — nothing else:

```bash
SPRING_PROFILES_ACTIVE=rls        # (append to any existing profiles: prod,rls)
```

Activating the profile sets `originex.rls.enabled=true`, wires the app/system
routing datasource, and points Flyway at the owner role. With the profile
inactive, none of this loads and behaviour is identical to Phase 0.

> Do **not** enable a service until its canary stage in the rollout plan.

**Rollout status** (2026-07-17). Two services are enabled — each carries
`spring.profiles.include: rls` in its own `application.yml`, so the profile is
active everywhere it runs:

| Service | Enabled | What its canary actually proves — and what it does not |
|---|---|---|
| `customer-service` | `d1af8ad` | JWT→RLS isolation on the HTTP path (`CustomerRlsJwtIsolationIntegrationTest`, CI 3/3). Registration is DB-only, so the outbox is unexercised. |
| `ledger-service` | `dbb80fc` | isolation for **account read/write only** (CI 3/3). The **posting and outbox paths are unexercised** — and issue #5 is an open, undiagnosed failure on exactly that path. |
| `payment-service` | `d295293` | isolation **and** the transactional outbox on the RLS datasource (CI 4/4). Callback/inbound paths unexercised; its `Location` header is broken (KI-7). |
| `los-service` | *this commit* | isolation **and** the outbox (CI 4/4). **`CustomerVerificationPort` is mocked**, so los's outbound REST adapters and their **resilience4j circuit-breaker / retry / fallback behaviour are NOT exercised** — a green canary says nothing about them. They need their own test. |

The remaining four are dark. RLS is not yet enabled in any **deployment** —
`infra/helm` sets no profile.

> Read the right-hand column literally. "Canary green" means *the paths that
> canary drives* are proven, not that the service is. Ledger's IT covers
> `POST /v1/ledger/accounts` → `GET`, which is DB-only by design; nothing
> exercises `POST /v1/ledger/journal-entries`, its outbox write, or its
> posting logic — and issue #5 is an open, undiagnosed failure on exactly
> that path. los's IT mocks the one collaborator on its write path, so its
> circuit-breaker and fallback behaviour is equally unproven. Apply the same
> reading to every service you enable: state what the IT drives, not what the
> service contains — and where a collaborator is mocked, say so, because the
> mock is the boundary of what the green means.

## "Enabled" is a claim about the service, not about a test

Two different claims are easy to confuse, and confusing them wastes real time:

| Claim | How you check it |
|---|---|
| *The service runs under RLS* | the profile is in the **service's own config or launch env** — `grep -rn "rls" services/<svc>/src/main/resources/ infra/helm/` |
| *The RLS wiring works* | an integration test sets `@ActiveProfiles("rls")` |

`@ActiveProfiles("rls")` activates the profile **only for that test's own
context**. It proves the wiring is sound; it says nothing about whether the
service is configured to run under RLS in CI or in a deployed environment. A
repo can be full of green `rls` tests while every service still runs without it.
When asked "is service X enabled?", look at X's configuration — not its tests.

## The header path and the JWT path are mutually exclusive

`TenantResolutionFilter` (which trusts the `X-Tenant-Id` header) is registered by
`OriginexAutoConfiguration` **only while `originex.security.enabled=false`**
(`havingValue = "false"`, `matchIfMissing = true`). Turn security on and that
bean is never created: `TenantClaimResolutionFilter` takes over, tenant comes
from the verified `tenant_id` claim, and under the default `ENFORCED` mode
`X-Tenant-Id` is **ignored** entirely.

The consequence for anyone enabling RLS on a service:

> A header-driven RLS test proves the **pre-auth** path only. It cannot tell you
> whether tenant isolation holds once authentication is on — it exercises a
> filter that will not exist in that configuration.

So each service needs **both**, and passing the first is not evidence for the
second. `CustomerRlsJwtIsolationIntegrationTest` is the template for the second:
real Keycloak (the actual `infra/keycloak/realm-export.json`), real minted
tokens for two tenants, asserting that a spoofed `X-Tenant-Id` cannot override
the claim in either direction and that an unauthenticated request is 401.

## Enabling a service is not a one-liner — budget for the test

The rollout plan reads as one profile flip per service. That is misleading. The
flip is one line; **the work is the test that gives the canary something to
prove.** As of 2026-07-17, outside customer-service and lms, *no service has a
single context-booting test*:

| Service | Context-booting tests | RLS policies | What enabling it costs |
|---|---|---|---|
| ledger | 0 | 9 | write its first boot-level RLS IT, then flip |
| payment | 0 | 4 | write its first boot-level RLS IT, then flip |
| los | 0 | 6 | write its first boot-level RLS IT, then flip |
| notification | 0 | 4 | write its first boot-level RLS IT, then flip |
| bre | 0 | 4 | write its first boot-level RLS IT, then flip |
| partner-integration | 0 (no tests at all) | 2 | write its first boot-level RLS IT, then flip |
| lms | 2 | 6 | **also** migrate `LoanLifecycleIntegrationTest` to `RlsPostgresSupport` first |

Two consequences worth being blunt about:

1. **A clean flip is not a green light.** On a service with no context-booting
   test, adding the profile breaks nothing *and exercises nothing*. CI stays
   green while the service has never been observed booting under `rls`. Do not
   read that green as evidence — it is the absence of evidence.
2. **`spring.profiles.include: rls` is unconditional.** It applies to every
   Spring context the service loads, including tests. A `@SpringBootTest`
   without `@ActiveProfiles("rls")` on a plain `PostgreSQLContainer` (no RLS
   roles) will fail at boot on a missing `originex_app` — fail-loud working as
   designed, but confusing if unexpected. `lms` is the live example: its
   `LoanLifecycleIntegrationTest` must move to `RlsPostgresSupport` **before**
   its canary, not as part of it.

Per service, budget: one JWT-driven RLS IT (template above) + the flip +
whatever the IT uncovers — not a one-line change.

### The three-commit shape (every remaining service)

`spring-boot-starter-oauth2-resource-server` is on **customer-service only** — it
was the Phase 1 auth canary. Every other service must opt in before security can
be enabled at all: `SecurityAutoConfiguration` builds the `JwtDecoder` under
`originex.security.enabled=true`, and without the dependency there is no decoder,
so a JWT-driven RLS test is impossible. Verify before assuming otherwise:

```bash
grep -c oauth2-resource-server services/<svc>-service/pom.xml   # 0 ⇒ not opted in
```

So each canary is **three commits, stop-and-wait after each, CI as the gate**:

1. **Opt into the OAuth2 resource server.** Dependency + a port of
   `SecurityOptInVerificationTest` proving the opt-in changes nothing while
   `originex.security.enabled=false`. This is **Phase 1 work executed inside the
   canary for sequencing convenience** — it is not part of Phase 2's identity, and
   it gets its own commit rather than being folded into the RLS work.
2. **Write the JWT-driven RLS IT** (template: `CustomerRlsJwtIsolationIntegrationTest`).
3. **Flip `spring.profiles.include: rls`** — with boot evidence, not assumption.

### Check the not-found mapping *before* writing the IT

The isolation test asserts that another tenant's row reads as **404**. That only
holds if the service throws a `ResourceNotFoundException`. Most don't yet — see
issue #4 — so **check first, or the IT asserts the wrong status and the failure
looks like an RLS regression when it isn't**:

```bash
grep -rn "IllegalArgumentException" services/<svc>-service/src/main/java | grep -i "not found"
```

Any hit ⇒ that service needs a small `fix(<svc>): map resource-not-found to HTTP
404` commit **before** its IT, as its own commit (ledger's `99164b5` is the
worked example; `3b942db` is the original precedent). Do not bulk-fix other
services — each one belongs to that service's own canary.

Known state as of 2026-07-17: **ledger** fixed (`99164b5`); **payment**, **lms**,
and **customer**'s bank-account lookup still throw `IllegalArgumentException` →
400; **los** and **template** throw a `NotFoundException` that extends
`RuntimeException`, never reaches the 404 handler, and surfaces as **500** — a
worse failure needing the exception re-parented, not just swapped. Only
**bre**, **notification**, and **partner-integration** are clean.

This is not cosmetic. `ResourceNotFoundException`'s contract is explicit that a
row hidden by RLS must surface as 404 — *"another tenant's record is simply 'not
found'"* — so a service that 400s or 500s on a cross-tenant read is reporting
tenant isolation incorrectly, whatever its test asserts.

Two things that must **not** get folded in:

- **The realm needs no change.** `originex-tenant` is a *default* client scope on
  `originex-web`, so tokens for `customer-alice` / `customer-bob` already carry
  `tenant_id`. Tenant isolation is provable without touching Keycloak.
- **Authorization is a separate concern.** `OriginexScopes` defines per-domain
  scopes (`ledger:read`, `ledger:post`, …) that the realm does not yet publish, and
  no service outside customer-service has `@PreAuthorize`. None of that is needed
  to prove tenant isolation — the verified claim drives RLS regardless. Adding
  `@PreAuthorize` + realm scopes is Phase 1 authz work, its own commit.

**`lms` carries two extra prerequisites** before its canary, deliberately explicit
rather than absorbed into "do it like the others": the outbox `jsonb`/SQLSTATE
42804 failure (issue #3) and migrating `LoanLifecycleIntegrationTest` to
`RlsPostgresSupport`. Order it last.

## What the profile assumes

The `app`, `system`, and `owner` roles all connect to the **same database** as
the service's existing `spring.datasource.url`; only the **role** differs:

| Route | Role | Attribute | Used for |
|---|---|---|---|
| app | `originex_app` | NOBYPASSRLS | HTTP + Kafka-consumer runtime path |
| system | `originex_system` | BYPASSRLS | accrual / DPD / retry schedulers |
| Flyway | `originex_owner` | BYPASSRLS + CREATE | DDL **and** seed DML at startup |

These roles must exist in the target cluster before enabling (local dev:
`dev/init-scripts/init-databases.sql`; other environments: DBA/IaC).

## Environment variables

All optional; defaults target local docker Postgres. **Production must override
the passwords** via the secret store.

| Variable | Default | Purpose |
|---|---|---|
| `RLS_DATASOURCE_URL` | `${spring.datasource.url}` | JDBC URL shared by app/system/owner. Defaults to the service's own DB. |
| `RLS_APP_USERNAME` | `originex_app` | App (RLS-subject) role. |
| `RLS_APP_PASSWORD` | `originex_app_local` | **Override in prod.** |
| `RLS_APP_POOL_SIZE` | `10` | App Hikari pool max. |
| `RLS_SYSTEM_USERNAME` | `originex_system` | System (BYPASSRLS) role. |
| `RLS_SYSTEM_PASSWORD` | `originex_system_local` | **Override in prod.** |
| `RLS_SYSTEM_POOL_SIZE` | `5` | System Hikari pool max. |
| `RLS_OWNER_USERNAME` | `originex_owner` | Flyway (owner) role. |
| `RLS_OWNER_PASSWORD` | `originex_owner_local` | **Override in prod.** |

A wrong password fails loud at connection time; it never weakens isolation.
A missing `app`/`system` `url` or `username` fails the service at boot with a
precise message (fail-loud, by design).

## Local dev quick start

Roles + per-database grants are already provisioned by the docker init script.
To run a service with RLS on against local Postgres:

```bash
SPRING_PROFILES_ACTIVE=rls ./run-service.sh <service>   # or your usual launcher
```

No other variables are needed locally — the `*_local` password defaults match
the init script.

## Verifying it took effect

```sql
-- On a request thread (app route): expect originex_app
SELECT current_user;
SELECT current_setting('app.tenant_id', true);   -- non-null inside a request tx
```

If reads unexpectedly return zero rows after enabling, the usual cause is the
connecting role or an unset `app.tenant_id` — see the troubleshooting flow in
`dev/RLS_DESIGN.md`.

## Verifying isolation in CI (role-aware integration tests)

RLS isolation is proven by integration tests that connect as `originex_app`
(not the container superuser, which would bypass RLS). The shared harness lives
in `libs/test-support` (`RlsPostgresSupport` + `rls/test-roles.sql`): it starts a
Postgres Testcontainer whose init script provisions the three roles with the
same names/passwords the `rls` profile defaults to, then exposes per-role
datasources and `migrateAsOwner(...)`.

Two layers, both tagged `@Tag("rls")`:

| Layer | Example | Proves |
|---|---|---|
| DB semantics | `CustomerRlsSemanticsIntegrationTest` | isolation, `WITH CHECK`, fail-closed, system-role bypass — asserted directly via per-role datasources |
| App wiring (pre-auth) | `CustomerHttpRlsIsolationIntegrationTest`, `LmsRlsConsumerAndSchedulerIntegrationTest` | `TenantResolutionFilter` (`X-Tenant-Id`), Kafka `RecordInterceptor`, and `runAsSystem` scheduler set `app.tenant_id` end-to-end — **with `originex.security.enabled=false` only** |
| App wiring (authenticated) | `CustomerRlsJwtIsolationIntegrationTest` | the path production runs: `TenantClaimResolutionFilter` derives the tenant from a verified JWT claim, a spoofed `X-Tenant-Id` is ignored, unauthenticated → 401 |

They are named `*IntegrationTest`, so they run under the existing failsafe
profile and stay out of the unit (`surefire`) build. Run just the RLS suite with
the tag:

```bash
mvn verify -Pintegration-test -Dgroups=rls          # RLS isolation tests only
mvn verify -Pintegration-test                        # all integration tests
```

Requires Docker (Testcontainers pulls `postgres:16-alpine`, for LMS
`confluentinc/cp-kafka`, and for the JWT tests `quay.io/keycloak/keycloak`).
Adding coverage for another service is a matter of a test-scope dependency on
`originex-test-support` plus a `@Tag("rls")` test built from
`RlsPostgresSupport` (and `KeycloakSupport` for the authenticated layer).

> **Known local blocker (2026-07-16).** On Docker Desktop 29+ these tests fail
> locally with `Could not find a valid Docker environment` even though the
> `docker` CLI works. It is not a socket problem: Testcontainers 1.20.4's
> docker-java negotiates Docker API **v1.32**, and Docker 29 raised its
> `MinAPI` to **1.40**, so the daemon rejects the call. Confirm with
> `curl --unix-socket ~/.docker/run/docker.sock http://localhost/v1.32/info`
> (zeroed payload) versus `/v1.44/info` (real data). `DOCKER_HOST`,
> `DOCKER_API_VERSION`, and `~/.testcontainers.properties` do **not** work
> around it; the fix is a Testcontainers upgrade (≥1.21.x). CI's runner has an
> older daemon and is unaffected — so CI remains the gate until that lands.
