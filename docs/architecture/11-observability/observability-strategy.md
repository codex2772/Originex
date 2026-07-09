# Observability Strategy

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Observability Pillars

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    THREE PILLARS + PROFILING                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐  ┌─────────────┐ │
│  │    METRICS     │  │    TRACES     │  │     LOGS      │  │  PROFILES   │ │
│  │  (Prometheus)  │  │   (Jaeger)    │  │    (Loki)     │  │ (Pyroscope) │ │
│  │               │  │               │  │               │  │             │ │
│  │ • Counters    │  │ • Distributed │  │ • Structured  │  │ • CPU       │ │
│  │ • Gauges      │  │ • Span-based  │  │ • JSON format │  │ • Memory    │ │
│  │ • Histograms  │  │ • Causal      │  │ • Correlated  │  │ • Alloc     │ │
│  │ • SLO-based   │  │ • Sampled     │  │ • Indexed     │  │ • Continuous│ │
│  └───────┬───────┘  └───────┬───────┘  └───────┬───────┘  └──────┬──────┘ │
│          │                   │                   │                  │        │
│          └───────────────────┼───────────────────┘                  │        │
│                              │                                      │        │
│                    ┌─────────▼─────────┐                           │        │
│                    │  OpenTelemetry     │◄──────────────────────────┘        │
│                    │  (Unified Agent)   │                                    │
│                    └─────────┬─────────┘                                    │
│                              │                                               │
│                    ┌─────────▼─────────┐                                    │
│                    │     Grafana        │                                    │
│                    │  (Unified View)    │                                    │
│                    └───────────────────┘                                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. OpenTelemetry Instrumentation

### 2.1 Auto-Instrumentation Strategy

```yaml
# OTel Collector configuration (DaemonSet on each node)
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318
  prometheus:
    config:
      scrape_configs:
        - job_name: 'kubernetes-pods'
          kubernetes_sd_configs:
            - role: pod

processors:
  batch:
    timeout: 5s
    send_batch_size: 1024
  memory_limiter:
    limit_mib: 512
    spike_limit_mib: 128
  attributes:
    actions:
      - key: tenant_id
        from_context: tenant_id
        action: upsert
      - key: service.environment
        value: production
        action: upsert
  tail_sampling:
    policies:
      - name: error-policy
        type: status_code
        status_code: { status_codes: [ERROR] }
      - name: latency-policy
        type: latency
        latency: { threshold_ms: 1000 }
      - name: probabilistic-policy
        type: probabilistic
        probabilistic: { sampling_percentage: 10 }

exporters:
  prometheusremotewrite:
    endpoint: http://prometheus:9090/api/v1/write
  otlp/jaeger:
    endpoint: jaeger-collector:4317
  loki:
    endpoint: http://loki:3100/loki/api/v1/push

service:
  pipelines:
    metrics:
      receivers: [otlp, prometheus]
      processors: [memory_limiter, batch]
      exporters: [prometheusremotewrite]
    traces:
      receivers: [otlp]
      processors: [memory_limiter, tail_sampling, batch]
      exporters: [otlp/jaeger]
    logs:
      receivers: [otlp]
      processors: [memory_limiter, attributes, batch]
      exporters: [loki]
```

### 2.2 Java Auto-Instrumentation

```yaml
# Kubernetes annotation for automatic OTel injection
metadata:
  annotations:
    instrumentation.opentelemetry.io/inject-java: "true"
    
# OpenTelemetry Operator auto-instruments:
# • Spring Web (REST controllers)
# • gRPC client/server
# • Kafka producer/consumer
# • JDBC/HikariCP
# • Redis (Lettuce/Jedis)
# • HTTP clients (WebClient, RestTemplate)
```

---

## 3. Metrics Strategy

### 3.1 Golden Signals (Per Service)

| Signal | Metric | Alert Threshold |
|--------|--------|-----------------|
| **Latency** | http_server_request_duration_seconds (p50, p95, p99) | p99 > 500ms |
| **Traffic** | http_server_request_total (rate) | Anomaly detection (±3σ) |
| **Errors** | http_server_request_total{status=~"5.."} / total | Error rate > 0.5% |
| **Saturation** | process_cpu_usage, jvm_memory_used_bytes | CPU > 80%, Memory > 85% |

### 3.2 Business Metrics

