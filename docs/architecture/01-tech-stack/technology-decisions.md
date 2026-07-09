# Technology Stack & Rationale

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Technology Selection Matrix

### 1.1 Backend Runtime & Framework

| Technology | Version | Purpose | Rationale |
|-----------|---------|---------|-----------|
| Java 21 (LTS) | 21.x | Primary language | Virtual threads (Project Loom) for high-concurrency I/O; mature ecosystem for financial systems; strong type safety; GraalVM-ready; proven at scale in banking |
| Spring Boot | 3.4.x | Application framework | Native AOT compilation; virtual thread support; comprehensive ecosystem; observability integration; battle-tested in enterprise |
| Spring Security | 6.x | Security framework | OAuth2/OIDC native support; method-level security; comprehensive filter chain |
| Spring Data | 3.x | Data access | Repository abstraction; audit support; pagination; query derivation |
| Spring Cloud | 2024.x | Distributed systems | Circuit breakers, config management, service discovery |
| Spring Modulith | 1.x | Modular monolith (where appropriate) | Logical module boundaries within services; event publication; architectural tests |

**Why Java 21 over alternatives:**

| Alternative | Consideration | Decision |
|------------|---------------|----------|
| Kotlin | Excellent language but smaller hiring pool in India fintech; coroutines compete with virtual threads | Rejected — hiring risk |
| Go | Great for infra tooling, but lacks mature ORM/DI frameworks for complex domain logic | Rejected — domain complexity |
| Node.js | Single-threaded; weak type safety at scale; BigDecimal handling poor | Rejected — financial correctness |
| Rust | Excellent performance but steep learning curve; slower development velocity | Rejected — velocity |

**Virtual Threads justification:** At 100K+ applications/day and tens of thousands of repayments/minute, traditional thread-per-request models hit limits at ~10K threads. Virtual threads allow millions of concurrent operations without reactive complexity, maintaining readable imperative code while achieving reactive throughput.

---

### 1.2 Messaging & Event Streaming

| Technology | Version | Purpose | Rationale |
|-----------|---------|---------|-----------|
| Apache Kafka | 3.7.x | Event backbone | Proven at 500M+ msgs/day; exactly-once semantics; log compaction; multi-DC replication |
| Confluent Schema Registry | 7.x | Schema governance | Protobuf schema evolution; backward/forward compatibility; centralized contract |
| Kafka Connect | 3.7.x | Data integration | CDC from PostgreSQL; sink to OpenSearch; minimal custom code |
| Kafka Streams | 3.7.x | Lightweight stream processing | Stateful processing within services; no separate cluster needed |

**Why Kafka over alternatives:**

| Alternative | Consideration | Decision |
|------------|---------------|----------|
| RabbitMQ | Excellent for task queues but lacks persistent log, replay, exactly-once at scale | Rejected — no replay |
| Apache Pulsar | Multi-tenancy built-in but smaller ecosystem, fewer operational experts | Rejected — operational maturity |
| AWS Kinesis | Vendor lock-in; shard limits; no log compaction; weaker exactly-once | Rejected — flexibility |
| NATS | Lightweight but lacks enterprise features (schema registry, exactly-once) | Rejected — enterprise gaps |

**Kafka deployment model:** Self-managed on EKS using Strimzi Operator for full control over configuration, multi-AZ rack awareness, and custom retention policies per topic.

---

### 1.3 Stream & Batch Processing

| Technology | Version | Purpose | Rationale |
|-----------|---------|---------|-----------|
| Apache Flink | 1.19.x | Complex event processing | Exactly-once state; event-time processing; savepoints; low latency |
| Flink SQL | 1.19.x | Declarative stream queries | Business analyst-friendly; rapid iteration |
| Flink CDC | 3.x | Change Data Capture | PostgreSQL WAL streaming to Flink |

**Use cases for Flink:**
- Real-time interest accrual across 5M+ loans
- Delinquency detection and aging (DPD calculation)
- Payment matching and reconciliation
- Fraud pattern detection
- Real-time portfolio risk aggregation
- EOD processing (event-driven, not batch)

**Why Flink over alternatives:**

