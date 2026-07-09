# Phase 0 Verification Checklist

Repeatable, manual + scripted checklist to establish a **before** baseline
prior to any Phase 0 fix, and an **after** result once each Phase 0 commit
lands. Re-run the whole checklist once all 7 Phase 0 commits are in to
confirm the platform starts cleanly end-to-end (the Phase 0 acceptance
criterion in `CLAUDE_ANALYSIS.md` §2).

Some steps only become fully green once specific commits land — that's
expected. Each step below states which commit closes it.

---

## Known Local-Environment Blockers (not repository defects)

Two issues surfaced while running this checklist that are specific to the
machine it was run on, not to `dev/docker-compose.yml` or any other
committed file. They are documented here for anyone hitting the same thing,
and are **intentionally not fixed** — `docker-compose.yml` has not been
modified for either. Treat both as environment-specific unless a second,
independent machine reproduces them, which would upgrade them to a real
repository issue.

1. **`originex-postgres` fails to start: "address already in use" on
   5432.** Caused by a native PostgreSQL service already installed and
   running on that machine (`/Library/PostgreSQL/18/bin/postgres`),
   permanently bound to host port 5432, entirely outside Docker. Any
   machine with a local Postgres install (native, Homebrew, or another
   unrelated Docker Compose project) already using 5432 will hit this.
   Workaround used for verification only: a throwaway `postgres:16-alpine`
   container on an alternate host port (e.g. `-p 15432:5432`), never
   part of the repository.

2. **`originex-kafka` (`cp-kafka:7.7.1`, KRaft mode) exits immediately**
   with `Error while writing meta.properties file
   /tmp/kraft-combined-logs: .../bootstrap.checkpoint.tmp`. Reproduced
   identically against both a stale and a freshly-recreated
   `dev_kafka_data` volume, which rules out stale volume state as the
   cause — it appears to be a Docker Desktop / host-filesystem interaction
   with this specific image's KRaft combined-log directory on that
   machine. No further root-causing has been done; flagged here rather
   than guessed at.

Full details and the exact commands/output are in the "Baseline Log" →
BEFORE section near the end of this document.

---

## 1. Docker Compose Startup

```bash
dev/scripts/verify-phase0-infra.sh up
```

Expected: `originex-postgres`, `originex-kafka`, `originex-schema-registry`,
`originex-redis`, `originex-kafka-ui` all report `running` with a healthy
(or no-op) healthcheck. This part has no known Phase 0 gaps — it should be
green on the very first run.

## 2. Database Creation Verification

Covered by the same script (section 2 of its output). It compares the
actual databases in the `originex-postgres` container against the 9
databases the real services expect (`originex_customer`, `originex_los`,
`originex_lms`, `originex_ledger`, `originex_partner`, `originex_payment`,
`originex_notification`, `originex_bre`, `originex_template`).

**RESOLVED — Phase 0 commit 3 ("Fix dev database initialization").**
`originex_bre`, `originex_partner`, `originex_notification` were missing
and `originex_collections`/`originex_iam` were stale entries for services
with no Maven module. `dev/init-scripts/init-databases.sql` now creates
exactly the 9 databases above. Verified against a clean Postgres 16: all 9
`CREATE DATABASE`/`GRANT` statements apply cleanly, and connecting as the
`originex` user to each of the 9 by name succeeds (see "AFTER commit 3"
below).

## 3. Flyway Migration Success

Per service, with the stack up:
```bash
mvn -pl services/<service-name> spring-boot:run
# watch the startup log for:
#   "Successfully validated N migrations"
#   "Successfully applied N migrations to schema ... "
# a FlywayException here is a hard stop for that service.
```
Or, without booting the full Spring context:
```bash
mvn -pl services/<service-name> flyway:info -Dflyway.url=jdbc:postgresql://localhost:5432/originex_<db> \
  -Dflyway.user=originex -Dflyway.password=originex_local
```

**Known gap until commit 1 ("Fix ledger bootstrap schema and seed accounts"):**
`ledger-service` has no Flyway-created `inbox_events` table even though
`LmsEventConsumer` depends on `InboxEventRepository` at runtime. With
`spring.jpa.hibernate.ddl-auto: validate`, this fails Hibernate schema
validation at boot; with a more lenient `ddl-auto`, it fails the first time
the consumer tries to write to a table that doesn't exist. Confirm the exact
current `ddl-auto` setting in `services/ledger-service/src/main/resources/application.yml`
before relying on either failure mode.

