# Database Strategy & Data Architecture

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Design Principles

1. **Database-per-Service** — Each bounded context owns its data exclusively
2. **No shared databases** — Cross-service queries via events/CQRS read models
3. **Polyglot persistence** — Right storage for right workload
4. **Multi-tenancy at data layer** — Row-Level Security (RLS) for pool model
5. **Immutability for financial data** — Append-only where possible
6. **Encryption at rest** — AWS KMS managed keys
7. **Point-in-time recovery** — 35-day retention for all production databases

---

## 2. Storage Strategy Per Service

| Service | Primary Store | Justification | CQRS Read Store | Cache |
|---------|--------------|---------------|-----------------|-------|
| customer-service | PostgreSQL 16 | ACID for profile; JSONB for flexible attributes | OpenSearch (search) | Redis (profile cache) |
| iam-service | PostgreSQL 16 | Keycloak standard; ACID for credentials | — | Redis (sessions, tokens) |
| los-service | PostgreSQL 16 | Complex workflow state; ACID for application state | OpenSearch (application search) | Redis (eligibility cache) |
| bre-service | PostgreSQL 16 | Rule versioning; ACID for rule sets | — | Redis (hot rule cache) |
| lms-service | PostgreSQL 16 | Loan lifecycle; ACID for schedule mutations | OpenSearch (loan search) | Redis (schedule cache) |
| ledger-service | PostgreSQL 16 (Event Store) | **Event-sourced**; append-only journal entries | PostgreSQL (materialized balances) | Redis (balance cache) |
| payment-service | PostgreSQL 16 | ACID for payment state; idempotency | — | Redis (payment status) |
| collection-service | PostgreSQL 16 | Workflow state; case management | OpenSearch (case search) | Redis (DPD cache) |
| notification-service | PostgreSQL 16 + Redis | Template storage; delivery tracking | — | Redis (rate limits, queues) |
| reporting-service | OpenSearch | Primary analytical store; time-series | — | Redis (dashboard cache) |
| tenant-service | PostgreSQL 16 | ACID for tenant config | — | Redis (config hot cache) |
| audit-service | OpenSearch + S3 | Append-only; full-text search; long retention | — | — |
| partner-service | Redis | Response caching; circuit breaker state | — | Redis (primary store) |
| config-service | PostgreSQL 16 | Versioned configuration; ACID | — | Redis (hot config) |

---

## 3. Multi-Tenancy Data Isolation