| Alternative | Consideration | Decision |
|------------|---------------|----------|
| Kafka Streams | Good for simple enrichment but lacks advanced windowing, exactly-once state at scale | Used for simple cases; Flink for complex |
| Apache Spark Structured Streaming | Micro-batch latency (seconds); JVM memory pressure at scale | Rejected — latency |
| AWS Kinesis Analytics | Vendor lock-in; limited state management | Rejected — vendor lock |
| Spring Batch | No streaming; only batch; high latency for EOD | Rejected — batch only |

---

### 1.4 Serialization

| Technology | Purpose | Rationale |
|-----------|---------|-----------|
| Protocol Buffers (Protobuf) v3 | Event serialization, gRPC contracts | 10x smaller than JSON; schema evolution; code generation; backward compatible |
| JSON (Jackson) | REST API responses | Human-readable for external APIs; industry standard |

**Protobuf Schema Evolution Strategy:**
- All fields are optional (proto3 default)
- Fields are never removed, only deprecated
- Field numbers are never reused
- Backward compatibility enforced by Schema Registry
- Consumer reads unknown fields gracefully

**Why Protobuf over alternatives:**

| Alternative | Consideration | Decision |
|------------|---------------|----------|
| Avro | Schema evolution good but requires schema at read-time; container format overhead | Rejected — complexity |
| JSON | 10x larger wire size; no schema enforcement; parsing overhead at 500M msgs/day | Rejected for events — performance |
| FlatBuffers | Zero-copy but complex schema evolution; limited tooling | Rejected — tooling |
| MessagePack | No schema; binary JSON variant; no code generation | Rejected — no schema |

---

### 1.5 Databases

| Technology | Services | Justification |
|-----------|----------|---------------|
| PostgreSQL 16 | LOS, LMS, Ledger, Payments, Collections, Customer, IAM | ACID compliance; JSONB for flexible attributes; partitioning; row-level security; proven in financial systems |
| Redis 7 (Cluster) | All services | Sub-millisecond caching; rate limiting; distributed locks; session store; sorted sets for leaderboards |
| OpenSearch 2.x | Reporting, Search, Audit | Full-text search; aggregations; time-series; log analytics; dashboard |
| AWS S3 | Document Management | Object storage; lifecycle policies; versioning; encryption; cross-region replication |
| TimescaleDB | Metrics, Time-series analytics | Hypertable partitioning; continuous aggregations; retention policies |

**Database-per-service principle:** Each bounded context owns its data store exclusively. No shared databases. Cross-service queries use CQRS read models populated via events.

**Why PostgreSQL over alternatives:**

| Alternative | Consideration | Decision |
|------------|---------------|----------|
| MySQL | Weaker JSONB; no native partitioning until 8.x; weaker CTE support | Rejected — feature gaps |
| Oracle | License cost prohibitive for cloud-native; vendor lock-in | Rejected — cost |
| CockroachDB | Distributed SQL but higher latency; newer; fewer DBA experts | Evaluated for future — maturity |
| MongoDB | No ACID across documents; eventual consistency risky for financial data | Rejected — financial correctness |
| YugabyteDB | PostgreSQL compatible, distributed; considered for multi-region | Evaluated for Phase 4 |

---

### 1.6 Infrastructure & Deployment

| Technology | Purpose | Rationale |
|-----------|---------|-----------|
| AWS | Cloud provider | Widest service catalog; Mumbai + Hyderabad regions; RBI compliance; fintech adoption |
| Amazon EKS | Container orchestration | Managed Kubernetes; auto-scaling; IRSA for IAM; Fargate for burst |
| Terraform | Infrastructure as Code | Multi-cloud capable; state management; module ecosystem; drift detection |
| Docker | Containerization | Industry standard; multi-stage builds; distroless base images |
| Helm | Kubernetes packaging | Templated manifests; release management; rollback capability |
| ArgoCD | GitOps deployment | Declarative; audit trail; automated sync; multi-cluster |
| Istio | Service mesh | mTLS; traffic management; observability; rate limiting; fault injection |
| GitHub Actions | CI pipeline | Native GitHub integration; reusable workflows; matrix builds |
| Vault (HashiCorp) | Secrets management | Dynamic secrets; auto-rotation; encryption-as-a-service; audit log |
| cert-manager | TLS certificates | Automated certificate lifecycle; Let's Encrypt + private CA |