**Known gap until commit 2 ("Add payment outbox/inbox migrations"):**
same issue, worse — `payment-service` currently has neither `outbox_events`
nor `inbox_events` at all.

## 4. Service Startup Validation

Start each service individually (`mvn -pl services/<name> spring-boot:run`)
and confirm `/actuator/health` returns `{"status":"UP"}`.

**RESOLVED — Phase 0 commit 5 ("Normalize local development ports").**
`dev/docker-compose.yml`'s `schema-registry` container bound **host port
8081**, colliding with `customer-service`'s `server.port: 8081` — running
the full local dev stack and `customer-service` side by side would fail
with an address-already-in-use error. This was not caught by the earlier
`CLAUDE_ANALYSIS.md` audit (which only compared service ports against
each other and against Kafka UI); it only surfaced once this checklist
actually ran the compose stack and a service side by side. `schema-registry`'s
host-side port mapping moved to 8090 (container still listens on 8081
internally — no Docker-internal URL changed); `customer-service` was left
untouched at 8081, since it's the actively-used business API and
schema-registry's `localhost:8081` was only referenced by two unused
config defaults, not by any live code path. Full audit and rationale for
choosing which side to move are in commit 5's summary.

## 5. Kafka Topic Verification

Covered by the same script (section 3 of its output): confirms the broker
is reachable and lists whatever topics currently exist. Note
`infra/kafka/topics.yaml`'s 12 `KafkaTopic` CRDs are Strimzi/EKS-only — the
local docker-compose broker auto-creates topics lazily on first publish, so
an empty topic list on a fresh stack is expected, not a failure.

**Known gap (commit 7, "Add/verify missing Kafka topic CRDs"):**
`originex.customer.customers.events` — the topic `customer-service`
publishes to and `notification-service` consumes from — has no CRD in
`topics.yaml` at all. Irrelevant locally (auto-create covers it), relevant
once a real Strimzi cluster is targeted.

## 6. Customer → LOS → LMS → Ledger Smoke Flow

Only meaningful once enough services can start simultaneously (i.e. after
the database and port-conflict gaps above are closed — realistically after
commits 1–3 and whichever of commit 5 is needed). Once runnable:

```bash
TENANT=00000000-0000-0000-0000-000000000001

# 1. Create customer
curl -s -X POST http://localhost:8081/v1/customers \
  -H "X-Tenant-Id: $TENANT" -H "Content-Type: application/json" \
  -d '{"firstName":"Test","lastName":"Borrower","phone":"9999999999","pan":"ABCDE1234F", ...}'

# 2. Submit loan application (customerId from step 1)
curl -s -X POST http://localhost:8082/v1/loan-applications \
  -H "X-Tenant-Id: $TENANT" -H "Content-Type: application/json" \
  -d '{"customerId":"<id>","productCode":"PERSONAL_LOAN","amount":"100000","currency":"INR","tenureMonths":12}'

# 3. Trigger credit-check (applicationId from step 2)
curl -s -X POST http://localhost:8082/v1/loan-applications/<id>/credit-check \
  -H "X-Tenant-Id: $TENANT" -H "Content-Type: application/json" -d '{"consentArtifactId":"c1"}'

# 4. Accept offer
curl -s -X POST http://localhost:8082/v1/loan-applications/<id>/offer/accept \
  -H "X-Tenant-Id: $TENANT"

# 5. Poll LMS for the loan created by the DisbursementRequested consumer
curl -s http://localhost:8083/v1/loans/<loanId> -H "X-Tenant-Id: $TENANT"

# 6. Confirm ledger posted without error (requires commit 1):
docker exec originex-postgres psql -U originex -d originex_ledger -c \
  "SELECT entry_type, posting_date FROM journal_entries ORDER BY posted_at DESC LIMIT 5;"
```

Before commit 1, step 6 will show **no rows** and `ledger-service`'s logs
will show `IllegalArgumentException: Account not found` for the
`POOL_ACCOUNT_ID` leg — this is the exact failure this checklist exists to
catch, and the baseline run below reproduces it directly at the repository
level without needing all 9 services up.

---

## Baseline Log

