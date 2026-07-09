# Event Architecture — Kafka Topology & Event Taxonomy

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Kafka Cluster Topology

### 1.1 Production Cluster Specifications

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    KAFKA CLUSTER (Production)                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Cluster: originex-prod (Strimzi Operator on EKS)                            │
│                                                                              │
│  Brokers: 6 (2 per AZ, 3 AZs)                                               │
│  ┌───────────┐  ┌───────────┐  ┌───────────┐                               │
│  │  AZ-1a    │  │  AZ-1b    │  │  AZ-1c    │                               │
│  │ broker-0  │  │ broker-2  │  │ broker-4  │                               │
│  │ broker-1  │  │ broker-3  │  │ broker-5  │                               │
│  └───────────┘  └───────────┘  └───────────┘                               │
│                                                                              │
│  Instance: i3.2xlarge (8 vCPU, 61 GB RAM, 1.9 TB NVMe)                      │
│  Replication Factor: 3 (min.insync.replicas = 2)                             │
│  Acks: all (for financial topics)                                            │
│                                                                              │
│  Supporting Components:                                                      │
│  ├── Schema Registry: 3 instances (HA)                                       │
│  ├── Kafka Connect: 6 workers (3 source CDC, 3 sink)                         │
│  ├── MirrorMaker 2: Cross-region replication                                 │
│  └── Kafka UI: 1 instance (operational visibility)                           │
│                                                                              │
│  CAPACITY PLANNING:                                                          │
│  • Target throughput: 500M+ events/day ≈ 6,000 events/second average         │
│  • Peak throughput: 50,000 events/second (EOD burst)                         │
│  • Message size: avg 2 KB (Protobuf), max 1 MB                               │
│  • Storage per broker: ~500 GB (7-day hot retention)                         │
│  • Network: 500 MB/s aggregate throughput                                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Topic Naming Convention

```
Format: originex.{domain}.{entity}.{event-category}

Examples:
  originex.los.applications.events         — Application lifecycle events
  originex.los.applications.commands       — Commands to LOS
  originex.lms.loans.events                — Loan lifecycle events
  originex.lms.loans.commands              — Commands to LMS
  originex.ledger.journal-entries.events   — Ledger postings
  originex.payments.orders.events          — Payment events
  originex.payments.orders.commands        — Payment commands
  originex.collections.cases.events        — Collection case events
  originex.notifications.requests.commands — Notification requests
  originex.cdc.los.outbox                  — CDC from LOS outbox table
  originex.cdc.lms.outbox                  — CDC from LMS outbox table

DLQ Topics:
  originex.{domain}.{entity}.{category}.dlq
  originex.{domain}.{entity}.{category}.retry-1

Internal/System Topics:
  originex.platform.health-checks
  originex.platform.config-changes
  originex.platform.audit-events
```

### 1.3 Topic Configuration Matrix

| Topic | Partitions | Retention | Cleanup | Compression | Key |
|-------|-----------|-----------|---------|-------------|-----|
| originex.los.applications.events | 32 | 30 days | delete | lz4 | application_id |
| originex.lms.loans.events | 64 | 30 days | delete | lz4 | loan_id |
| originex.ledger.journal-entries.events | 128 | 90 days + S3 tier | delete | lz4 | account_id |
| originex.payments.orders.events | 32 | 30 days | delete | lz4 | payment_order_id |
| originex.payments.orders.commands | 32 | 7 days | delete | lz4 | payment_order_id |
| originex.collections.cases.events | 16 | 30 days | delete | lz4 | case_id |
| originex.notifications.requests.commands | 16 | 3 days | delete | lz4 | notification_id |
| originex.platform.audit-events | 32 | 90 days + S3 | delete | zstd | entity_id |
| originex.cdc.*.outbox | (matches source) | 7 days | delete | lz4 | aggregate_id |

**Partition Count Rationale:**
- Ledger (128): Highest throughput (500M+/day), parallelism for Flink consumers
- LMS (64): 5M active loans, high event rate, parallel EOD processing
- LOS (32): 100K applications/day, moderate parallelism
- Others (16-32): Lower volume, adequate parallelism

---

## 2. Event Taxonomy

### 2.1 Event Envelope (Standard Metadata)