| Domain | Metric | Purpose |
|--------|--------|---------|
| LOS | originex_applications_submitted_total | Application volume |
| LOS | originex_applications_approved_rate | Approval rate monitoring |
| LOS | originex_application_processing_duration_seconds | SLA compliance |
| LMS | originex_active_loans_total | Portfolio size |
| LMS | originex_disbursements_total | Disbursement volume |
| LMS | originex_delinquent_loans_total{bucket} | NPA monitoring |
| Ledger | originex_journal_entries_posted_total | Transaction throughput |
| Ledger | originex_ledger_balance_discrepancy_total | Reconciliation health |
| Payments | originex_payment_success_rate | Payment rail health |
| Payments | originex_payment_processing_duration_seconds | Payment SLA |
| Collections | originex_collection_recovery_rate | Recovery efficiency |
| Platform | originex_kafka_consumer_lag | Event processing health |

### 3.3 SLO Definitions

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SERVICE LEVEL OBJECTIVES (SLOs)                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PLATFORM-LEVEL SLOs:                                                        │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │  SLO: API Availability                                          │         │
│  │  SLI: Successful requests / Total requests                      │         │
│  │  Target: 99.99% (52.6 min/year error budget)                    │         │
│  │  Window: 30 days rolling                                        │         │
│  │  Alert: Burn rate > 14.4 (page), > 6 (ticket)                  │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │  SLO: API Latency                                               │         │
│  │  SLI: Requests completed within threshold / Total requests      │         │
│  │  Target: 99% of requests < 500ms (p99)                          │         │
│  │  Window: 30 days rolling                                        │         │
│  │  Alert: Burn rate > 14.4 (page), > 6 (ticket)                  │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │  SLO: Event Processing Freshness                                │         │
│  │  SLI: Events processed within 5s / Total events                 │         │
│  │  Target: 99.9%                                                  │         │
│  │  Window: 1 hour rolling                                         │         │
│  │  Alert: Kafka consumer lag > 10,000 messages                    │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────┐         │
│  │  SLO: Disbursement Success                                      │         │
│  │  SLI: Successful disbursements / Total attempted                │         │
│  │  Target: 99.5%                                                  │         │
│  │  Window: 24 hours rolling                                       │         │
│  │  Alert: Failure rate > 1% in 15 min window                      │         │
│  └────────────────────────────────────────────────────────────────┘         │
│                                                                              │
│  ERROR BUDGET POLICY:                                                        │
│  • > 50% budget remaining: Normal development velocity                       │
│  • 25-50% remaining: Reduce risky changes, increase testing                  │
│  • < 25% remaining: Feature freeze, reliability focus only                   │
│  • 0% exhausted: All changes require SRE approval                            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Distributed Tracing

### 4.1 Trace Context Propagation

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    TRACE PROPAGATION                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  HTTP Headers (W3C Trace Context):                                           │
│  • traceparent: 00-{trace-id}-{span-id}-{trace-flags}                       │
│  • tracestate: originex=tenant_id:{id},correlation_id:{id}                  │
│                                                                              │
│  Kafka Headers:                                                              │
│  • traceparent: Same W3C format                                              │
│  • X-Correlation-Id: Original request correlation                            │
│  • X-Tenant-Id: Tenant context                                               │
│                                                                              │
│  gRPC Metadata:                                                              │
│  • Automatic propagation via OTel interceptor                                │
│  • grpc-trace-bin: Binary trace context                                      │
│                                                                              │
│  SAMPLING STRATEGY:                                                          │
│  • Head-based: 100% for errors, 10% for success (at service)                │
│  • Tail-based: OTel Collector applies intelligent sampling:                  │
│    - All errors: 100% retained                                               │
│    - High latency (> 1s): 100% retained                                     │
│    - Financial operations: 100% retained                                     │
│    - Normal success: 10% retained                                            │
│  • Result: Full visibility into issues, manageable storage                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Trace Visualization Example

```
Trace: Loan Disbursement (trace-id: abc-123, duration: 2.3s)
├── [200ms] api-gateway: POST /v1/loans/{id}/disbursements
│   ├── JWT validation (5ms)
│   ├── Rate limit check (2ms)
│   └── Route to lms-service
├── [150ms] lms-service: CreateDisbursement
│   ├── Load loan aggregate (20ms, DB)
│   ├── Validate disbursement rules (10ms)
│   ├── gRPC → customer-service: ValidateAccount (80ms)
│   └── Save + Outbox event (40ms, DB TX)
├── [50ms] kafka: Publish DisbursementRequested
├── [1500ms] payment-service: ProcessDisbursement
│   ├── Load payment order (20ms, DB)
│   ├── gRPC → partner-service: InitiateNEFT (1200ms)  ← EXTERNAL
│   ├── Save payment status (30ms, DB)
│   └── Publish PaymentInitiated (50ms, Kafka)
├── [200ms] ledger-service: PostJournalEntry
│   ├── Validate double-entry (10ms)
│   ├── Append event (30ms, Event Store)
│   ├── Update balance snapshot (20ms)
│   └── Publish JournalEntryPosted (50ms)
└── [50ms] notification-service: SendDisbursementSMS
    ├── Load template (5ms, Redis)
    ├── Render template (2ms)
    └── Queue to SMS provider (40ms)
```