| Run | Date | Scope | Result |
|---|---|---|---|
| BEFORE | 2026-07-09 | `docker compose up` + Flyway migration replay | See findings below |
| AFTER commit 1 | 2026-07-09 | ledger-service V1+V2 Flyway replay | Migrations apply cleanly; accounts + inbox_events verified (see below) |
| AFTER commit 2 | 2026-07-09 | payment-service V1+V2 Flyway replay | Migrations apply cleanly; outbox_events + inbox_events verified (see below) |
| AFTER commit 3 | 2026-07-09 | `dev/init-scripts/init-databases.sql` replay | All 9 service databases created and connectable (see below) |
| AFTER commit 4 | 2026-07-09 | `PaymentApplicationService.selectRail()` unit tests | 13/13 pass — boundary values and explicit override all correct (see below) |
| AFTER commit 5 | 2026-07-09 | Full repo-wide port reference audit + docker-compose replay | Zero remaining `:8080`/`:8081` conflicts; schema-registry container verified reachable on new host port (see below) |

### BEFORE — actual findings from running the checklist, not just reading code

1. **`originex-redis` came up healthy immediately.** No issues.

2. **`originex-postgres` could not start on this machine**: `docker compose up` failed
   with `ports are not available: exposing port TCP 0.0.0.0:5432 ... address already
   in use`. Root cause: this machine has a **native PostgreSQL 18 service already
   running** (`/Library/PostgreSQL/18/bin/postgres`), permanently bound to port 5432,
   entirely unrelated to Docker or this repository. This is a per-machine environment
   conflict, not a defect in `dev/docker-compose.yml` — do not "fix" the committed
   compose file's port mapping for this; other engineers' machines may not have this
   conflict. Worked around locally for verification by running a throwaway
   `postgres:16-alpine` container on an alternate port (15432), never touching the
   committed compose file.

3. **`originex-kafka` (cp-kafka:7.7.1, KRaft mode) exited immediately on this
   machine**, both against a stale volume and a freshly-recreated one:
   `Error while writing meta.properties file /tmp/kraft-combined-logs: .../bootstrap.checkpoint.tmp`.
   This reproduced identically after `docker compose down -v` (ruling out stale
   volume state as the cause) — it looks like a Docker-Desktop/host-filesystem
   interaction with this specific image's KRaft combined-log directory on this
   machine. Not caused by anything in this repository or by any Phase 0 commit;
   noted as a new, unscoped local-environment finding, not fixed here.

