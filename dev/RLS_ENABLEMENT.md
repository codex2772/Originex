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

**Rollout status.** `customer-service` is the Phase 2 canary and is enabled: it
carries `spring.profiles.include: rls` in its own `application.yml`, so the
profile is active everywhere it runs. Every other service is still dark.
RLS is not yet enabled in any **deployment** — `infra/helm` sets no profile.

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