---

## 5. Structured Logging

### 5.1 Log Format (JSON)

```json
{
  "timestamp": "2026-07-08T10:30:00.123Z",
  "level": "INFO",
  "logger": "com.originex.lms.application.service.DisbursementService",
  "message": "Disbursement initiated",
  "service": "lms-service",
  "version": "1.5.2",
  "environment": "production",
  "trace_id": "abc123def456",
  "span_id": "789ghi",
  "correlation_id": "req-uuid-001",
  "tenant_id": "tenant-acme",
  "actor_id": "api-client-xyz",
  "loan_id": "loan-123",
  "amount": "500000.00",
  "currency": "INR",
  "duration_ms": 150,
  "kubernetes": {
    "namespace": "originex-core",
    "pod": "lms-service-7f8b9c-4xk2l",
    "node": "ip-10-0-1-42"
  }
}
```

### 5.2 Log Levels Usage

| Level | Usage | Examples | Indexing |
|-------|-------|----------|----------|
| ERROR | Unexpected failures requiring attention | Unhandled exceptions, data corruption | Full index, alert |
| WARN | Expected issues that may need review | Circuit breaker open, retry exhausted | Full index |
| INFO | Significant business events | Loan disbursed, payment received | Full index |
| DEBUG | Detailed diagnostic info | Method entry/exit, intermediate state | Sampled (10%) |
| TRACE | Very detailed debugging | Full request/response bodies | Dev only |

### 5.3 PII Masking in Logs

```java
// Custom LogMasking filter (applied globally)
@Component
public class PiiMaskingFilter implements LogFilter {
    
    // Patterns that trigger masking
    private static final Map<String, MaskStrategy> SENSITIVE_FIELDS = Map.of(
        "pan", MaskStrategy.LAST_4,        // XXXXX1234
        "aadhaar", MaskStrategy.FULL,      // ************
        "account_number", MaskStrategy.LAST_4,
        "phone", MaskStrategy.LAST_3,      // XXXXXXX890
        "email", MaskStrategy.PARTIAL,     // r***@example.com
        "dob", MaskStrategy.FULL           // ****-**-**
    );
}
```

---

## 6. Alerting Strategy

### 6.1 Alert Severity Levels

| Severity | Response Time | Channel | Example |
|----------|--------------|---------|---------|
| P1 — Critical | 5 min (24/7) | PagerDuty + Phone | Platform down, data loss risk |
| P2 — High | 15 min (24/7) | PagerDuty + Slack | Service degraded, SLO burning fast |
| P3 — Medium | 4 hours (business) | Slack + Ticket | Non-critical service issue |
| P4 — Low | Next business day | Ticket | Performance degradation, non-urgent |

### 6.2 Key Alert Rules

```yaml
# Prometheus alert rules
groups:
- name: originex-critical
  rules:
  - alert: LedgerServiceDown
    expr: up{job="ledger-service"} == 0
    for: 30s
    labels:
      severity: P1
      team: finance-platform
    annotations:
      summary: "Ledger service is down"
      runbook: "https://wiki.originex.io/runbooks/ledger-down"
      
  - alert: HighErrorRate
    expr: |
      sum(rate(http_server_requests_total{status=~"5.."}[5m])) by (service)
      / sum(rate(http_server_requests_total[5m])) by (service) > 0.01
    for: 2m
    labels:
      severity: P2
    annotations:
      summary: "{{ $labels.service }} error rate > 1%"
      
  - alert: KafkaConsumerLag
    expr: kafka_consumer_group_lag > 50000
    for: 5m
    labels:
      severity: P2
    annotations:
      summary: "Kafka consumer lag > 50K for {{ $labels.consumer_group }}"
      
  - alert: DatabaseConnectionPoolExhausted
    expr: hikaricp_connections_active / hikaricp_connections_max > 0.9
    for: 2m
    labels:
      severity: P2
    annotations:
      summary: "DB connection pool > 90% utilized on {{ $labels.pool }}"
      
  - alert: DisbursementFailureRate
    expr: |
      sum(rate(originex_disbursements_total{status="FAILED"}[15m]))
      / sum(rate(originex_disbursements_total[15m])) > 0.02
    for: 5m
    labels:
      severity: P1
      team: payments
    annotations:
      summary: "Disbursement failure rate > 2%"

  - alert: SLOBurnRateHigh
    expr: |
      slo:sli_error:ratio_rate5m{slo="api-availability"} 
      > (14.4 * (1 - 0.9999))
    for: 2m
    labels:
      severity: P1
    annotations:
      summary: "SLO burn rate critical — error budget exhausting fast"
```