```protobuf
// Base event envelope — all events wrapped in this
message EventEnvelope {
  string event_id = 1;              // UUIDv7
  string event_type = 2;            // Fully qualified: originex.lms.LoanDisbursed
  int32 event_version = 3;          // Schema version (1, 2, 3...)
  string aggregate_type = 4;        // e.g., "Loan", "Account"
  string aggregate_id = 5;          // Entity ID
  string tenant_id = 6;             // Multi-tenancy
  google.protobuf.Timestamp occurred_at = 7;  // When the event happened
  google.protobuf.Timestamp published_at = 8; // When it was published
  string correlation_id = 9;        // Request chain correlation
  string causation_id = 10;         // Direct cause event ID
  string actor_id = 11;             // Who triggered this
  string actor_type = 12;           // USER, SYSTEM, SCHEDULER
  map<string, string> metadata = 13; // Extensible metadata
  bytes payload = 14;               // Domain event payload (Protobuf)
}
```

### 2.2 Event Categories

| Category | Description | Example | Guarantee |
|----------|-------------|---------|-----------|
| **Domain Events** | Business state changes | LoanDisbursed, PaymentReceived | Exactly-once (Outbox) |
| **Integration Events** | Cross-context notifications | CustomerKYCCompleted | At-least-once |
| **Commands** | Requests for action | InitiatePayment, SendNotification | At-least-once |
| **System Events** | Platform operational | HealthCheckFailed, ConfigChanged | Best-effort |
| **Audit Events** | Compliance trail | DataAccessed, RoleChanged | Exactly-once |

### 2.3 Full Event Catalog

#### Loan Origination Events

| Event | Aggregate | Producer | Consumers | Partition Key |
|-------|-----------|----------|-----------|---------------|
| ApplicationSubmitted | LoanApplication | LOS | Audit, Notification, Analytics | application_id |
| ApplicationAssigned | LoanApplication | LOS | Notification | application_id |
| DocumentUploaded | LoanApplication | LOS | Document Service, Audit | application_id |
| DocumentVerified | LoanApplication | LOS | LOS (self), Audit | application_id |
| CreditCheckInitiated | LoanApplication | LOS | Partner Service, Audit | application_id |
| CreditCheckCompleted | LoanApplication | LOS | LOS (self), Analytics | application_id |
| EligibilityDetermined | LoanApplication | LOS | Analytics | application_id |
| ApplicationApproved | LoanApplication | LOS | LMS, Notification, Analytics | application_id |
| ApplicationRejected | LoanApplication | LOS | Notification, Analytics | application_id |
| OfferGenerated | LoanApplication | LOS | Notification, Analytics | application_id |
| OfferAccepted | LoanApplication | LOS | LMS, Notification | application_id |

#### Loan Management Events

| Event | Aggregate | Producer | Consumers | Partition Key |
|-------|-----------|----------|-----------|---------------|
| LoanCreated | Loan | LMS | Ledger, Notification, Analytics | loan_id |
| DisbursementRequested | Loan | LMS | Payment Service | loan_id |
| LoanDisbursed | Loan | LMS | Ledger, Notification, Analytics | loan_id |
| ScheduleGenerated | Loan | LMS | Notification, Analytics | loan_id |
| InstallmentDue | Loan | LMS | Notification, Collections | loan_id |
| RepaymentReceived | Loan | LMS | Ledger, Collections, Notification | loan_id |
| RepaymentAllocated | Loan | LMS | Ledger, Notification | loan_id |
| InterestAccrued | Loan | Flink | Ledger | loan_id |
| PrepaymentProcessed | Loan | LMS | Ledger, Notification | loan_id |
| LoanForeclosed | Loan | LMS | Ledger, Notification, Analytics | loan_id |
| LoanRestructured | Loan | LMS | Ledger, Notification, Analytics | loan_id |
| NPAClassified | Loan | Flink | Collections, Notification, Analytics | loan_id |
| LoanMatured | Loan | LMS | Ledger, Notification | loan_id |

#### Ledger Events

| Event | Aggregate | Producer | Consumers | Partition Key |
|-------|-----------|----------|-----------|---------------|
| AccountOpened | Account | Ledger | Analytics | account_id |
| JournalEntryPosted | JournalEntry | Ledger | Reporting, Analytics | account_id |
| JournalEntryReversed | JournalEntry | Ledger | Reporting, Analytics | account_id |
| ReconciliationCompleted | Reconciliation | Ledger | Analytics, Audit | reconciliation_id |
| BalanceSnapshotCreated | Account | Ledger | Reporting | account_id |

#### Payment Events

