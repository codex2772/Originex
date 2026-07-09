# Risks, Assumptions & Trade-offs

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Risk Register (RAID Log)

### 1.1 Technical Risks

| ID | Risk | Likelihood | Impact | Severity | Mitigation | Owner |
|----|------|-----------|--------|----------|-----------|-------|
| R-001 | Kafka operational complexity leads to outages | Medium | Critical | High | Strimzi operator; dedicated SRE; runbooks; chaos testing | Platform Team |
| R-002 | Event sourcing complexity in Ledger causes bugs | Medium | Critical | High | Extensive testing; shadow mode; reconciliation jobs; limited to Ledger only | Finance Team |
| R-003 | Multi-tenancy data leak (RLS bypass) | Low | Critical | High | Defense in depth (RLS + app-level + integration tests); security audit; pen testing | Security Team |
| R-004 | Flink state management failure (checkpoint corruption) | Low | High | Medium | Incremental checkpoints; RocksDB tuning; savepoint before deploy; monitoring | Data Team |
| R-005 | Virtual thread pinning causes throughput degradation | Low | Medium | Low | Pinning detection in CI (JFR); replace synchronized with ReentrantLock | All Teams |
| R-006 | Schema evolution breaks consumer compatibility | Medium | High | High | Schema Registry BACKWARD mode; CI compatibility check; contract tests | All Teams |
| R-007 | Cross-region replication lag causes stale reads after failover | Medium | Medium | Medium | Synchronous replication for Ledger; idempotent consumers handle duplicates | Platform Team |
| R-008 | Partner API instability (bureau, payment rails) | High | Medium | Medium | Circuit breakers; bulkheads; fallback strategies; multiple provider support | Integration Team |
| R-009 | Cost overrun due to infrastructure sprawl | Medium | Medium | Medium | FinOps practices; resource quotas; auto-scaling right-sizing; monthly review | Platform Team |
| R-010 | Key person dependency (Kafka/Flink expertise) | Medium | High | High | Cross-training; documentation; operational runbooks; SRE hiring | Engineering Manager |
| R-011 | Database connection exhaustion under load | Medium | High | High | Connection pooling (HikariCP); PgBouncer; monitoring; auto-scaling | Platform Team |
| R-012 | Outbox table growth causing PostgreSQL bloat | Medium | Medium | Medium | Daily partition pruning; monitor table size; autovacuum tuning | Platform Team |
| R-013 | DLQ accumulation without resolution | Medium | Medium | Medium | DLQ monitoring dashboard; automated retry policies; alerting; weekly review | All Teams |
| R-014 | Inadequate performance under peak load (EOD burst) | Medium | High | High | Load testing; capacity planning; auto-scaling; Flink parallelism tuning | Performance Team |
| R-015 | Compliance gap discovered during audit | Low | Critical | Medium | Compliance-as-code; automated checks; quarterly self-audit; external audit | Compliance Team |

### 1.2 Organizational Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|----|------|-----------|--------|-----------|
| R-016 | Team scaling challenges (hiring DDD/Kafka/Flink expertise) | High | High | Training programs; pair programming; gradual complexity introduction |
| R-017 | Scope creep in Phase 3 (feature requests outpace architecture) | High | Medium | Strict ADR process; architecture fitness functions; tech debt sprints |
| R-018 | Vendor lock-in to AWS | Low | Medium | Abstract infrastructure via Terraform; Kubernetes portability; no proprietary services in core logic |
| R-019 | Regulatory changes requiring architecture modification | Medium | High | Modular compliance layer; configurable rules; BRE for policy changes |

---

## 2. Assumptions

### 2.1 Scale Assumptions

| Assumption | Basis | Impact if Wrong | Validation Plan |
|-----------|-------|-----------------|-----------------|
| 20M customers within 3 years | Market analysis; sales pipeline | Under-provisioned storage/compute | Quarterly capacity review |
| 500M ledger txns/day at steady state | 5M loans × 100 txns/loan/day (accrual, fees, etc.) | Kafka cluster sizing wrong | Load test with projected volume |
| Peak-to-average ratio of 8:1 (EOD burst) | Industry benchmarks | Auto-scaling insufficient | Stress test with 10x burst |
| 80/20 tenant distribution (20% tenants = 80% volume) | B2B platform norms | Noisy-neighbor more severe | Monitor per-tenant metrics |
| Average message size: 2 KB (Protobuf) | Measured from proto definitions | Network/storage estimates off | Validate with real proto messages |
| Database growth: 5 TB/year per major service | Transaction volume × avg row size | Storage costs higher | Monitor and project quarterly |

### 2.2 Technical Assumptions