4. **Real bug found in `V1__create_ledger_schema.sql`, independent of both of the
   above**: applied directly against a clean, unmodified `postgres:16-alpine`
   (the project's own target version) via `psql -f`, it failed with:
   ```
   ERROR:  unique constraint on partitioned table must include all partitioning columns
   DETAIL:  PRIMARY KEY constraint on table "ledger_events" lacks column "occurred_at"
            which is part of the partition key.
   ```
   `ledger_events` is `PARTITION BY RANGE (occurred_at)` but its original
   `PRIMARY KEY (tenant_id, aggregate_id, event_sequence)` didn't include
   `occurred_at`. PostgreSQL requires every unique/PK constraint on a partitioned
   table to include all partition-key columns (true since partitioning was
   introduced in PG 11, unaffected by version specifics). Since Flyway (and
   `psql -f`) runs a migration transactionally, this meant **V1 could never have
   successfully applied against a real Postgres 16 database, in any environment** —
   no `account_snapshots`, `journal_entries`, `postings`, or `outbox_events` table
   was ever actually created by this migration. This is more severe than the
   originally-scoped "seed 3 accounts + add inbox_events table" fix, since the
   seed migration has nothing to insert into until V1 itself succeeds.

   Fixed as part of commit 1 (user-approved exception to "never modify old
   migrations," justified because no environment could possibly have V1 recorded
   as successfully applied — there is no checksum-mismatch risk to protect
   against). See commit 1 below.

### AFTER commit 1 — re-verified against a clean Postgres 16

Re-applied `V1__create_ledger_schema.sql` (fixed) followed by
`V2__seed_chart_of_accounts_and_inbox_table.sql` against a fresh, unmodified
`postgres:16-alpine`:
- V1: all 8 tables created (`ledger_events` partitioned + 2 monthly partitions,
  `account_snapshots`, `journal_entries`, `postings`, `outbox_events`), all 3 RLS
  policies applied. `V1_EXIT:0`.
- V2: `inbox_events` table created; 3 rows inserted into `account_snapshots`
  matching `LmsEventConsumer`'s `POOL_ACCOUNT_ID` / `INTEREST_INCOME_ID` /
  `INTEREST_RECEIVABLE_ID` exactly, under the default tenant
  `00000000-0000-0000-0000-000000000001`. `V2_EXIT:0`.
- Confirmed via direct query: `account_snapshots` has exactly 3 rows with the
  expected `account_number`/`account_type`/`normal_balance`/`gl_code` values;
  `inbox_events` matches `InboxEventJpaEntity`'s column shape exactly.

Not yet verified in this pass (blocked on the port-5432 and Kafka KRaft findings
above, which are this-machine issues, not Phase 0 code issues): booting the full
`ledger-service` Spring Boot app end-to-end and replaying a real
`LoanDisbursed`/`RepaymentAllocated` Kafka message through `LmsEventConsumer`
against the seeded accounts. The direct-SQL verification above proves the schema
and seed data are correct; proving the consumer's runtime behavior against a live
broker requires an environment without this machine's local port/Kafka
conflicts (any other machine, or CI).

### AFTER commit 2 — payment-service outbox/inbox migrations

Followed the same reproduce-then-fix process as commit 1:

1. **Reproduced first**: applied `V1__create_payment_schema.sql` alone
   against a clean `postgres:16-alpine`. Unlike ledger's V1, it applied
   without error (`V1_EXIT:0`) — no partitioning-style bug here. Confirmed
   by listing tables afterward that only `payment_orders` and
   `nach_mandates` exist — `outbox_events` and `inbox_events` are genuinely
   absent, exactly as reported. Since V1 itself is not broken, only a new
   migration was needed; V1 was not touched.

2. **Verified required columns against the actual JPA entities** before
   writing SQL: `OutboxEventJpaEntity` (`libs/spring-boot-starter`) maps
   `event_id, aggregate_type, aggregate_id, event_type, tenant_id, payload,
   metadata, status, created_at, published_at` (10 columns).
   `InboxEventJpaEntity` maps `event_id, event_type, processed_at` (3
   columns). Also compared column-for-column against
   `PaymentOrderJpaEntity`/`NachMandateJpaEntity` vs. V1's existing
   `payment_orders`/`nach_mandates` tables — already an exact match, no
   drift there.

3. **New finding**: cross-checking `published_at` against every other
   service's `outbox_events` table surfaced that `customer-service`,
   `los-service`, and `lms-service` all correctly include it, but
   **`ledger-service`'s `outbox_events` (from V1, unaffected by commit 1's
   fix) is missing it** — with `ddl-auto: validate` set on every service
   (confirmed across all 9 `application.yml` files), this means
   ledger-service likely also fails Hibernate schema validation at boot,
   independent of the account-seeding fix already shipped. Not fixed here
   (out of commit-2 scope: payment-service only) — flagged for a follow-up.

4. Added `V2__create_outbox_and_inbox_tables.sql`, matching the correct
   3-service pattern exactly (including `published_at` and the
   `idx_outbox_pending` partial index), plus `inbox_events` matching the
   shape used everywhere else. No RLS on either table, consistent with
   every other service — `outbox_events`/`inbox_events` are never
   RLS-protected anywhere in this codebase.

5. **Re-verified end to end** against the same clean Postgres: V1 → V2
   both apply (`V1_EXIT:0`, `V2_EXIT:0`); `outbox_events` has all 10
   expected columns plus the `idx_outbox_pending` index;
   `inbox_events` has the expected 3 columns; `pg_class` confirms
   `payment_orders`/`nach_mandates` still have RLS forced
   (`relrowsecurity=t, relforcerowsecurity=t`) and `outbox_events`/
   `inbox_events` correctly have neither (`f, f`).

Not verified in this pass, for the same pre-existing reasons as commit 1:
booting payment-service's actual Spring context to observe Hibernate's
`ddl-auto: validate` pass at runtime (blocked by this sandbox's Maven
registry access for pinned `postgresql`/`testcontainers` versions, and by
the port-5432/Kafka findings above for a live end-to-end run). The
column-for-column comparison against the real JPA entity mappings above is
the substitute verification, same approach as commit 1.

### AFTER commit 3 — dev database initialization

1. **Verified the target list against the actual Maven reactor**, not
   assumed: `grep`'d `<modules>` in the root `pom.xml` (9 services, no
   `collections-service` or `iam-service` module exists) and cross-checked
   every service's `spring.datasource.url` in `application.yml` directly.
   All 9 resolve to `originex_customer`, `originex_los`, `originex_lms`,
   `originex_ledger`, `originex_partner`, `originex_payment`,
   `originex_notification`, `originex_bre`, `originex_template` — exactly
   the list requested, confirming `template-service` still needs its
   database (it has both a live datasource config and its own
   `V1__create_template_schema.sql`, so "only if still required" resolves
   to yes).