| Event | Aggregate | Producer | Consumers | Partition Key |
|-------|-----------|----------|-----------|---------------|
| PaymentOrderCreated | PaymentOrder | Payment | Audit | payment_order_id |
| PaymentInitiated | PaymentOrder | Payment | LMS, Audit | payment_order_id |
| PaymentSucceeded | PaymentOrder | Payment | LMS, Ledger, Notification | payment_order_id |
| PaymentFailed | PaymentOrder | Payment | LMS, Notification, Collections | payment_order_id |
| PaymentReversed | PaymentOrder | Payment | LMS, Ledger, Notification | payment_order_id |
| MandateRegistered | Mandate | Payment | LMS, Notification | mandate_id |
| MandateActivated | Mandate | Payment | LMS | mandate_id |

#### Collections Events

| Event | Aggregate | Producer | Consumers | Partition Key |
|-------|-----------|----------|-----------|---------------|
| DelinquencyDetected | CollectionCase | Collections | Notification, Analytics | case_id |
| CollectionCaseOpened | CollectionCase | Collections | Notification, Audit | case_id |
| CollectionActionExecuted | CollectionCase | Collections | Notification, Audit | case_id |
| PromiseToPayReceived | CollectionCase | Collections | Notification | case_id |
| SettlementOffered | CollectionCase | Collections | Notification, LMS | case_id |
| SettlementAccepted | CollectionCase | Collections | LMS, Ledger, Notification | case_id |

---

## 3. Event Versioning & Schema Evolution

### 3.1 Versioning Strategy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SCHEMA EVOLUTION STRATEGY                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  RULES:                                                                      │
│  1. NEVER remove a field (deprecate only)                                    │
│  2. NEVER change a field's type                                              │
│  3. NEVER reuse a field number                                               │
│  4. New fields MUST be optional (proto3 default)                             │
│  5. Schema Registry enforces BACKWARD compatibility mode                     │
│  6. Breaking changes require new topic + migration                           │
│                                                                              │
│  COMPATIBLE CHANGES (no version bump):                                       │
│  • Add new optional field                                                    │
│  • Add new enum value                                                        │
│  • Add new oneof member                                                      │
│                                                                              │
│  INCOMPATIBLE CHANGES (new version, new topic):                              │
│  • Remove field                                                              │
│  • Change field type                                                         │
│  • Rename field                                                              │
│  • Change semantics of existing field                                        │
│                                                                              │
│  MIGRATION STRATEGY (for breaking changes):                                  │
│  1. Create v2 topic: originex.lms.loans.events.v2                            │
│  2. Deploy dual-write: produce to both v1 and v2                             │
│  3. Migrate consumers one by one to v2                                       │
│  4. Monitor v1 consumer lag → zero                                           │
│  5. Stop dual-write, deprecate v1 topic                                      │
│  6. Delete v1 topic after retention expires                                  │
│                                                                              │
│  VERSION IN EVENT:                                                           │
│  • event_version field in EventEnvelope                                      │
│  • Consumer checks version and uses appropriate deserializer                 │
│  • Protobuf backward compatibility handles minor versions transparently      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Schema Registry Configuration

```yaml
# Compatibility mode per subject
schemas:
  originex.lms.loans.events-value:
    compatibility: BACKWARD
  originex.ledger.journal-entries.events-value:
    compatibility: FULL  # Strictest for financial data
  originex.notifications.requests.commands-value:
    compatibility: BACKWARD
```

---

## 4. Event Ordering Guarantees

| Guarantee | Mechanism | Use Case |
|-----------|-----------|----------|
| Per-aggregate ordering | Partition by aggregate_id | Loan events in order per loan |
| Per-tenant ordering | Partition by tenant_id | Tenant-wide ordering (where needed) |
| Global ordering | Single partition (avoid!) | Not used — scalability bottleneck |
| Causal ordering | Causation ID + consumer-side reordering | Cross-aggregate dependencies |

**Implementation:**
```java
// Producer ensures ordering by partitioning on loan_id
kafkaTemplate.send(
    "originex.lms.loans.events",
    event.getLoanId(),    // Key = partition key
    eventEnvelope        // Value = serialized event
);

// Consumer: Ordered processing within partition
@KafkaListener(
    topics = "originex.lms.loans.events",
    concurrency = "32",  // = number of partitions
    properties = {
        "max.poll.records=500",
        "enable.auto.commit=false"
    }
)
public void handleLoanEvent(ConsumerRecord<String, EventEnvelope> record) {
    // Process in order within partition (per loan_id)
    // Manual commit after successful processing
}
```

