# High-Level System Architecture

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. C4 Level 1 — System Context Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                 SYSTEM CONTEXT                                        │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                      │
│  ┌──────────────┐                                          ┌──────────────┐         │
│  │   Lending     │                                          │   Borrower    │         │
│  │   Partner     │     REST API / Webhooks                  │   (End User)  │         │
│  │   (Bank/NBFC) │─────────────────┐                       │               │         │
│  └──────────────┘                  │                       └───────┬───────┘         │
│                                    │                               │                  │
│  ┌──────────────┐                  ▼                               ▼                  │
│  │   Partner     │     ┌─────────────────────────────────────────────┐               │
│  │   Admin       │────▶│                                             │               │
│  │   Portal      │     │         ORIGINEX LaaS PLATFORM              │               │
│  └──────────────┘     │                                             │               │
│                        │   Loan Origination │ Loan Management        │               │
│  ┌──────────────┐     │   Ledger │ Payments │ Collections           │               │
│  │   Ops Team    │────▶│   Risk │ Notifications │ Reporting          │               │
│  │   (Internal)  │     │                                             │               │
│  └──────────────┘     └───────────┬──────────────┬──────────────────┘               │
│                                    │              │                                   │
│                          ┌─────────┘              └──────────┐                       │
│                          ▼                                    ▼                       │
│              ┌──────────────────┐                 ┌──────────────────┐               │
│              │  Credit Bureaus   │                 │  Payment Rails    │               │
│              │  (CIBIL/Experian/ │                 │  (NPCI/Banks/     │               │
│              │   Equifax/CRIF)   │                 │   UPI/NACH/RTGS)  │               │
│              └──────────────────┘                 └──────────────────┘               │
│                                                                                      │
│              ┌──────────────────┐                 ┌──────────────────┐               │
│              │  KYC Providers    │                 │  Communication    │               │
│              │  (DigiLocker/     │                 │  (SMS Gateway/    │               │
│              │   NSDL/UIDAI)     │                 │   Email/WhatsApp) │               │
│              └──────────────────┘                 └──────────────────┘               │
│                                                                                      │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. C4 Level 2 — Container Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                              ORIGINEX PLATFORM CONTAINERS                                      │
├─────────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐        │
│  │                           API GATEWAY LAYER (Kong/Istio)                          │        │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐         │        │
│  │  │Rate Limit│  │   Auth   │  │  Routing │  │  Logging │  │   WAF    │         │        │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘         │        │
│  └──────────────────────────────────────┬──────────────────────────────────────────┘        │
│                                          │                                                   │
│  ┌───────────────────────────────────────┼──────────────────────────────────────────┐       │
│  │                    CORE SERVICES       │    (Kubernetes Namespace: originex-core)  │       │
│  │                                       ▼                                           │       │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │       │
│  │  │  customer-   │  │    iam-      │  │    los-      │  │    bre-      │         │       │
│  │  │   service    │  │   service    │  │   service    │  │   service    │         │       │
│  │  │  (Java 21)   │  │  (Keycloak)  │  │  (Java 21)   │  │  (Java 21)   │         │       │
│  │  │  PostgreSQL  │  │  PostgreSQL  │  │  PostgreSQL  │  │  PostgreSQL  │         │       │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘         │       │
│  │                                                                                   │       │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │       │
│  │  │    lms-      │  │   ledger-    │  │  payment-    │  │ collection-  │         │       │
│  │  │   service    │  │   service    │  │   service    │  │   service    │         │       │
│  │  │  (Java 21)   │  │  (Java 21)   │  │  (Java 21)   │  │  (Java 21)   │         │       │
│  │  │  PostgreSQL  │  │  PostgreSQL  │  │  PostgreSQL  │  │  PostgreSQL  │         │       │
│  │  │             │  │ Event-Sourced │  │             │  │             │         │       │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘         │       │
│  │                                                                                   │       │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │       │
│  │  │notification- │  │  reporting-  │  │   tenant-    │  │   audit-     │         │       │
│  │  │   service    │  │   service    │  │   service    │  │   service    │         │       │
│  │  │  (Java 21)   │  │  (Java 21)   │  │  (Java 21)   │  │  (Java 21)   │         │       │
│  │  │    Redis     │  │  OpenSearch  │  │  PostgreSQL  │  │  OpenSearch  │         │       │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘         │       │
│  │                                                                                   │       │
│  │  ┌──────────────┐  ┌──────────────┐                                              │       │
│  │  │  partner-    │  │   config-    │                                              │       │
│  │  │   service    │  │   service    │                                              │       │
│  │  │  (Java 21)   │  │  (Java 21)   │                                              │       │
│  │  │    Redis     │  │  PostgreSQL  │                                              │       │
│  │  └──────────────┘  └──────────────┘                                              │       │
│  └──────────────────────────────────────────────────────────────────────────────────┘       │
│                                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────────────────┐       │
│  │                     EVENT BACKBONE (Kafka Cluster)                                 │       │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐          │       │
│  │  │ Brokers  │  │  Schema  │  │  Kafka   │  │  Kafka   │  │   DLQ    │          │       │
│  │  │ (6 nodes)│  │ Registry │  │ Connect  │  │ Streams  │  │  Topics  │          │       │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘          │       │
│  └──────────────────────────────────────────────────────────────────────────────────┘       │
│                                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────────────────┐       │
│  │                     STREAM PROCESSING (Apache Flink)                               │       │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │       │
│  │  │  Interest    │  │ Delinquency  │  │  Payment     │  │  Portfolio   │         │       │
│  │  │  Accrual     │  │   Aging      │  │  Matching    │  │  Risk Agg    │         │       │
│  │  │   Job        │  │    Job       │  │    Job       │  │    Job       │         │       │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘         │       │
│  └──────────────────────────────────────────────────────────────────────────────────┘       │
│                                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────────────────┐       │
│  │                     OBSERVABILITY STACK                                            │       │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐          │       │
│  │  │Prometheus│  │ Grafana  │  │  Jaeger  │  │   Loki   │  │  Alert   │          │       │
│  │  │          │  │          │  │          │  │          │  │ Manager  │          │       │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘          │       │
│  └──────────────────────────────────────────────────────────────────────────────────┘       │
│                                                                                              │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Data Flow Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          PRIMARY DATA FLOWS                                       │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  LOAN ORIGINATION FLOW:                                                          │
│  ═══════════════════════                                                         │
│                                                                                  │
│  Partner API ──► API Gateway ──► LOS Service ──► BRE (Eligibility)              │
│       │                              │                   │                       │
│       │                              ▼                   ▼                       │
│       │                    Partner Service ──► Bureau API (Credit Check)          │
│       │                              │                                           │
│       │                              ▼                                           │
│       │                    LOS Service ──► Offer Engine ──► Customer Accept       │
│       │                              │                                           │
│       │                              ▼                                           │
│       │                    ══► Kafka: ApplicationApproved ══►                    │
│       │                              │                                           │
│       ▼                              ▼                                           │
│  LMS Service ◄══ Kafka: DisbursementRequested ══► Payment Service               │
│       │                                                  │                       │
│       │                                                  ▼                       │
│       │                                          Payment Rails (NEFT/RTGS)       │
│       │                                                  │                       │
│       ▼                                                  ▼                       │
│  Ledger Service ◄══ Kafka: DisbursementCompleted                                │
│       │                                                                          │
│       ▼                                                                          │
│  Journal Entry Posted (Double-Entry)                                             │
│                                                                                  │
│                                                                                  │
│  REPAYMENT FLOW:                                                                 │
│  ═══════════════                                                                 │
│                                                                                  │
│  Payment Rails ──► Payment Service ──► Kafka: PaymentReceived                   │
│                         │                      │                                 │
│                         ▼                      ▼                                 │
│                   Reconciliation        LMS Service (Allocation)                 │
│                                               │                                 │
│                                               ▼                                 │
│                                    Kafka: RepaymentAllocated                    │
│                                               │                                 │
│                                    ┌──────────┼──────────┐                      │
│                                    ▼          ▼          ▼                      │
│                              Ledger      Collections  Notification              │
│                              (Posting)   (DPD Update) (Receipt)                 │
│                                                                                  │
│                                                                                  │
│  EOD PROCESSING FLOW (Event-Driven via Flink):                                  │
│  ═══════════════════════════════════════════════                                 │
│                                                                                  │
│  Scheduler ──► Kafka: EODTriggered                                              │
│                      │                                                           │
│          ┌───────────┼───────────┬───────────────┐                              │
│          ▼           ▼           ▼               ▼                              │
│    Interest      DPD Aging    NPA Class.    Report Gen                           │
│    Accrual       (Flink)     (Flink)       (Flink)                              │
│    (Flink)          │           │               │                               │
│       │             ▼           ▼               ▼                               │
│       ▼        Kafka Events  Kafka Events  OpenSearch                           │
│  Kafka: InterestAccrued                                                          │
│       │                                                                          │
│       ▼                                                                          │
│  Ledger (Accrual Posting)                                                        │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Network Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        NETWORK TOPOLOGY                                           │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────────────────────────────────┐                                │
│  │              INTERNET                         │                                │
│  └────────────────────┬────────────────────────┘                                │
│                       │                                                          │
│  ┌────────────────────▼────────────────────────┐                                │
│  │         AWS CloudFront (CDN)                  │                                │
│  │         + AWS WAF + Shield Advanced           │                                │
│  └────────────────────┬────────────────────────┘                                │
│                       │                                                          │
│  ┌────────────────────▼────────────────────────┐                                │
│  │         AWS ALB (Application Load Balancer)   │                                │
│  │         TLS Termination + mTLS (partners)     │                                │
│  └────────────────────┬────────────────────────┘                                │
│                       │                                                          │
│  ═══════════════════════════════════════════════════════ VPC Boundary            │
│                       │                                                          │
│  ┌────────────────────▼────────────────────────┐                                │
│  │         Istio Ingress Gateway                 │  Public Subnet                │
│  └────────────────────┬────────────────────────┘                                │
│                       │                                                          │
│  ─────────────────────┼──────────────────────────── Private Subnet              │
│                       │                                                          │
│  ┌────────────────────▼────────────────────────┐                                │
│  │         EKS Cluster (3 AZ)                    │                                │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐     │                                │
│  │  │  AZ-1a  │  │  AZ-1b  │  │  AZ-1c  │     │                                │
│  │  │ Workers │  │ Workers │  │ Workers │     │                                │
│  │  └────┬────┘  └────┬────┘  └────┬────┘     │                                │
│  │       │             │            │           │                                │
│  │       └─────────────┼────────────┘           │                                │
│  │                     │                        │                                │
│  │  ┌──────────────────▼──────────────────┐    │                                │
│  │  │    Istio Service Mesh (mTLS)         │    │                                │
│  │  │    Zero-Trust Network                │    │                                │
│  │  └─────────────────────────────────────┘    │                                │
│  └─────────────────────────────────────────────┘                                │
│                       │                                                          │
│  ─────────────────────┼──────────────────────────── Data Subnet                 │
│                       │                                                          │
│  ┌────────────────────▼────────────────────────┐                                │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  │                                │
│  │  │  RDS     │  │  Redis   │  │OpenSearch│  │                                │
│  │  │(Multi-AZ)│  │ Cluster  │  │ Cluster  │  │                                │
│  │  └──────────┘  └──────────┘  └──────────┘  │                                │
│  │                                              │                                │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  │                                │
│  │  │  Kafka   │  │  Flink   │  │    S3    │  │                                │
│  │  │ (Strimzi)│  │ Cluster  │  │ Buckets  │  │                                │
│  │  └──────────┘  └──────────┘  └──────────┘  │                                │
│  └─────────────────────────────────────────────┘                                │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Service Catalog