### 3.1 Strategy: Hybrid Pool Model

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    MULTI-TENANCY DATA ISOLATION                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  STRATEGY: Hybrid (Pool for most, Schema-per-tenant for large partners)      │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │  POOL MODEL (Default — 90% of tenants)                          │         │
│  │                                                                  │         │
│  │  PostgreSQL Instance                                             │         │
│  │  └── originex_los_db                                             │         │
│  │      ├── Table: loan_applications                                │         │
│  │      │   ├── tenant_id (NOT NULL, indexed)                       │         │
│  │      │   ├── application_id                                      │         │
│  │      │   └── ... (all rows for all tenants)                      │         │
│  │      │                                                           │         │
│  │      └── Row-Level Security (RLS) Policy:                        │         │
│  │          CREATE POLICY tenant_isolation ON loan_applications      │         │
│  │          USING (tenant_id = current_setting('app.tenant_id'));    │         │
│  │                                                                  │         │
│  │  Benefits: Lower cost, simpler operations, shared connection pool│         │
│  │  Risks: Noisy neighbor (mitigated by resource limits)            │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │  SILO MODEL (Large tenants — top 10% by volume)                 │         │
│  │                                                                  │         │
│  │  Dedicated PostgreSQL Instance per tenant                        │         │
│  │  └── tenant_xyz_los_db                                           │         │
│  │      ├── Table: loan_applications                                │         │
│  │      │   └── ... (only this tenant's data)                       │         │
│  │      │                                                           │         │
│  │  Benefits: Full isolation, independent scaling, compliance       │         │
│  │  Risks: Higher cost, operational complexity                      │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  TENANT ROUTING:                                                             │
│  • TenantContext resolved from JWT claim (X-Tenant-Id)                       │
│  • DataSource routing via AbstractRoutingDataSource                          │
│  • Pool tenants → shared datasource with RLS                                 │
│  • Silo tenants → dedicated datasource                                       │
│  • Routing decision from tenant-service config (cached in Redis)             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Row-Level Security Implementation

```sql
-- Enable RLS on all multi-tenant tables
ALTER TABLE loan_applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE loan_applications FORCE ROW LEVEL SECURITY;

-- Create policy
CREATE POLICY tenant_isolation_policy ON loan_applications
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

-- Application sets tenant context per connection
SET app.tenant_id = 'tenant-uuid-here';

-- All subsequent queries automatically filtered
SELECT * FROM loan_applications; -- Only returns current tenant's rows
```

---

## 4. Ledger — Event Store Design

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    LEDGER EVENT STORE SCHEMA                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │  Table: ledger_events (Append-Only, Partitioned by month)       │         │
│  │  ──────────────────────────────────────────────────────────     │         │
│  │  event_id          UUID PRIMARY KEY (UUIDv7)                    │         │
│  │  aggregate_id      UUID NOT NULL (account_id)                   │         │
│  │  aggregate_type    VARCHAR(100) NOT NULL                        │         │
│  │  event_type        VARCHAR(200) NOT NULL                        │         │
│  │  event_version     INTEGER NOT NULL                             │         │
│  │  sequence_number   BIGINT NOT NULL                              │         │
│  │  tenant_id         UUID NOT NULL                                │         │
│  │  payload           BYTEA NOT NULL (Protobuf serialized)         │         │
│  │  metadata          JSONB NOT NULL                               │         │
│  │  created_at        TIMESTAMP WITH TIME ZONE NOT NULL            │         │
│  │  correlation_id    UUID NOT NULL                                │         │
│  │  causation_id      UUID                                         │         │
│  │                                                                  │         │
│  │  UNIQUE (aggregate_id, sequence_number)                         │         │
│  │  INDEX ON (tenant_id, aggregate_id, sequence_number)            │         │
│  │  PARTITION BY RANGE (created_at) -- monthly partitions          │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │  Table: account_snapshots (Materialized State)                  │         │
│  │  ──────────────────────────────────────────────────────────     │         │
│  │  account_id        UUID PRIMARY KEY                             │         │
│  │  tenant_id         UUID NOT NULL                                │         │
│  │  account_type      VARCHAR(50) NOT NULL                         │         │
│  │  balance           NUMERIC(19,4) NOT NULL                       │         │
│  │  currency          VARCHAR(3) NOT NULL                          │         │
│  │  last_event_seq    BIGINT NOT NULL                              │         │
│  │  snapshot_at       TIMESTAMP WITH TIME ZONE NOT NULL            │         │
│  │  version           BIGINT NOT NULL (optimistic locking)         │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  EVENT SOURCING MECHANICS:                                                   │
│  • Write: Append event to ledger_events                                      │
│  • Read (hot path): Read from account_snapshots (cached in Redis)            │
│  • Read (rebuild): Replay events from ledger_events for aggregate            │
│  • Snapshot: Updated after every N events (configurable, default 100)        │
│  • Consistency: Optimistic concurrency on sequence_number                    │
│  • Compaction: Never (immutable, regulatory requirement)                     │
│                                                                              │
│  WHY EVENT SOURCING FOR LEDGER:                                              │
│  1. Complete audit trail (regulatory requirement)                             │
│  2. Immutability matches accounting principles (no delete/update)            │
│  3. Temporal queries (balance at any point in time)                           │
│  4. Event replay for reconciliation and debugging                            │
│  5. Natural fit for CQRS (write events, read projections)                    │
│  6. 500M+ txns/day maps well to append-only workload                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. LMS — Core Schema Design

```sql
-- Loan aggregate
CREATE TABLE loans (
    loan_id             UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL,
    customer_id         UUID NOT NULL,
    application_id      UUID NOT NULL,
    product_code        VARCHAR(50) NOT NULL,
    status              VARCHAR(30) NOT NULL,
    sanctioned_amount   NUMERIC(19,4) NOT NULL,
    disbursed_amount    NUMERIC(19,4) NOT NULL DEFAULT 0,
    outstanding_principal NUMERIC(19,4) NOT NULL DEFAULT 0,
    outstanding_interest NUMERIC(19,4) NOT NULL DEFAULT 0,
    interest_rate       NUMERIC(8,6) NOT NULL,
    rate_type           VARCHAR(20) NOT NULL, -- FIXED, FLOATING
    tenure_months       INTEGER NOT NULL,
    disbursement_date   DATE,
    maturity_date       DATE,
    next_due_date       DATE,
    dpd                 INTEGER NOT NULL DEFAULT 0,
    npa_date            DATE,
    currency            VARCHAR(3) NOT NULL DEFAULT 'INR',
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    
    CONSTRAINT chk_amount CHECK (sanctioned_amount > 0),
    CONSTRAINT chk_rate CHECK (interest_rate >= 0)
);

-- Partitioned by tenant for large tenants, indexed for pool
CREATE INDEX idx_loans_tenant_status ON loans(tenant_id, status);
CREATE INDEX idx_loans_customer ON loans(tenant_id, customer_id);
CREATE INDEX idx_loans_dpd ON loans(tenant_id, dpd) WHERE dpd > 0;
CREATE INDEX idx_loans_next_due ON loans(tenant_id, next_due_date);

-- Repayment schedule
CREATE TABLE repayment_schedule (
    installment_id      UUID PRIMARY KEY,
    loan_id             UUID NOT NULL REFERENCES loans(loan_id),
    tenant_id           UUID NOT NULL,
    installment_number  INTEGER NOT NULL,
    due_date            DATE NOT NULL,
    principal_amount    NUMERIC(19,4) NOT NULL,
    interest_amount     NUMERIC(19,4) NOT NULL,
    total_amount        NUMERIC(19,4) NOT NULL,
    paid_principal      NUMERIC(19,4) NOT NULL DEFAULT 0,
    paid_interest       NUMERIC(19,4) NOT NULL DEFAULT 0,
    paid_charges        NUMERIC(19,4) NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL, -- UPCOMING, DUE, PAID, OVERDUE, PARTIALLY_PAID
    paid_date           DATE,
    
    UNIQUE (loan_id, installment_number)
);

CREATE INDEX idx_schedule_due ON repayment_schedule(tenant_id, due_date, status);

-- Outbox table for event publishing
CREATE TABLE outbox_events (
    event_id            UUID PRIMARY KEY,
    aggregate_type      VARCHAR(100) NOT NULL,
    aggregate_id        UUID NOT NULL,
    event_type          VARCHAR(200) NOT NULL,
    payload             BYTEA NOT NULL,
    metadata            JSONB NOT NULL,
    tenant_id           UUID NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at        TIMESTAMP WITH TIME ZONE,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX idx_outbox_pending ON outbox_events(status, created_at) 
    WHERE status = 'PENDING';
```

---

## 6. Partitioning Strategy

| Table | Partition Strategy | Partition Key | Rationale |
|-------|-------------------|---------------|-----------|
| ledger_events | RANGE (monthly) | created_at | Time-series; archive old partitions to S3 |
| loans | LIST (by tenant) | tenant_id | Large tenants get own partition; enables silo migration |
| repayment_schedule | RANGE (monthly) | due_date | Efficient EOD queries by due date range |
| audit_events | RANGE (monthly) | created_at | Time-series; retention-based drop |
| outbox_events | RANGE (daily) | created_at | Short retention; drop after processing |
| payment_orders | RANGE (monthly) | created_at | Archival; reconciliation by date |

---

## 7. Indexing Strategy

### 7.1 Index Design Principles

1. **Composite indexes** for multi-column WHERE clauses (leftmost prefix rule)
2. **Partial indexes** for filtered queries (e.g., `WHERE status = 'ACTIVE'`)
3. **Covering indexes** (INCLUDE) for hot read paths to avoid heap access
4. **No over-indexing** — each index costs write performance
5. **Regular ANALYZE** and index usage monitoring

### 7.2 Critical Indexes

```sql
-- Hot path: Fetch active loans for a customer
CREATE INDEX idx_loans_customer_active ON loans(tenant_id, customer_id, status)
    INCLUDE (sanctioned_amount, outstanding_principal, next_due_date)
    WHERE status IN ('ACTIVE', 'OVERDUE');

-- Hot path: EOD interest accrual (all active loans)
CREATE INDEX idx_loans_accrual ON loans(tenant_id, status, disbursement_date)
    INCLUDE (outstanding_principal, interest_rate, rate_type)
    WHERE status = 'ACTIVE';

-- Hot path: Due date processing
CREATE INDEX idx_schedule_due_processing ON repayment_schedule(due_date, status)
    INCLUDE (loan_id, total_amount)
    WHERE status IN ('UPCOMING', 'DUE');

-- Hot path: Delinquency detection
CREATE INDEX idx_loans_delinquent ON loans(tenant_id, dpd, npa_date)
    WHERE dpd > 0;
```

---

## 8. Connection Pooling

```yaml
# HikariCP Configuration (per service)
spring:
  datasource:
    hikari:
      maximum-pool-size: 20          # Matches CPU cores × 2 + disk spindles
      minimum-idle: 5
      idle-timeout: 300000           # 5 minutes
      max-lifetime: 1800000          # 30 minutes
      connection-timeout: 5000       # 5 seconds
      validation-timeout: 3000       # 3 seconds
      leak-detection-threshold: 60000 # 1 minute
      pool-name: ${spring.application.name}-pool
```

**Connection Pool Sizing Formula:**
```
connections = (core_count * 2) + effective_spindle_count
For EKS pods with 2 vCPU: 2 * 2 + 1 = 5 per pod
With 4 pods: 20 connections to RDS (well within RDS limits)
```

---

## 9. Read Replicas & CQRS

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CQRS READ MODEL STRATEGY                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  WRITE PATH                          READ PATH                               │
│  ──────────                          ─────────                               │
│  ┌──────────┐                       ┌──────────────┐                        │
│  │  Service  │                       │   Reporting   │                        │
│  │  (Write)  │                       │   Service     │                        │
│  └─────┬────┘                       └──────┬───────┘                        │
│        │                                    │                                │
│        ▼                                    ▼                                │
│  ┌──────────┐    CDC / Events       ┌──────────────┐                        │
│  │PostgreSQL│ ──────────────────►   │  OpenSearch   │                        │
│  │ (Primary)│    Kafka Connect      │ (Read Model)  │                        │
│  └──────────┘                       └──────────────┘                        │
│                                                                              │
│  STRATEGIES PER CONTEXT:                                                     │
│                                                                              │
│  LMS → OpenSearch:                                                           │
│  • Loan search (full-text, filters, aggregations)                            │
│  • Portfolio dashboards (pre-computed aggregations)                           │
│  • Customer loan history (denormalized view)                                 │
│  • Latency: < 5 seconds from write to searchable                            │
│                                                                              │
│  Ledger → OpenSearch/TimescaleDB:                                            │
│  • Transaction search                                                        │
│  • Account statement generation                                              │
│  • Trial balance (pre-computed)                                              │
│  • Regulatory reporting aggregations                                         │
│                                                                              │
│  Collections → OpenSearch:                                                   │
│  • Case search and filtering                                                 │
│  • Agent assignment dashboards                                               │
│  • Recovery analytics                                                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 10. Backup & Recovery Strategy

| Database | Backup Type | Frequency | Retention | RTO | RPO |
|----------|-------------|-----------|-----------|-----|-----|
| PostgreSQL (RDS) | Automated snapshots | Continuous (PITR) | 35 days | 5 min | 0 (sync replica) |
| PostgreSQL (RDS) | Cross-region snapshot | Every 6 hours | 7 days | 30 min | 6 hours |
| Redis | RDB snapshots | Every 1 hour | 24 hours | 5 min | 1 hour |
| OpenSearch | Index snapshots to S3 | Daily | 30 days | 30 min | 24 hours |
| Kafka | Log retention | N/A | 30 days (hot), indefinite (S3 tiered) | N/A | 0 |
| S3 (Documents) | Cross-region replication | Real-time | Indefinite | 0 | 0 |

---

## 11. Data Lifecycle Management

```
┌────────────────────────────────────────────────────────────────────────┐
│  DATA LIFECYCLE                                                         │
├────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  HOT (< 1 year)     WARM (1-3 years)    COLD (3-8 years)    ARCHIVE   │
│  ──────────────     ────────────────     ───────────────     ────────  │
│  PostgreSQL         PostgreSQL (RO)      S3 (Parquet)        Glacier   │
│  Redis cache        Read Replicas        Athena queryable    8+ years  │
│  OpenSearch         OpenSearch (ILM)     Redshift Spectrum              │
│  Full performance   Reduced IOPS         Cost-optimized                │
│                                                                         │
│  RETENTION REQUIREMENTS:                                                │
│  • Financial transactions: 8 years (regulatory)                         │
│  • KYC documents: Account lifetime + 5 years                           │
│  • Audit logs: 8 years                                                  │
│  • Application data: 5 years post-closure                              │
│  • Consent records: Until withdrawal + 1 year                          │
│  • Communication logs: 3 years                                          │
│                                                                         │
└────────────────────────────────────────────────────────────────────────┘
```
