# Phase 0 Verification Checklist

Repeatable, manual + scripted checklist to establish a **before** baseline
prior to any Phase 0 fix, and an **after** result once each Phase 0 commit
lands. Re-run the whole checklist once all 7 Phase 0 commits are in to
confirm the platform starts cleanly end-to-end (the Phase 0 acceptance
criterion in `CLAUDE_ANALYSIS.md` §2).

Some steps only become fully green once specific commits land — that's
expected. Each step below states which commit closes it.

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

**Known gap until commit 3 ("Fix dev database initialization"):**
`originex_bre`, `originex_partner`, `originex_notification` do not exist —
`dev/init-scripts/init-databases.sql` currently creates a different set
(includes `originex_collections`/`originex_iam` for services that don't
exist yet as Maven modules). The script reports these as `[KNOWN GAP]`,
not `[FAIL]`, until commit 3 lands.

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

**⚠️ Port conflict to check for, beyond the template-service item already
in the Phase 0 backlog:** `dev/docker-compose.yml`'s `schema-registry`
container binds **host port 8081**, and `customer-service`'s
`application.yml` also sets `server.port: 8081`. Running the full local
dev stack (`docker compose up`) and `customer-service` at the same time
will fail with an address-already-in-use error on whichever starts second.
This was not caught by the earlier CLAUDE_ANALYSIS.md audit (which only
compared service ports against each other and against Kafka UI) — it only
surfaces when you actually try to run the compose stack and a service
side-by-side, which this checklist now does. Track this alongside the
template-service/Kafka-UI 8080 conflict when commit 5 is scoped; do not
fix silently as part of commit 1.

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