| Service | Owner Team | Tier | Scaling | DB | Cache |
|---------|-----------|------|---------|-----|-------|
| customer-service | Platform | T1 | HPA (CPU/Memory) | PostgreSQL | Redis |
| iam-service | Security | T1 | HPA (RPS) | PostgreSQL | Redis |
| los-service | Origination | T1 | HPA (Queue Depth) | PostgreSQL | Redis |
| bre-service | Risk | T1 | HPA (Latency) | PostgreSQL | Redis |
| lms-service | Servicing | T1 | HPA (Event Lag) | PostgreSQL | Redis |
| ledger-service | Finance | T0 | HPA (TPS) | PostgreSQL (Event Store) | Redis |
| payment-service | Payments | T0 | HPA (Queue Depth) | PostgreSQL | Redis |
| collection-service | Recovery | T2 | HPA (CPU) | PostgreSQL | Redis |
| notification-service | Platform | T2 | HPA (Queue Depth) | Redis | Redis |
| reporting-service | Analytics | T2 | HPA (CPU) | OpenSearch | Redis |
| tenant-service | Platform | T1 | Fixed (3 replicas) | PostgreSQL | Redis |
| audit-service | Compliance | T1 | HPA (Ingestion Rate) | OpenSearch + S3 | — |
| partner-service | Integration | T1 | HPA (RPS) | Redis | Redis |
| config-service | Platform | T1 | Fixed (3 replicas) | PostgreSQL | Redis |

