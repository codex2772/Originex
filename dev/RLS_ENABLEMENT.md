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
| App wiring | `CustomerHttpRlsIsolationIntegrationTest`, `LmsRlsConsumerAndSchedulerIntegrationTest` | HTTP filter, Kafka `RecordInterceptor`, and `runAsSystem` scheduler set `app.tenant_id` end-to-end |

They are named `*IntegrationTest`, so they run under the existing failsafe
profile and stay out of the unit (`surefire`) build. Run just the RLS suite with
the tag:

```bash
mvn verify -Pintegration-test -Dgroups=rls          # RLS isolation tests only
mvn verify -Pintegration-test                        # all integration tests
```

Requires Docker (Testcontainers pulls `postgres:16-alpine` and, for LMS,
`confluentinc/cp-kafka`). Adding coverage for another service is a matter of a
test-scope dependency on `originex-test-support` plus a `@Tag("rls")` test built
from `RlsPostgresSupport`.