| Assumption | Basis | Impact if Wrong |
|-----------|-------|-----------------|
| Java 21 virtual threads are production-stable | GA since Sept 2023; 3 years of adoption | Fallback to platform threads + Reactor |
| PostgreSQL 16 handles 500M writes/day per instance (with partitioning) | Benchmarks; partitioning + connection pooling | Evaluate CockroachDB or YugabyteDB |
| Strimzi Kafka operator is mature for production financial workloads | Extensive community adoption; CNCF project | Evaluate Confluent Platform or AWS MSK |
| Istio service mesh overhead is < 5ms p99 per hop | Community benchmarks | Evaluate sidecar-less (ambient mesh) or direct mTLS |
| Flink can process 5M stateful keys with RocksDB within EOD window (30 min) | Benchmarked with synthetic data | Increase parallelism; optimize state access |
| AWS ap-south-2 (Hyderabad) has sufficient capacity for DR | AWS region availability | Use ap-south-1 multi-AZ only |

### 2.3 Business Assumptions

| Assumption | Basis | Impact if Wrong |
|-----------|-------|-----------------|
| Initial launch with 5-10 lending partners (tenants) | Sales pipeline | Slower scale; adjust infrastructure down |
| Primary loan products: Personal, Business, Education, LAP | Market analysis | Additional product complexity |
| INR only at launch; multi-currency in Phase 4 | India-first strategy | Ledger and payment redesign needed |
| RBI regulations remain stable (no major overhaul) | Historical pattern | Compliance module redesign |
| All partners have technical integration capability (APIs) | B2B target market | Need to build partner portal/UI |

---

## 3. Architectural Trade-offs

### 3.1 Consistency vs. Availability

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CAP TRADE-OFF DECISIONS                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  STRONG CONSISTENCY (CP):                                                    │
│  • Ledger service: Balance MUST be accurate (financial correctness)          │
│  • Payment service: No duplicate disbursements (exactly-once)                │
│  • Loan state: Status transitions must be atomic                             │
│                                                                              │
│  EVENTUAL CONSISTENCY (AP):                                                  │
│  • Reporting/Analytics: Acceptable 5-30s lag from source                     │
│  • Notification delivery: At-least-once, slight delay acceptable             │
│  • Search indexes: Seconds of lag from primary                               │
│  • Cross-region reads: May see slightly stale data                           │
│                                                                              │
│  RATIONALE:                                                                  │
│  Financial operations demand CP. Supporting operations can tolerate AP.       │
│  This allows the platform to remain available for non-critical paths          │
│  even during partial failures.                                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Performance vs. Correctness

| Domain | Priority | Trade-off |
|--------|----------|-----------|
| Ledger | Correctness > Performance | Accept higher latency for double-entry validation |
| BRE | Performance > Complexity | In-memory rule evaluation; accept cache staleness (5 min TTL) |
| LOS | Balance | Async processing for bureau calls; sync for eligibility check |
| Reporting | Performance > Freshness | Pre-computed aggregations; accept data lag |
| Notifications | Throughput > Ordering | Parallel delivery; accept slight out-of-order |

### 3.3 Simplicity vs. Resilience

| Decision | Complexity Added | Resilience Gained |
|----------|-----------------|-------------------|
| Outbox Pattern | Outbox table + CDC connector per service | Zero event loss; no dual-write problem |
| Inbox Pattern | Inbox table + dedup check per consumer | Exactly-once processing semantics |
| Circuit Breakers | Config + fallback logic per integration | Cascade failure prevention |
| Saga Pattern | State machine + compensation logic | Cross-service consistency without 2PC |
| Event Sourcing (Ledger) | Event store + projections + snapshots | Complete audit trail + temporal queries |
| Multi-region | 2x infrastructure + replication | 99.99% availability; DR capability |

**Verdict:** The complexity is justified because this is a financial platform where data loss, inconsistency, or extended downtime has direct monetary and regulatory consequences.

### 3.4 Cost vs. Isolation

| Approach | Monthly Cost (est.) | Isolation Level | Decision |
|----------|--------------------|-----------------|---------| 
| Silo (all tenants) | $150K+ | Perfect | Too expensive for small tenants |
| Pool (all tenants) | $25K | Logical (RLS) | Insufficient for enterprise contracts |
| Hybrid (our choice) | $35-50K | Right-sized per tenant | Best balance |

### 3.5 Build vs. Buy

| Capability | Decision | Rationale |
|-----------|----------|-----------|
| Identity/Auth | Buy (Keycloak) | Commodity; security-critical; proven solution |
| Business Rules Engine | Build | Core differentiator; must integrate deeply with domain |
| Ledger | Build | Core domain; no off-the-shelf supports our event-sourced model |
| Payment Gateway | Integrate (not build) | Payment rail connectivity is commodity |
| Notification Service | Build (with SaaS providers) | Orchestration is custom; delivery via vendors (Twilio, etc.) |
| Monitoring | Buy/OSS (Prometheus/Grafana) | Standard; no differentiation in observability tooling |
| CI/CD | Buy (GitHub Actions + ArgoCD) | Standard; no need to build custom |
| Search | Buy (OpenSearch) | Standard search/analytics infrastructure |

---

## 4. Known Technical Debt (Accepted for Phase 1)