---

## 5. Exactly-Once Semantics (EOS)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    EXACTLY-ONCE PROCESSING                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PRODUCER SIDE (Outbox + CDC):                                               │
│  • Business logic + outbox insert in single DB transaction                   │
│  • Debezium reads WAL → produces to Kafka with exactly-once                  │
│  • Kafka transactions enabled on Connect worker                              │
│  • Result: Event published exactly once per business action                  │
│                                                                              │
│  CONSUMER SIDE (Inbox Pattern):                                              │
│  • Consumer reads event                                                      │
│  • Check inbox table for event_id (deduplication)                            │
│  • Process + insert inbox + produce outbox in single DB transaction          │
│  • Commit Kafka offset after DB commit                                       │
│  • If crash before offset commit → event redelivered → inbox deduplicates    │
│  • Result: Business logic executes exactly once per event                    │
│                                                                              │
│  END-TO-END GUARANTEE:                                                       │
│  Outbox (exactly-once produce) + Inbox (exactly-once consume) =              │
│  Effectively exactly-once end-to-end for financial flows                     │
│                                                                              │
│  WHERE EXACTLY-ONCE IS REQUIRED:                                             │
│  ✅ Ledger postings (financial correctness)                                  │
│  ✅ Payment processing (no duplicate disbursements)                          │
│  ✅ Loan state transitions (no duplicate state changes)                      │
│  ✅ Interest accrual (deterministic, no double-counting)                     │
│                                                                              │
│  WHERE AT-LEAST-ONCE IS ACCEPTABLE:                                          │
│  ✓ Notifications (duplicate SMS is acceptable, idempotent at provider)       │
│  ✓ Analytics events (duplicate events aggregated/deduplicated)               │
│  ✓ Audit events (duplicate audit entries are acceptable)                     │
│  ✓ Cache invalidation (duplicate invalidation is safe)                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Event Replay & Recovery

### 6.1 Replay Strategy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    EVENT REPLAY CAPABILITY                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIOS REQUIRING REPLAY:                                                 │
│  1. Bug fix — consumer had incorrect logic, need to reprocess                │
│  2. New consumer — needs full history to build read model                    │
│  3. Disaster recovery — rebuild state from events                            │
│  4. Reconciliation — verify derived state matches event history              │
│                                                                              │
│  REPLAY MECHANISMS:                                                          │
│                                                                              │
│  1. Kafka Log (< 30 days):                                                   │
│     • Reset consumer group offset to earliest                                │
│     • Consumer replays from specific timestamp                               │
│     • Inbox pattern prevents duplicate processing                            │
│                                                                              │
│  2. Event Store (Ledger — unlimited):                                        │
│     • Replay from ledger_events table                                        │
│     • Rebuild any account balance from scratch                               │
│     • Verify snapshot correctness                                            │
│                                                                              │
│  3. S3 Archive (> 30 days):                                                  │
│     • Tiered storage via Kafka S3 Sink Connector                             │
│     • Replay by republishing from S3 to temporary topic                      │
│     • Flink batch job for historical reprocessing                            │
│                                                                              │
│  REPLAY SAFETY:                                                              │
│  • Inbox table prevents duplicate business effects                           │
│  • Replay flag in metadata to distinguish from live events                   │
│  • Notifications suppressed during replay (no duplicate SMS)                 │
│  • Analytics marked as backfill                                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Flink Stream Processing Jobs

### 7.1 Job Catalog

| Job | Input Topic | Output Topic | Processing | State |
|-----|-------------|--------------|-----------|-------|
| interest-accrual | originex.lms.loans.events | originex.lms.loans.events (InterestAccrued) | Daily accrual calculation per active loan | Keyed by loan_id |
| delinquency-aging | originex.lms.loans.events | originex.collections.cases.events | DPD calculation, NPA classification | Keyed by loan_id |
| payment-matching | originex.payments.orders.events | originex.lms.loans.events | Match payments to loans/installments | Keyed by loan_id |
| portfolio-risk-agg | originex.lms.loans.events | originex.platform.analytics | Real-time portfolio metrics | Keyed by tenant_id |
| reconciliation | originex.payments.orders.events + bank files | originex.ledger.reconciliation.events | Match bank entries to payments | Windowed |
| fraud-detection | Multiple input topics | originex.platform.alerts | Pattern detection, velocity checks | Keyed by customer_id |