**Why AWS over alternatives:**

| Alternative | Consideration | Decision |
|------------|---------------|----------|
| Azure | Strong enterprise but weaker India region presence for fintech | Rejected — region |
| GCP | Excellent Kubernetes but fewer India regions; smaller fintech adoption in India | Rejected — ecosystem |
| Multi-cloud | Complexity overhead; lowest-common-denominator constraints | Rejected — complexity |

---

### 1.7 Observability Stack

| Technology | Purpose | Rationale |
|-----------|---------|-----------|
| OpenTelemetry | Instrumentation standard | Vendor-agnostic; auto-instrumentation for Java; unified traces/metrics/logs |
| Prometheus | Metrics collection | Pull-based; PromQL; alerting rules; proven at scale |
| Grafana | Visualization | Unified dashboards; alerting; data source aggregation |
| Jaeger | Distributed tracing | OpenTelemetry native; trace correlation; service dependency map |
| Loki | Log aggregation | Label-based; efficient storage; Grafana-native; LogQL |
| Alertmanager | Alert routing | Deduplication; grouping; silencing; escalation policies |
| PagerDuty | Incident management | On-call scheduling; escalation; runbook automation |

---

### 1.8 Testing

| Technology | Purpose | Rationale |
|-----------|---------|-----------|
| JUnit 5 | Unit testing | Standard; parameterized tests; extensions |
| Testcontainers | Integration testing | Real databases/Kafka in Docker; reproducible; CI-friendly |
| WireMock | HTTP mocking | Contract verification; chaos simulation |
| Pact | Consumer-Driven Contract Testing | Cross-service contract safety; provider verification |
| Gatling | Performance testing | Scala DSL; realistic load patterns; detailed reports |
| OWASP ZAP | Security testing | Automated vulnerability scanning; CI integration |
| Chaos Monkey / Litmus | Chaos engineering | Fault injection; resilience validation |
| ArchUnit | Architecture testing | Enforce hexagonal architecture; dependency rules |

---

## 2. Version Pinning & Upgrade Strategy

```
┌─────────────────────────────────────────────────────────────┐
│ Dependency Lifecycle                                         │
├─────────────────────────────────────────────────────────────┤
│ LTS Only        → Java, PostgreSQL, Redis                   │
│ Latest Stable   → Spring Boot, Kafka, Flink                 │
│ N-1 Policy      → Always support current and previous minor │
│ Security Patch  → Applied within 72 hours (critical)        │
│ Major Upgrade   → Quarterly evaluation, annual execution    │
│ Renovate Bot    → Automated dependency PR creation          │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Build & Dependency Management

| Tool | Purpose |
|------|---------|
| Gradle 8.x | Build system — faster than Maven; incremental builds; build cache |
| Gradle Version Catalog | Centralized dependency versions |
| Gradle Convention Plugins | Shared build logic across services |
| JIB | Container image building — no Docker daemon required; reproducible |
| Spotless | Code formatting enforcement |
| SpotBugs + ErrorProne | Static analysis |
| OWASP Dependency Check | CVE scanning |

---

## 4. Development Environment

| Tool | Purpose |
|------|---------|
| Tilt / Skaffold | Local Kubernetes development |
| Docker Compose | Local dependency stack |
| Telepresence | Remote debugging in cluster |
| IntelliJ IDEA | Primary IDE |
| EditorConfig | Consistent formatting |
| Pre-commit hooks | Lint, format, test before push |

---

## 5. Technology Risk Matrix

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| Kafka operational complexity | Medium | High | Strimzi operator; dedicated SRE team; managed fallback plan |
| Flink state management at scale | Medium | High | Incremental checkpointing; RocksDB backend; savepoint strategy |
| Virtual threads maturity | Low | Medium | Fallback to platform threads; pinning detection |
| Istio complexity overhead | Medium | Medium | Progressive adoption; sidecar-less ambient mesh evaluation |
| PostgreSQL sharding limits | Low | High | Citus extension; YugabyteDB migration path |
| Protobuf schema evolution bugs | Low | Medium | Schema Registry compatibility checks; CI validation |
