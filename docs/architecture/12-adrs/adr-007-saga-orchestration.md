# ADR-007: Saga Orchestration for Distributed Transactions

**Status:** ACCEPTED  
**Date:** 2026-07-08  
**Deciders:** Architecture Board  
**Technical Story:** ORIG-ARCH-007  

---

## Context & Problem Statement

In a microservice architecture, operations that span multiple services (e.g., loan disbursement: LMS → Ledger → Payment → Notification) cannot use traditional database transactions. We need a pattern for maintaining data consistency across service boundaries while preserving availability.

Key business flows requiring cross-service consistency:
- Loan disbursement (LMS + Ledger + Payment)
- Repayment allocation (Payment + LMS + Ledger + Collections)
- Loan restructuring (LMS + Ledger + Notification)
- Application approval (LOS + LMS + Customer)

## Decision Drivers

* Data consistency across services without distributed transactions (no 2PC)
* Compensating actions for partial failures
* Visibility into multi-step process state
* Failure handling and recovery
* Auditability of the process
* Complexity management

## Considered Options

1. **Saga — Orchestration (Central coordinator)**
2. **Saga — Choreography (Event-driven, no coordinator)**
3. **Distributed Transactions (2PC/XA)**
4. **Hybrid (Orchestration for complex, Choreography for simple)**

## Decision Outcome

**Chosen option:** "Hybrid — Orchestration for complex multi-step flows, Choreography for simple linear flows" because complex flows (disbursement, restructuring) benefit from centralized state management and visibility, while simple flows (repayment notification) are naturally suited to choreography with less overhead.

### When to Use Orchestration vs Choreography

| Flow | Pattern | Rationale |
|------|---------|-----------|
| Loan Disbursement | Orchestration | 5+ steps, complex compensations, state visibility needed |
| Loan Restructuring | Orchestration | Multiple approval steps, complex rollback |
| Account Opening | Orchestration | KYC + Customer + Account creation coordination |
| Repayment Allocation | Choreography | Linear flow, each service knows what to do |
| Notification Dispatch | Choreography | Fire-and-forget, no compensation needed |
| Delinquency Update | Choreography | Simple event chain |

### Orchestration Implementation

```
Saga Orchestrator (within owning service, e.g., LMS):
┌────────────────────────────────────────────────────────────┐
│  1. Saga state machine persisted in PostgreSQL              │
│  2. Each step: publish command → await reply event          │
│  3. On success: advance to next step                        │
│  4. On failure: execute compensating actions in reverse     │
│  5. On timeout: retry or compensate (configurable)          │
│  6. Terminal states: COMPLETED, COMPENSATED, FAILED         │
└────────────────────────────────────────────────────────────┘

Saga State Table:
  saga_id, saga_type, current_step, status, 
  payload (JSONB), started_at, updated_at, 
  completed_steps (JSONB), compensation_log (JSONB)
```

### Consequences

**Good (Orchestration):**
* Centralized saga state — easy to query "what's the status of disbursement X?"
* Complex compensation logic is explicit and testable
* Timeout handling is straightforward
* Dead saga detection and alerting
* Audit trail of all steps and decisions

**Good (Choreography):**
* Simpler for linear flows (no coordinator overhead)
* More loosely coupled (services don't know about saga)
* Higher throughput (no coordinator bottleneck)

**Bad (Orchestration):**
* Coordinator service becomes a coupling point
* More code to maintain (saga state machine)
* Must handle coordinator crashes (saga state in DB survives)

**Bad (Choreography):**
* Hard to track overall process state across services
* Difficult to implement complex compensations
* "Who's responsible?" becomes unclear
* Debugging distributed choreography is challenging

## Why NOT 2PC/XA

* Blocks resources during voting phase (kills throughput)
* Single coordinator failure blocks all participants
* Not compatible with Kafka (Kafka doesn't support XA)
* Tight coupling between all participants
* Doesn't work across heterogeneous datastores
* **Fundamentally incompatible with microservice availability goals**

## Links

* [Service Interactions](../04-service-interactions/sync-patterns.md)
* [Event Architecture](../06-event-architecture/kafka-topology.md)