**Tier Definitions:**
- **T0:** Zero-tolerance for downtime; financial impact per second; 99.999% target
- **T1:** Critical path; 99.99% target; degradation acceptable for < 1 min
- **T2:** Important but not in real-time critical path; 99.9% target

---

## 6. Cross-Cutting Concerns

```
┌─────────────────────────────────────────────────────────────────┐
│                    CROSS-CUTTING ARCHITECTURE                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Every service includes:                                         │
│                                                                  │
│  ┌─────────────────────────────────────────────────────┐        │
│  │  originex-common-lib                                 │        │
│  │  ├── Money (BigDecimal + Currency + RoundingMode)    │        │
│  │  ├── TenantContext (ThreadLocal / Virtual Thread)    │        │
│  │  ├── CorrelationId (MDC propagation)                 │        │
│  │  ├── AuditContext (actor, action, timestamp)         │        │
│  │  ├── ErrorResponse (RFC 7807 Problem Details)        │        │
│  │  ├── Pagination (cursor-based)                       │        │
│  │  ├── IdempotencyKey (request deduplication)          │        │
│  │  ├── DomainEvent (base event with metadata)          │        │
│  │  └── SecurityContext (JWT claims extraction)         │        │
│  └─────────────────────────────────────────────────────┘        │
│                                                                  │
│  ┌─────────────────────────────────────────────────────┐        │
│  │  originex-spring-boot-starter                        │        │
│  │  ├── Auto-configured OpenTelemetry                   │        │
│  │  ├── Auto-configured Kafka Producer/Consumer         │        │
│  │  ├── Auto-configured Health Checks                   │        │
│  │  ├── Auto-configured Tenant Resolution               │        │
│  │  ├── Auto-configured Audit Interceptor               │        │
│  │  ├── Auto-configured Exception Handling              │        │
│  │  ├── Auto-configured API Versioning                  │        │
│  │  └── Auto-configured Security Filters                │        │
│  └─────────────────────────────────────────────────────┘        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 7. Multi-Region Active-Active Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                       MULTI-REGION ACTIVE-ACTIVE                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│            ┌────────────────────┐         ┌────────────────────┐                │
│            │  AWS Mumbai (Primary)│         │ AWS Hyderabad (DR) │                │
│            │   ap-south-1        │         │   ap-south-2       │                │
│            ├────────────────────┤         ├────────────────────┤                │
│            │                    │         │                    │                │
│            │  EKS Cluster       │◄═══════►│  EKS Cluster       │                │
│            │  (Full Service Set)│  Kafka  │  (Full Service Set)│                │
│            │                    │  Mirror │                    │                │
│            │  PostgreSQL (RDS)  │  Maker  │  PostgreSQL (RDS)  │                │
│            │  Primary Writer    │◄═══════►│  Read Replica /    │                │
│            │                    │  Async  │  Standby Promotion │                │
│            │  Redis Cluster     │  Repl   │  Redis Cluster     │                │
│            │                    │         │                    │                │
│            │  Kafka Cluster     │◄═══════►│  Kafka Cluster     │                │
│            │  (Strimzi)         │  Mirror │  (Strimzi)         │                │
│            │                    │  Maker  │                    │                │
│            └────────┬───────────┘         └────────┬───────────┘                │
│                     │                              │                            │
│                     └──────────────┬───────────────┘                            │
│                                    │                                            │
│                     ┌──────────────▼───────────────┐                            │
│                     │      Route 53 (DNS)           │                            │
│                     │   Latency-Based Routing       │                            │
│                     │   Health Check Failover       │                            │
│                     └──────────────────────────────┘                            │
│                                                                                  │
│  CONSISTENCY MODEL:                                                              │
│  ─────────────────                                                               │
│  • Ledger: Strong consistency (single-region writer, async replication)           │
│  • LOS/LMS: Eventually consistent (event-driven replication)                     │
│  • Customer: Read-after-write consistency (same region)                           │
│  • Reporting: Eventually consistent (acceptable 5-30s lag)                       │
│                                                                                  │
│  FAILOVER STRATEGY:                                                              │
│  ─────────────────                                                               │
│  • RTO: < 5 minutes (automated failover)                                         │
│  • RPO: 0 for financial data (synchronous WAL shipping for Ledger)              │
│  • RPO: < 1 second for non-financial data (async replication)                    │
│  • Automated health checks every 10 seconds                                     │
│  • Circuit breaker opens after 3 consecutive failures                            │
│  • DNS TTL: 30 seconds for fast failover propagation                            │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Hexagonal Architecture (Per Service)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   HEXAGONAL ARCHITECTURE (EACH SERVICE)                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                    ┌─── Driving Adapters ───┐                                │
│                    │                        │                                │
│         ┌──────────────┐        ┌──────────────┐                            │
│         │  REST API     │        │ Kafka Consumer│                            │
│         │  Controller   │        │  (Events In)  │                            │
│         └──────┬───────┘        └──────┬───────┘                            │
│                │                        │                                    │
│         ┌──────▼───────┐        ┌──────▼───────┐                            │
│         │  REST Port    │        │  Event Port   │                            │
│         │  (Interface)  │        │  (Interface)  │                            │
│         └──────┬───────┘        └──────┬───────┘                            │
│                │                        │                                    │
│  ══════════════╪════════════════════════╪══════════════════════              │
│                │     APPLICATION LAYER  │                                    │
│                ▼                        ▼                                    │
│         ┌────────────────────────────────────┐                              │
│         │       Application Services          │                              │
│         │  (Use Cases / Command Handlers)     │                              │
│         └─────────────────┬──────────────────┘                              │
│                           │                                                  │
│  ═════════════════════════╪══════════════════════════════════                │
│                           │     DOMAIN LAYER                                 │
│                           ▼                                                  │
│         ┌────────────────────────────────────┐                              │
│         │         Domain Model                │                              │
│         │  ┌──────────┐  ┌──────────┐        │                              │
│         │  │Aggregates│  │ Domain   │        │                              │
│         │  │& Entities│  │ Services │        │                              │
│         │  └──────────┘  └──────────┘        │                              │
│         │  ┌──────────┐  ┌──────────┐        │                              │
│         │  │  Value   │  │ Domain   │        │                              │
│         │  │ Objects  │  │  Events  │        │                              │
│         │  └──────────┘  └──────────┘        │                              │
│         └─────────────────┬──────────────────┘                              │
│                           │                                                  │
│  ═════════════════════════╪══════════════════════════════════                │
│                           │     INFRASTRUCTURE LAYER                          │
│                           ▼                                                  │
│         ┌──────────────┐        ┌──────────────┐                            │
│         │  Repository   │        │  Event Pub    │                            │
│         │  Port (Intf)  │        │  Port (Intf)  │                            │
│         └──────┬───────┘        └──────┬───────┘                            │
│                │                        │                                    │
│         ┌──────▼───────┐        ┌──────▼───────┐                            │
│         │  PostgreSQL   │        │ Kafka Producer│                            │
│         │  Adapter      │        │   Adapter     │                            │
│         └──────────────┘        └──────────────┘                            │
│                                                                              │
│                    └─── Driven Adapters ────┘                                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

Package Structure (per service):
  com.originex.{service}
  ├── adapter
  │   ├── in
  │   │   ├── rest         (REST Controllers)
  │   │   ├── grpc         (gRPC Services)
  │   │   └── kafka        (Event Consumers)
  │   └── out
  │       ├── persistence  (JPA Repositories)
  │       ├── kafka        (Event Publishers)
  │       ├── redis        (Cache Adapters)
  │       └── external     (External API Clients)
  ├── application
  │   ├── port
  │   │   ├── in           (Use Case Interfaces)
  │   │   └── out          (Repository Interfaces)
  │   ├── service          (Application Services)
  │   └── dto              (Application DTOs)
  ├── domain
  │   ├── model            (Aggregates, Entities, VOs)
  │   ├── event            (Domain Events)
  │   ├── service          (Domain Services)
  │   ├── policy           (Business Policies)
  │   └── exception        (Domain Exceptions)
  └── config               (Spring Configuration)
```
