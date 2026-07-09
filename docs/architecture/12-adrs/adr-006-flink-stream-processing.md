# ADR-006: Apache Flink for Stream Processing over Alternatives

**Status:** ACCEPTED  
**Date:** 2026-07-08  
**Deciders:** Architecture Board, Data Engineering Lead  
**Technical Story:** ORIG-ARCH-006  

---

## Context & Problem Statement

The platform requires complex stream processing for:
- Daily interest accrual across 5M+ active loans (deterministic, exactly-once)
- Real-time delinquency detection and DPD aging
- Payment matching and reconciliation
- Portfolio-level risk aggregation
- Fraud pattern detection (velocity checks)
- Event-driven EOD processing (replacing traditional batch)

These workloads require stateful stream processing with exactly-once guarantees, event-time semantics, and the ability to process millions of events within strict time windows.

## Decision Drivers

* Exactly-once state management (financial correctness)
* Event-time processing (handle late-arriving events correctly)
* Stateful computation (maintain per-loan state for accrual)
* Low latency for real-time use cases (fraud, delinquency)
* High throughput for batch-equivalent workloads (5M loans EOD)
* Savepoint/checkpoint for recovery and deployment
* Mature Java API (team expertise)

## Considered Options

1. **Apache Flink**
2. **Kafka Streams**
3. **Apache Spark Structured Streaming**
4. **Custom Java consumers with state in Redis/PostgreSQL**
5. **Spring Batch (for EOD only)**

## Decision Outcome

**Chosen option:** "Apache Flink" for complex stateful stream processing, with **Kafka Streams** retained for simple stateless enrichment/routing within individual services. This dual approach uses the right tool for each complexity level.

### Consequences

**Good:**
* Exactly-once state with incremental checkpointing (no data loss/duplication)
* Event-time processing handles out-of-order events correctly
* Savepoints enable zero-downtime deployments (save state → upgrade → restore)
* RocksDB state backend handles state larger than memory
* Scales to millions of keys (one per loan for accrual)
* SQL/Table API for simpler processing (accessible to data engineers)
* Unified batch + stream API (same code for backfill and real-time)

**Bad:**
* Operational complexity (Flink cluster management)
* Additional infrastructure to maintain (JobManager, TaskManagers)
* Learning curve for Flink-specific concepts (watermarks, windowing, checkpoints)
* Debugging distributed state is challenging
* Resource-intensive (memory for RocksDB state, network for checkpointing)

## When to Use Flink vs Kafka Streams

| Use Case | Technology | Rationale |
|----------|-----------|-----------|
| Interest accrual (5M loans) | Flink | Stateful, exactly-once, event-time |
| Delinquency aging (DPD) | Flink | Stateful timers, complex logic |
| Payment reconciliation | Flink | Windowed join between payments and bank files |
| Portfolio risk aggregation | Flink | Complex aggregations across millions of loans |
| Fraud velocity checks | Flink | Windowed counting, pattern detection |
| Simple event routing | Kafka Streams | Stateless, within service boundary |
| Event enrichment (add customer name) | Kafka Streams | Simple join, low complexity |
| CDC transformation | Kafka Streams / Connect SMT | Simple field mapping |

## Pros & Cons of Other Options

### Kafka Streams
* Good: No separate cluster (runs within service JVM)
* Good: Simple deployment model (just another microservice)
* Good: Good for simple stateless/low-state processing
* Bad: State management limited (no incremental checkpoints)
* Bad: Scaling limited to number of Kafka partitions
* Bad: No event-time watermarks (only stream-time)
* Bad: Complex windowed operations are cumbersome
* Bad: No savepoint equivalent for zero-downtime upgrades of stateful apps
* **Decision: Used for simple cases; Flink for complex stateful processing**

### Apache Spark Structured Streaming
* Good: Unified batch + stream
* Good: Large community
* Bad: Micro-batch model (100ms-1s minimum latency)
* Bad: Higher memory requirements
* Bad: Less mature exactly-once (requires careful checkpoint config)
* Bad: JVM memory pressure with large state
* Bad: Not truly real-time (micro-batch windowing)

### Custom Java Consumers + External State
* Good: Full control, no additional infrastructure
* Good: Simple for small scale
* Bad: Must implement exactly-once manually (extremely error-prone)
* Bad: State management in Redis/DB adds latency and failure modes
* Bad: No event-time semantics (must implement manually)
* Bad: No savepoint/checkpoint for recovery
* Bad: Does not scale to 5M stateful keys
* **Decision: Rejected — reimplementing Flink poorly**

### Spring Batch (EOD)
* Good: Familiar Spring ecosystem
* Good: Job scheduling, restart capability
* Bad: Batch-only (not real-time)
* Bad: Cannot detect delinquency in real-time
* Bad: Long processing windows (hours for 5M loans)
* Bad: Architecture shift: Originex uses event-driven EOD, not batch
* **Decision: Rejected — batch paradigm conflicts with event-driven architecture**

## Links

* [Technology Decisions](../01-tech-stack/technology-decisions.md)
* [Event Architecture — Flink Jobs](../06-event-architecture/kafka-topology.md)