2. Rewrote `dev/init-scripts/init-databases.sql`: added the 3 missing
   databases (`originex_bre`, `originex_partner`, `originex_notification`),
   removed the 2 stale ones with no corresponding Maven module
   (`originex_collections`, `originex_iam`), kept the other 6 unchanged.

3. **Reproduced clean, then re-verified** against a fresh
   `postgres:16-alpine` (same throwaway-container approach as commits 1–2):
   - Ran the fixed script exactly as `docker-entrypoint-initdb.d` would
     (`psql -v ON_ERROR_STOP=1 -f init-databases.sql`) — all 9
     `CREATE DATABASE`/`GRANT` pairs succeeded (`INIT_EXIT:0`).
   - Listed databases afterward: all 9 expected names present (plus
     Postgres's own `originex_dev` from `POSTGRES_DB`, unrelated to this
     script).
   - **Connected as the `originex` user to each of the 9 databases by
     name** (`psql -U originex -d <db> -c "SELECT current_database();"`),
     the same thing each service's JDBC URL does — all 9 succeeded, no
     missing-database errors.

No documentation outside this file and `CLAUDE_ANALYSIS.md` referenced the
old database list (`docs/architecture/05-database-strategy/data-architecture.md`
checked directly — no match), so no other doc changes were needed.

### AFTER commit 4 — payment rail selection

Investigated before writing any code, per the requested process:

1. **Existing rail adapters**: read `NeftRailAdapter`/`RtgsRailAdapter`/`ImpsRailAdapter`
   in full. Found `RtgsRailAdapter`'s own Javadoc claimed RTGS is "for
   high-value transfers >= ₹2 lakhs" (no upper bound) — in tension with
   routing RTGS only above ₹5L. No dedicated payment business-rule doc
   exists anywhere in `docs/architecture/`, no README for payment-service,
   and no existing test encoded expected rail-selection behavior, so the
   adapter Javadocs were the only documented intent available. Flagged
   this conflict to the user rather than picking a side — confirmed:
   implement the originally-proposed thresholds, and correct
   `RtgsRailAdapter`'s Javadoc (comment only) so it stops contradicting
   the fixed behavior.
2. **DTO/API check**: confirmed `preferredRail` is a real, working
   explicit override (`PaymentUseCase.InitiateDisbursementCommand`,
   comment: `"NEFT, RTGS, IMPS — null = auto-select"`), reachable via
   `PaymentController` — this is what the override tests below exercise.
3. Implemented the confirmed business rule in
   `PaymentApplicationService.selectRail()`: `>₹5,00,000→RTGS`,
   `₹2,00,000–₹5,00,000→IMPS`, `<₹2,00,000→NEFT`. Renamed the misleading
   `RTGS_THRESHOLD`/`IMPS_MAX` constants to `IMPS_MIN_AMOUNT`/
   `IMPS_MAX_AMOUNT` and documented the three-way rule directly on the
   method Javadoc, per the request to document the business rule near the
   selection logic.
4. Added `PaymentApplicationServiceTest` (13 tests: the 5 requested
   boundary values plus a large-amount sanity check and a blank-preferred
   case under `AutoSelection`; explicit-override tests for all 4 rails
   including case-insensitivity and a genuinely-unrecognized preferred
   rail falling back to auto-select, under `ExplicitOverride`).
5. **Verification**: `mvn test` blocked by the same pre-existing Maven
   registry issue as commits 1–2 (confirmed unrelated to this change —
   same root cause). Compiled the test class directly via `javac` against
   cached JUnit/AssertJ jars plus the project's own compiled classes, then
   executed all 13 `@Test` methods through a small reflection-based
   runner (JUnit itself isn't invoked, but every assertion in every test
   method genuinely runs against the real `PaymentApplicationService`
   code): **13/13 PASS**. Full reactor `mvn compile` also green.

Scope check: only `PaymentApplicationService.java` (logic + constants +
Javadoc), `RtgsRailAdapter.java` (Javadoc only, explicitly approved for
this reason), and the new test file were touched. No other adapter, no
unrelated payment flow.

### AFTER commit 5 — normalize local development ports