| Item | Reason for Acceptance | Resolution Plan |
|------|----------------------|-----------------|
| Single-region deployment initially | DR region adds 40% cost; validate architecture first | Phase 4: Multi-region rollout |
| Manual DLQ review process | Low initial volume; automation premature | Phase 3: Automated DLQ replay tooling |
| Basic rate limiting (per-tenant, not per-endpoint) | Sufficient for launch; refine with real usage data | Phase 4: Endpoint-level + adaptive rate limiting |
| No ML-based fraud detection | Requires training data (need production traffic) | Phase 4: Introduce ML pipeline |
| Limited chaos engineering | Focus on correctness first | Phase 4: Full Litmus chaos suite |
| No multi-currency support | INR-only launch; architecture supports it | Phase 4: Multi-currency ledger |

---

## 5. Capacity Planning Summary

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CAPACITY PLANNING (Year 1 → Year 3)                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  COMPUTE:                                                                    │
│  Year 1: 12-18 EKS nodes (core) → Year 3: 40-60 nodes                      │
│  Scaling: HPA + KEDA; burst via Fargate                                      │
│                                                                              │
│  STORAGE:                                                                    │
│  PostgreSQL: Year 1: 5 TB → Year 3: 50 TB (with archival)                   │
│  Kafka: Year 1: 3 TB (hot) → Year 3: 30 TB (hot) + S3 tiered               │
│  OpenSearch: Year 1: 2 TB → Year 3: 20 TB (with ILM)                        │
│  S3: Year 1: 10 TB → Year 3: 200 TB (documents + archives)                  │
│                                                                              │
│  NETWORK:                                                                    │
│  Year 1: 1 Gbps sustained → Year 3: 10 Gbps sustained                       │
│  Cross-AZ: Optimize with topology-aware routing                              │
│                                                                              │
│  DATABASE CONNECTIONS:                                                        │
│  Year 1: ~200 concurrent (20 per service × 10 services)                      │
│  Year 3: ~600 concurrent (with PgBouncer pooling)                            │
│  RDS max: db.r6g.2xlarge supports ~2000 connections                          │
│                                                                              │
│  KAFKA:                                                                      │
│  Year 1: 6 brokers, 500 partitions, 50M msgs/day                            │
│  Year 3: 12 brokers, 2000 partitions, 500M msgs/day                         │
│  Growth: Add brokers + rebalance partitions                                  │
│                                                                              │
│  COST PROJECTION:                                                            │
│  Year 1: $25-35K/month (single region)                                       │
│  Year 2: $50-70K/month (growing traffic + DR region)                         │
│  Year 3: $80-120K/month (full scale + multi-region active)                   │
│  With RI/Savings Plans: 30-40% discount applicable                           │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Open Questions (To Be Resolved)

| # | Question | Impact | Decision Needed By | Options |
|---|----------|--------|-------------------|---------|
| 1 | Which Kafka managed option if Strimzi proves too complex? | Operational cost | End of Phase 2 | AWS MSK vs Confluent Cloud |
| 2 | CockroachDB/YugabyteDB for distributed SQL in multi-region active-active? | Data architecture | Phase 4 planning | PostgreSQL with app-level routing vs distributed SQL |
| 3 | Service mesh: Istio sidecar vs Ambient mesh (sidecar-less)? | Performance + ops | End of Phase 2 | Ambient mesh (newer) vs sidecar (proven) |
| 4 | BRE: Custom-built vs Drools vs commercial engine? | Development time | Phase 3 BRE implementation | Build (flexibility) vs Drools (faster) |
| 5 | Event store: PostgreSQL vs dedicated EventStoreDB? | Ledger performance | Phase 3 Ledger implementation | PostgreSQL (unified ops) vs EventStoreDB (purpose-built) |
| 6 | Tenant billing: Build vs integrate Stripe/Chargebee? | Time to market | Phase 3 Tenant service | Build (control) vs buy (speed) |
| 7 | ML platform for fraud/risk: SageMaker vs Kubeflow vs custom? | Phase 4 capability | Phase 4 planning | Evaluate based on data volume and model complexity |

---

## 7. Phase 1 Exit Criteria

Before proceeding to Phase 2 (Platform Foundation), the following must be validated:

| Criteria | Validation Method | Status |
|----------|------------------|--------|
| All ADRs reviewed and accepted by Architecture Board | Meeting minutes | ☐ Pending |
| Domain model validated with business stakeholders | Workshop sign-off | ☐ Pending |
| Technology stack PoC completed (Kafka + Flink + PostgreSQL) | Working prototype | ☐ Pending |
| Security architecture reviewed by Security Team | Security review document | ☐ Pending |
| Compliance mapping validated by Legal/Compliance | Compliance checklist | ☐ Pending |
| Cost estimates approved by Finance | Budget approval | ☐ Pending |
| Team structure and hiring plan aligned | HR/Eng Manager alignment | ☐ Pending |
| Infrastructure budget approved | FinOps review | ☐ Pending |
| No blocking open questions remaining | This document updated | ☐ Pending |
| External architecture review (optional) | Third-party consultant | ☐ Pending |
