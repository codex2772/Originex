# ADR-002: Event Sourcing for the Ledger Domain

**Status:** ACCEPTED  
**Date:** 2026-07-08  
**Deciders:** Architecture Board, Finance Team Lead  
**Technical Story:** ORIG-ARCH-002  

---

## Context & Problem Statement

The Ledger service must handle 500M+ financial transactions per day while guaranteeing:
- Complete audit trail (regulatory requirement: 8-year retention)
- Immutability (accounting principle: entries are never modified, only reversed)
- Temporal queries (what was the balance at any point in time?)
- Deterministic replay (rebuild state from history without divergence)
- Zero data loss
- Reconciliation capability

Traditional CRUD models require separate audit tables, complex trigger-based change tracking, and cannot naturally support temporal queries or deterministic replay.

## Decision Drivers

* Regulatory requirement for complete, tamper-evident audit trail
* Accounting principle of immutability (no UPDATE/DELETE on financial records)
* Need for temporal queries (point-in-time balance reconstruction)
* 500M+ transactions/day workload (append-optimized writes)
* Reconciliation requires replay capability
* Disaster recovery requires state reconstruction from events

## Considered Options

1. **Full Event Sourcing (append-only event store + materialized projections)**
2. **CRUD + CDC (Change Data Capture) for audit trail**
3. **CRUD + Temporal Tables (PostgreSQL system-versioned)**
4. **Hybrid: Event Sourcing for write, CQRS projection for read**

## Decision Outcome

**Chosen option:** "Full Event Sourcing with CQRS projections" (Option 4 — which is a variant of Option 1 with explicit read-side optimization) because it naturally aligns with accounting principles, provides guaranteed audit trail, supports temporal queries, and handles the high-throughput append workload efficiently.

### Consequences

**Good:**
* Immutable event log IS the audit trail — no separate audit table needed
* Temporal queries natural — replay events up to any point in time
* Balance at any date reconstructable by replaying events
* Append-only writes scale well at 500M+ TPS (no row locking)
* Natural fit for double-entry bookkeeping (each entry is an event)
* Event replay enables reconciliation and bug detection
* Disaster recovery: rebuild entire state from event log

**Bad:**
* Higher storage cost (events + snapshots + projections)
* Eventual consistency between event store and read projections
* More complex query patterns (cannot just SELECT * FROM accounts)
* Snapshot management required for large aggregates
* Schema evolution of events requires careful planning
* Steeper learning curve for developers unfamiliar with ES

**Neutral:**
* PostgreSQL used as event store (not purpose-built like EventStoreDB) — simpler ops
* Snapshots every 100 events to bound replay time
* Read projections (balance table) serve 99% of queries

## Detailed Design

### Write Path (Event Store)

```
Command: PostJournalEntry
  → Validate: total debits == total credits
  → Validate: accounts exist and are active
  → Validate: optimistic concurrency (expected sequence number)
  → Persist: Append JournalEntryPosted event to ledger_events table
  → Update: Snapshot (balance) if threshold reached
  → Publish: Event to Kafka via Outbox pattern
```

### Read Path (CQRS Projection)

```
Query: GetAccountBalance(account_id)
  → Read from account_snapshots table (Redis-cached)
  → Fast path: snapshot is current (last_event_seq matches)
  → Slow path: replay events since last snapshot (rare)
```

### Why NOT Event Sourcing for Other Domains

| Domain | Decision | Rationale |
|--------|----------|-----------|
| LOS | CRUD + Outbox | Application lifecycle is CRUD-friendly; audit via outbox events |
| LMS | CRUD + Outbox | Complex loan aggregate with many updates; ES overhead not justified |
| Customer | CRUD + Outbox | Profile updates are simple; no temporal query need |
| Payments | CRUD + Outbox | Payment state machine is simpler with CRUD; idempotency via unique constraints |
| Collections | CRUD + Outbox | Case management is naturally CRUD |

**Event Sourcing is applied ONLY to the Ledger** where its benefits strongly outweigh the complexity costs.

## Pros & Cons of Other Options

### CRUD + CDC
* Good: Simpler development model
* Good: Developers more familiar
* Bad: CDC audit trail is infrastructure-dependent (Debezium)
* Bad: No temporal queries without significant custom code
* Bad: Cannot replay to rebuild state
* Bad: Audit trail is a derived artifact, not source of truth

### CRUD + Temporal Tables
* Good: PostgreSQL native (system-versioned tables)
* Good: Familiar SQL queries
* Bad: Storage overhead for every row version
* Bad: Cannot easily reconstruct complex aggregate state
* Bad: No event replay capability
* Bad: Temporal queries limited to row-level, not aggregate-level

## Links

* [Database Strategy](../05-database-strategy/data-architecture.md)
* [Event Architecture](../06-event-architecture/kafka-topology.md)
* [Martin Fowler: Event Sourcing](https://martinfowler.com/eaaDev/EventSourcing.html)