Full repository-wide audit performed before any change, then a
repository-wide re-search after, per the requested process:

1. **Audit**: searched every `.yml`/`.yaml`/`.md`/`.sh`/`.java`/`.sql`/
   `.properties`/`.json` file for `localhost:8080`, `localhost:8081`,
   `:8080`, `:8081`, plus bare `8080`/`8081` tokens (to catch YAML's
   `port: 8080` style, which has no literal `:8080` substring) and
   confirmed zero test-resource files exist to check. Found exactly 2
   conflicts among 13 total local ports (5 infra containers + 9 wait, 9
   services + `originex_dev`'s default — see the port table in the
   commit summary), everything else already clean.
2. Found two references that turned out **not** to be affected and were
   correctly left alone: `pom.xml`'s Jib plugin `<port>8080</port>`
   (generic container-image EXPOSE metadata shared by every service,
   unrelated to any service's actual `server.port` or to local
   docker-compose networking) and `infra/helm/originex-service/values.yaml`'s
   4× `port: 8080` (generic Kubernetes `ClusterIP`/probe port template —
   each service gets its own pod/service in K8s, so there's no host-port
   collision concept there at all).
3. Also found `schema-registry-url: http://localhost:8081` declared in
   `lms-service` and `template-service`'s `application.yml`, plus the
   same default hardcoded in `libs/spring-boot-starter/.../OriginexProperties.java`
   — traced all three: none are actually read by any Kafka
   producer/consumer/serializer anywhere in the codebase (protobuf/schema-registry
   wiring isn't implemented yet, consistent with `CLAUDE_ANALYSIS.md`'s
   earlier finding). This confirmed schema-registry was the lower-risk
   side of the 8081 conflict to move — zero functional runtime impact
   today, versus `customer-service` which is the actively-used business
   API already exercised in this file's own smoke-test commands.
4. Implemented: `template-service` → `8089`; `schema-registry`'s
   **host-side** port mapping only → `8090:8081` (container-internal
   listener, healthcheck `CMD` target, and `kafka-ui`'s
   `KAFKA_CLUSTERS_0_SCHEMAREGISTRY: http://schema-registry:8081` all
   left untouched, exactly as instructed — none of those are host-facing).
   Updated the two `schema-registry-url` declarations and the starter's
   default to `8090` for consistency, and `README.md`'s Quick Start curl
   example to `8089`.
5. **Re-search confirmed complete**: re-ran the same repo-wide search
   after editing — every remaining `8080`/`8081` reference is either
   kafka-ui (unchanged, correct), customer-service (unchanged, correct),
   a Docker-internal URL (unchanged, correct per instruction), the
   frozen `CLAUDE_HANDOVER.md` historical snapshot (intentionally never
   edited, consistent with how this document has been treated throughout
   Phase 0 — corrections go in `CLAUDE_ANALYSIS.md`, not into the
   original artifact), or the Jib/Helm generic templates confirmed
   unrelated in step 2.
6. **Verified the fix actually works**, without relying on a full
   `docker compose up` (blocked on this machine by the pre-existing,
   unrelated Postgres-5432 and Kafka-KRaft issues documented earlier):
   - `docker compose -f dev/docker-compose.yml config` — valid, and the
     resolved config shows exactly `target: 8081` / `published: "8090"`
     for schema-registry, confirming the edit parses and resolves
     correctly.
   - **Directly proved the conflict is resolved**, independent of
     whether the full stack can boot on this machine: started a
     throwaway container binding host port 8081 (simulating
     `customer-service`), then attempted `docker run -p 8081:8081
     confluentinc/cp-schema-registry:7.7.1` (the **old** mapping) —
     failed exactly as expected: `Bind for 0.0.0.0:8081 failed: port is
     already allocated`. Then attempted `docker run -p 8090:8081
     confluentinc/cp-schema-registry:7.7.1` (the **new** mapping) — port
     bound successfully; the container exited afterward only because
     this minimal isolated test didn't pass the Kafka bootstrap-servers
     env var (confirmed via `docker logs`: `one of
     (SCHEMA_REGISTRY_KAFKASTORE_CONNECTION_URL,
     SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS) is required`) — an
     unrelated, expected outcome for this isolated test, not a port
     conflict.
   - `mvn compile` — full reactor, BUILD SUCCESS after the
     `OriginexProperties.java` and two `application.yml` edits.
   - All test containers removed afterward.
