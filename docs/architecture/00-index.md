# Originex — Enterprise Lending-as-a-Service Platform

## Architecture Documentation Index

**Version:** 1.0.0  
**Status:** Phase 1 — Foundation & Architecture  
**Last Updated:** 2026-07-08  
**Classification:** Internal — Confidential  

---

## Platform Overview

Originex is an enterprise-grade, cloud-native B2B **Lending-as-a-Service (LaaS)** platform designed to serve banks, NBFCs, and fintech organizations. The platform provides a unified ecosystem for loan origination, management, accounting, payments, collections, risk assessment, and regulatory compliance.

### Scale Targets

| Metric | Target |
|--------|--------|
| Registered Customers | 20M+ |
| Active Loans | 5M+ |
| Daily Applications | 100K+ |
| Daily Ledger Transactions | 500M+ |
| Concurrent Disbursements | Thousands |
| Repayment Events/Minute | Tens of Thousands |
| Availability | 99.99% (52.6 min/year downtime) |
| API Response Time (p99) | < 500ms |
| Recovery Point Objective (RPO) | 0 (zero data loss) |
| Recovery Time Objective (RTO) | < 5 minutes |

---

## Document Structure & Reading Order

### Audience Guide

| Audience | Start With | Focus On |
|----------|-----------|----------|
| Platform Architects | 01 → 02 → 03 → 12 | Full depth |
| Backend Engineers | 02 → 04 → 06 → 07 | Domain, APIs, Events |
| DevOps/SRE | 10 → 11 → 13 | Infrastructure, Observability |
| Security Engineers | 08 → 09 | Security, Compliance |
| Product/Business | 00 → 02 → 13 | Overview, Domain, Risks |
| Auditors | 09 → 08 → 12 | Compliance, Security, ADRs |

### Document Index

| # | Section | Description |
|---|---------|-------------|
| 01 | [Technology Stack](./01-tech-stack/technology-decisions.md) | Unified stack with rationale matrix |
| 02 | [Domain Model — Bounded Contexts](./02-domain-model/bounded-contexts.md) | DDD context map, all 13 bounded contexts |
| 02a | [Domain Model — LOS](./02-domain-model/los-domain.md) | Loan Origination aggregates, state machine, events |
| 02b | [Domain Model — LMS](./02-domain-model/lms-domain.md) | Loan Management lifecycle, waterfall, accrual |
| 02c | [Domain Model — Ledger](./02-domain-model/ledger-domain.md) | Double-entry bookkeeping, event sourcing, COA |
| 03 | [System Architecture](./03-system-architecture/high-level-overview.md) | C4 diagrams, service catalog, hexagonal architecture |
| 04 | [Service Interactions](./04-service-interactions/sync-patterns.md) | Sync & async patterns, sagas, idempotency |
| 04b | [Testing Strategy](./04-service-interactions/testing-strategy.md) | Test pyramid, contract tests, chaos engineering |
| 05 | [Database Strategy](./05-database-strategy/data-architecture.md) | Per-service storage, multi-tenancy, partitioning |
| 06 | [Event Architecture](./06-event-architecture/kafka-topology.md) | Kafka topology, event taxonomy, EOS, CDC |
| 07 | [API Strategy](./07-api-strategy/external-api-design.md) | REST/gRPC design standards, versioning, webhooks |
| 08 | [Security Architecture](./08-security-architecture/security-design.md) | Auth, encryption, threat model, secrets |
| 09 | [Compliance Architecture](./09-compliance-architecture/regulatory-compliance.md) | RBI, DPDPA, KYC/AML, consent management |
| 10 | [Deployment Architecture](./10-deployment-architecture/infrastructure.md) | AWS, EKS, multi-region, DR |
| 10b | [CI/CD & GitOps](./10-deployment-architecture/cicd-gitops.md) | Pipelines, ArgoCD, canary deployments |
| 11 | [Observability](./11-observability/observability-strategy.md) | Metrics, tracing, logging, SLOs, alerting |
| 12 | [ADRs](./12-adrs/adr-template.md) | Architecture Decision Records (7 ADRs) |
| 13 | [Risks & Trade-offs](./13-risks-assumptions/risks-and-tradeoffs.md) | RAID log, assumptions, capacity planning |

---

## Architecture Principles

1. **Domain-Driven Design** — Business domains drive service boundaries
2. **Event-Driven Architecture** — Asynchronous, loosely coupled communication
3. **Clean/Hexagonal Architecture** — Ports & adapters for testability
4. **SOLID Principles** — Maintainable, extensible code
5. **Twelve-Factor App** — Cloud-native application design
6. **API-First Design** — Contracts before implementation
7. **Security by Design** — Zero-trust, defense in depth
8. **Compliance by Design** — Regulatory requirements are first-class citizens
9. **Observability by Design** — Every service is observable from day one
10. **Financial Correctness** — BigDecimal only, deterministic, auditable

---

## Phase Roadmap

```
Phase 1: Foundation & Architecture    ✅ COMPLETE
Phase 2: Platform Foundation (Infra, CI/CD, Shared Libraries)  ← NEXT
Phase 3: Business Capabilities (Services Implementation)
Phase 4: Production Readiness (Performance, Security Hardening, DR)
```

---

## Key Architectural Decisions Summary

| # | Decision | Rationale | ADR |
|---|----------|-----------|-----|
| 1 | Java 21 with Virtual Threads | High concurrency without reactive complexity | [ADR-001](./12-adrs/adr-001-java21-virtual-threads.md) |
| 2 | Event Sourcing for Ledger only | Audit trail + immutability for financial records | [ADR-002](./12-adrs/adr-002-event-sourcing-ledger.md) |
| 3 | Hybrid Multi-Tenancy (Pool + Silo) | Pool for small, Silo for enterprise tenants | [ADR-003](./12-adrs/adr-003-multi-tenancy-strategy.md) |
| 4 | Kafka (self-managed via Strimzi) | Full control, exactly-once, event replay | [ADR-004](./12-adrs/adr-004-kafka-event-backbone.md) |
| 5 | Protobuf for events + gRPC | 10x smaller than JSON; schema evolution | [ADR-005](./12-adrs/adr-005-protobuf-serialization.md) |
| 6 | Flink for complex stream processing | Exactly-once state, event-time, 5M loan accrual | [ADR-006](./12-adrs/adr-006-flink-stream-processing.md) |
| 7 | Saga Orchestration + Choreography | Right pattern for right complexity level | [ADR-007](./12-adrs/adr-007-saga-orchestration.md) |

---

## Conventions

- **Monetary values**: Always `BigDecimal` with explicit scale and `RoundingMode.HALF_EVEN`
- **Timestamps**: UTC `Instant` stored as `TIMESTAMP WITH TIME ZONE`
- **IDs**: UUIDv7 (time-ordered) for primary keys
- **Events**: Protobuf serialization with Schema Registry
- **APIs**: REST (external), gRPC (internal service-to-service)
- **Naming**: `kebab-case` for APIs, `snake_case` for DB, `PascalCase` for Protobuf