### 7.2 Interest Accrual Job Design

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    FLINK: INTEREST ACCRUAL JOB                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  TRIGGER: Event-driven (not batch)                                           │
│  • Processes LoanDisbursed, RepaymentAllocated, RateChanged events           │
│  • Timer-based trigger at EOD (event-time watermark)                         │
│                                                                              │
│  CALCULATION:                                                                │
│  Daily Interest = (Outstanding Principal × Annual Rate) / Days in Year       │
│  • 30/360, Actual/365, Actual/360 day count conventions (configurable)       │
│  • BigDecimal with HALF_EVEN rounding                                        │
│  • Deterministic: same inputs always produce same output                     │
│                                                                              │
│  STATE MANAGEMENT:                                                           │
│  • Keyed state per loan_id                                                   │
│  • State: outstanding_principal, rate, accrued_interest, last_accrual_date   │
│  • Checkpointing: Every 30 seconds (RocksDB backend)                         │
│  • Savepoints: Before deployment, for rollback                               │
│                                                                              │
│  OUTPUT: InterestAccrued event → Ledger (journal entry posting)              │
│                                                                              │
│  SCALE:                                                                      │
│  • 5M active loans × 1 accrual event/day = 5M events in EOD window           │
│  • Parallelism: 64 (matches Kafka partitions)                                │
│  • Processing time: < 30 minutes for full portfolio                          │
│                                                                              │
│  EXACTLY-ONCE:                                                               │
│  • Flink Kafka connector with exactly-once sink                              │
│  • Checkpointing ensures no lost or duplicate accruals                       │
│  • Downstream inbox pattern for additional safety                            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Change Data Capture (CDC) Configuration

```yaml
# Debezium PostgreSQL Source Connector (LMS)
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaConnector
metadata:
  name: lms-outbox-cdc
spec:
  class: io.debezium.connector.postgresql.PostgresConnector
  tasksMax: 3
  config:
    database.hostname: lms-db.cluster-xxx.ap-south-1.rds.amazonaws.com
    database.port: "5432"
    database.user: ${DEBEZIUM_USER}
    database.password: ${DEBEZIUM_PASSWORD}
    database.dbname: originex_lms
    database.server.name: lms
    schema.include.list: public
    table.include.list: public.outbox_events
    transforms: outbox
    transforms.outbox.type: io.debezium.transforms.outbox.EventRouter
    transforms.outbox.table.field.event.id: event_id
    transforms.outbox.table.field.event.key: aggregate_id
    transforms.outbox.table.field.event.type: event_type
    transforms.outbox.table.field.event.payload: payload
    transforms.outbox.route.topic.replacement: originex.lms.${routedByValue}.events
    transforms.outbox.table.fields.additional.placement: tenant_id:header
    plugin.name: pgoutput
    slot.name: lms_outbox_slot
    publication.name: lms_outbox_publication
    heartbeat.interval.ms: "10000"
    snapshot.mode: never
```

---

## 9. Correlation & Tracing

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    EVENT CORRELATION                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Every event carries:                                                        │
│  • correlation_id: Traces back to original API request                       │
│  • causation_id: The event that directly caused this event                   │
│  • trace_id: OpenTelemetry trace ID (same as correlation_id typically)        │
│  • span_id: OpenTelemetry span for this specific operation                   │
│                                                                              │
│  EXAMPLE CHAIN:                                                              │
│                                                                              │
│  API Request (correlation_id: abc-123)                                       │
│    └── ApplicationSubmitted (event_id: evt-001, correlation: abc-123)        │
│         └── CreditCheckInitiated (evt-002, correlation: abc-123,             │
│         │                         causation: evt-001)                         │
│         └── CreditCheckCompleted (evt-003, correlation: abc-123,             │
│              │                    causation: evt-002)                         │
│              └── ApplicationApproved (evt-004, correlation: abc-123,          │
│                   │                   causation: evt-003)                     │
│                   └── LoanCreated (evt-005, correlation: abc-123,             │
│                        │           causation: evt-004)                        │
│                        └── DisbursementRequested (evt-006, ...)              │
│                                                                              │
│  QUERYING:                                                                   │
│  • "Show me everything that happened for request abc-123"                    │
│  • Query OpenSearch audit index by correlation_id                            │
│  • Jaeger trace view by trace_id                                             │
│  • Full causal chain reconstruction via causation_id graph                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```