---

## 7. Dashboards

### 7.1 Dashboard Hierarchy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    GRAFANA DASHBOARD HIERARCHY                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Level 1: Executive Overview                                                 │
│  ├── Platform health (green/yellow/red)                                      │
│  ├── Total active loans                                                      │
│  ├── Daily disbursement volume                                               │
│  ├── Payment success rate                                                    │
│  ├── NPA percentage                                                          │
│  └── SLO compliance                                                          │
│                                                                              │
│  Level 2: Service Health (per service)                                       │
│  ├── Golden signals (latency, traffic, errors, saturation)                   │
│  ├── Pod count and scaling events                                            │
│  ├── Kafka consumer lag                                                      │
│  ├── Database connections and query performance                              │
│  └── Circuit breaker state                                                   │
│                                                                              │
│  Level 3: Deep Dive (per domain)                                             │
│  ├── LOS: Application funnel, approval rate, processing time                 │
│  ├── LMS: Disbursement rate, EMI collection rate, DPD distribution           │
│  ├── Ledger: TPS, balance accuracy, reconciliation status                    │
│  ├── Payments: Rail-wise success rate, processing time, pending queue        │
│  ├── Collections: Recovery rate, agent productivity, case aging              │
│  └── Kafka: Partition health, replication lag, disk usage                    │
│                                                                              │
│  Level 4: Infrastructure                                                     │
│  ├── EKS: Node utilization, pod scheduling, OOM kills                        │
│  ├── RDS: CPU, IOPS, connections, replication lag                            │
│  ├── Redis: Memory, evictions, hit rate                                      │
│  ├── OpenSearch: Cluster health, indexing rate, query latency                │
│  └── Network: Traffic, errors, DNS resolution                                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Health Check Strategy

```java
// Spring Boot Actuator health groups
management:
  endpoint:
    health:
      group:
        liveness:
          include: livenessState
        readiness:
          include:
            - readinessState
            - db
            - kafka
            - redis
        startup:
          include:
            - db
            - kafka

// Custom health indicators
@Component
public class KafkaConsumerHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        long maxLag = kafkaConsumerMetrics.getMaxLag();
        if (maxLag > 100_000) {
            return Health.down()
                .withDetail("consumer_lag", maxLag)
                .withDetail("threshold", 100_000)
                .build();
        }
        return Health.up().withDetail("consumer_lag", maxLag).build();
    }
}
```

**Probe Configuration:**

| Probe | Path | Interval | Timeout | Failure Threshold |
|-------|------|----------|---------|-------------------|
| Liveness | /actuator/health/liveness | 10s | 5s | 3 |
| Readiness | /actuator/health/readiness | 5s | 3s | 2 |
| Startup | /actuator/health/startup | 5s | 10s | 30 (allow 150s startup) |

---

## 9. On-Call & Incident Management

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    INCIDENT RESPONSE FLOW                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Alert Fired → PagerDuty → On-Call Engineer → Acknowledge (5 min)            │
│       │                                            │                         │
│       │                                            ▼                         │
│       │                                    Assess Severity                    │
│       │                                            │                         │
│       │                              ┌─────────────┼─────────────┐          │
│       │                              ▼             ▼             ▼          │
│       │                           P1/P2         P3           P4           │
│       │                              │             │             │          │
│       │                              ▼             ▼             ▼          │
│       │                    War Room        Investigate     Create Ticket    │
│       │                    (Slack)         (async)         (Jira)          │
│       │                              │                                      │
│       │                              ▼                                      │
│       │                    Mitigate → Resolve → Post-Mortem                  │
│       │                                                                      │
│  ESCALATION:                                                                 │
│  0 min  → Primary on-call                                                    │
│  15 min → Secondary on-call (if not acknowledged)                            │
│  30 min → Engineering Manager                                                │
│  60 min → VP Engineering (P1 only)                                           │
│                                                                              │
│  POST-MORTEM:                                                                │
│  • Blameless post-mortem within 48 hours                                     │
│  • Action items tracked in Jira                                              │
│  • Shared with broader engineering team                                      │
│  • Patterns reviewed monthly for systemic improvements                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```
