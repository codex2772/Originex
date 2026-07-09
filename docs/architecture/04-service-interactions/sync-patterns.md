# Service Interaction Patterns

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Communication Strategy Overview

| Pattern | Use Case | Technology | Consistency |
|---------|----------|-----------|-------------|
| Synchronous Request-Reply | Query, real-time validation | gRPC (internal), REST (external) | Strong |
| Asynchronous Event-Driven | State changes, notifications | Apache Kafka | Eventual |
| Async Command | Deferred execution, disbursements | Kafka (with reply topic) | Eventual |
| CQRS Query | Reporting, search, dashboards | OpenSearch / Read Models | Eventual |
| Saga Orchestration | Multi-service transactions | Kafka + State Machine | Eventual |

**Decision Rule:** Default to async events. Use sync only when:
1. Caller needs immediate response (< 100ms)
2. Strong consistency is required (financial validation)
3. Simple query without side effects

---

## 2. Synchronous Patterns (gRPC Internal)

### 2.1 Service-to-Service gRPC

```
┌──────────────┐     gRPC/Protobuf      ┌──────────────┐
│  LOS Service │ ───────────────────────► │  BRE Service │
│              │  EligibilityRequest      │              │
│              │ ◄─────────────────────── │              │
│              │  EligibilityResponse     │              │
└──────────────┘                          └──────────────┘
        │
        │  Istio mTLS (automatic)
        │  Circuit Breaker (Resilience4j)
        │  Retry: 3 attempts, exponential backoff
        │  Timeout: 5 seconds (configurable)
        │  Bulkhead: 50 concurrent calls max
        │
```

**gRPC Usage Rules:**
- Real-time eligibility checks (LOS → BRE)
- Customer lookup during application (LOS → Customer)
- Balance inquiry (Payment → Ledger)
- Rate/pricing lookup (LOS → Config)
- Authentication token validation (All → IAM)

**Resilience Patterns:**

```java
// Circuit Breaker Configuration
@CircuitBreaker(name = "bre-service", fallbackMethod = "eligibilityFallback")
@Retry(name = "bre-service")
@Bulkhead(name = "bre-service")
@TimeLimiter(name = "bre-service")
public EligibilityResult checkEligibility(EligibilityRequest request) {
    return breServiceClient.evaluate(request);
}

// Configuration
resilience4j:
  circuitbreaker:
    instances:
      bre-service:
        sliding-window-size: 100
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 10
  retry:
    instances:
      bre-service:
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - io.grpc.StatusRuntimeException
  bulkhead:
    instances:
      bre-service:
        max-concurrent-calls: 50
        max-wait-duration: 100ms
```

### 2.2 REST External APIs

```
┌──────────────┐     HTTPS/REST/JSON     ┌──────────────┐
│   Partner    │ ───────────────────────► │ API Gateway  │
│   System     │  POST /v1/applications   │   (Kong)     │
│              │ ◄─────────────────────── │              │
│              │  201 Created + Location  │              │
└──────────────┘                          └──────┬───────┘
                                                  │
                                                  │ Rate Limited
                                                  │ Authenticated (OAuth2)
                                                  │ Tenant Resolved
                                                  ▼
                                          ┌──────────────┐
                                          │  LOS Service │
                                          └──────────────┘
```

---

## 3. Asynchronous Patterns (Kafka Events)

### 3.1 Event Publishing (Outbox Pattern)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         OUTBOX PATTERN                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────┐                                                           │
│  │  LMS Service │                                                           │
│  │              │                                                           │
│  │  1. Business ├──┐                                                        │
│  │     Logic    │  │  SINGLE TRANSACTION                                    │
│  │              │  │                                                         │
│  │  2. Save     │  │  BEGIN TX                                              │
│  │     Entity   ├──┤  ├─ UPDATE loan SET status = 'DISBURSED'               │
│  │              │  │  ├─ INSERT INTO outbox (event_type, payload, ...)       │
│  │  3. Insert   │  │  COMMIT TX                                             │
│  │     Outbox   ├──┘                                                        │
│  └──────────────┘                                                           │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────┐       │
│  │  Outbox Table (PostgreSQL)                                        │       │
│  │  ┌──────────────────────────────────────────────────────────┐    │       │
│  │  │ id │ aggregate_type │ aggregate_id │ event_type │ payload│    │       │
│  │  │    │ created_at     │ published_at │ status     │        │    │       │
│  │  └──────────────────────────────────────────────────────────┘    │       │
│  └──────────────────────────────────┬───────────────────────────────┘       │
│                                     │                                        │
│                                     │  Debezium CDC (Kafka Connect)          │
│                                     │  Reads WAL, publishes to Kafka         │
│                                     │  Exactly-once via transaction markers  │
│                                     ▼                                        │
│  ┌──────────────────────────────────────────────────────────────────┐       │
│  │  Kafka Topic: originex.lms.loan-events                            │       │
│  │  Key: loan_id (ensures ordering per loan)                         │       │
│  │  Partition: hash(tenant_id + loan_id) % num_partitions            │       │
│  └──────────────────────────────────────────────────────────────────┘       │
│                                                                              │
│  WHY OUTBOX PATTERN:                                                         │
│  • Guarantees atomicity between business state and event publication         │
│  • No dual-write problem (DB + Kafka in same transaction is impossible)      │
│  • Events are durable even if Kafka is temporarily unavailable               │
│  • Enables exactly-once delivery semantics end-to-end                        │
│  • Outbox table serves as audit trail of published events                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Event Consumption (Inbox Pattern)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         INBOX PATTERN                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Kafka Topic ──► Consumer ──► Inbox Table Check ──► Process (if new)        │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────┐       │
│  │  Inbox Table (PostgreSQL)                                         │       │
│  │  ┌──────────────────────────────────────────────────────────┐    │       │
│  │  │ event_id (PK) │ event_type │ processed_at │ status       │    │       │
│  │  └──────────────────────────────────────────────────────────┘    │       │
│  └──────────────────────────────────────────────────────────────────┘       │
│                                                                              │
│  PROCESSING LOGIC:                                                           │
│  1. Consume event from Kafka                                                 │
│  2. Check inbox: SELECT 1 FROM inbox WHERE event_id = ?                      │
│  3. If exists → skip (idempotent, already processed)                         │
│  4. If not exists:                                                           │
│     BEGIN TX                                                                 │
│     ├─ INSERT INTO inbox (event_id, event_type, processed_at)                │
│     ├─ Execute business logic                                                │
│     ├─ Save results                                                          │
│     ├─ Insert into own outbox (if producing downstream events)               │
│     COMMIT TX                                                                │
│  5. Commit Kafka offset                                                      │
│                                                                              │
│  WHY INBOX PATTERN:                                                          │
│  • Guarantees exactly-once processing semantics                              │
│  • Handles Kafka redelivery (at-least-once) gracefully                       │
│  • Event ID serves as natural idempotency key                                │
│  • Enables safe retry without side effects                                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.3 Saga Pattern — Loan Disbursement

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  SAGA: LOAN DISBURSEMENT (Orchestration)                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Orchestrator: LMS Service (Loan Disbursement Saga)                          │
│                                                                              │
│  ┌─────┐      ┌─────┐      ┌─────┐      ┌─────┐      ┌─────┐             │
│  │Step1│─────►│Step2│─────►│Step3│─────►│Step4│─────►│Step5│             │
│  │     │      │     │      │     │      │     │      │     │             │
│  └──┬──┘      └──┬──┘      └──┬──┘      └──┬──┘      └──┬──┘             │
│     │             │            │             │            │                 │
│     ▼             ▼            ▼             ▼            ▼                 │
│  Validate      Create       Create       Initiate     Confirm              │
│  Customer      Loan         Ledger       Payment      Disbursement         │
│  Account       Account      Accounts     Transfer                          │
│  (Customer)    (LMS)        (Ledger)     (Payment)    (LMS)                │
│                                                                              │
│  COMPENSATING ACTIONS (on failure):                                          │
│  Step5 fails → Reverse Payment → Close Ledger Accounts → Cancel Loan       │
│  Step4 fails → Close Ledger Accounts → Cancel Loan                          │
│  Step3 fails → Cancel Loan                                                   │
│  Step2 fails → No compensation needed                                        │
│  Step1 fails → Reject Application                                            │
│                                                                              │
│  SAGA STATE MACHINE:                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐        │
│  │  INITIATED → CUSTOMER_VALIDATED → LOAN_CREATED →                │        │
│  │  LEDGER_SETUP → PAYMENT_INITIATED → PAYMENT_CONFIRMED →        │        │
│  │  COMPLETED                                                       │        │
│  │                                                                  │        │
│  │  Any state → COMPENSATING → COMPENSATED → FAILED                │        │
│  └─────────────────────────────────────────────────────────────────┘        │
│                                                                              │
│  IMPLEMENTATION:                                                             │
│  • Saga state persisted in PostgreSQL (survives restarts)                    │
│  • Each step publishes command to relevant service's command topic            │
│  • Each service replies with success/failure event                            │
│  • Orchestrator listens for replies and advances/compensates                 │
│  • Timeout per step (configurable, default 30s per step)                     │
│  • Maximum saga duration: 5 minutes                                          │
│  • Dead sagas detected and alerted after timeout                             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.4 Saga Pattern — Repayment Processing

```
┌─────────────────────────────────────────────────────────────────────────────┐
│               SAGA: REPAYMENT ALLOCATION (Choreography)                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  This saga uses CHOREOGRAPHY (no central orchestrator) because:              │
│  • Simpler flow with clear linear dependencies                               │
│  • Each service knows exactly what to do on receiving the event              │
│  • Lower latency (no orchestrator hop)                                       │
│                                                                              │
│  Payment         LMS             Ledger         Collections    Notification  │
│  Service         Service         Service        Service        Service       │
│     │               │               │               │               │       │
│     │ PaymentReceived               │               │               │       │
│     ├──────────────►│               │               │               │       │
│     │               │               │               │               │       │
│     │               │ Allocate      │               │               │       │
│     │               │ (Waterfall)   │               │               │       │
│     │               │               │               │               │       │
│     │               │RepaymentAllocated             │               │       │
│     │               ├──────────────►│               │               │       │
│     │               │               │               │               │       │
│     │               │               │ Post Journal  │               │       │
│     │               │               │ Entry         │               │       │
│     │               │               │               │               │       │
│     │               │RepaymentAllocated             │               │       │
│     │               ├──────────────────────────────►│               │       │
│     │               │               │               │               │       │
│     │               │               │          Update DPD           │       │
│     │               │               │          Close Case?          │       │
│     │               │               │               │               │       │
│     │               │RepaymentAllocated             │               │       │
│     │               ├──────────────────────────────────────────────►│       │
│     │               │               │               │               │       │
│     │               │               │               │          Send Receipt │
│     │               │               │               │               │       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Sequence Diagrams — Critical Flows

### 4.1 Loan Application Submission

```
Partner       API GW      LOS         Customer    BRE         Bureau      Notification
  │             │          │             │          │            │             │
  │ POST /v1/  │          │             │          │            │             │
  │ applications│          │             │          │            │             │
  ├────────────►│          │             │          │            │             │
  │             │ Validate │             │          │            │             │
  │             │ Token    │             │          │            │             │
  │             ├─────────►│             │          │            │             │
  │             │          │             │          │            │             │
  │             │          │ Get Customer│          │            │             │
  │             │          ├────────────►│          │            │             │
  │             │          │◄────────────┤          │            │             │
  │             │          │             │          │            │             │
  │             │          │ Check Eligibility      │            │             │
  │             │          ├────────────────────────►│            │             │
  │             │          │◄────────────────────────┤            │             │
  │             │          │             │          │            │             │
  │             │          │ Save Application       │            │             │
  │             │          │ + Outbox Event         │            │             │
  │             │          │──┐           │          │            │             │
  │             │          │  │ TX        │          │            │             │
  │             │          │◄─┘           │          │            │             │
  │             │          │             │          │            │             │
  │◄────────────┤◄─────────┤ 202 Accepted│          │            │             │
  │ 202 +       │          │             │          │            │             │
  │ Location    │          │             │          │            │             │
  │             │          │             │          │            │             │
  │             │    [Async via Kafka: ApplicationSubmitted]      │             │
  │             │          │             │          │            │             │
  │             │          │ Initiate Bureau Pull   │            │             │
  │             │          ├───────────────────────────────────►│             │
  │             │          │             │          │            │             │
  │             │          │◄───────────────────────────────────┤             │
  │             │          │ Bureau Response        │            │             │
  │             │          │             │          │            │             │
  │             │          │ Credit Decision        │            │             │
  │             │          ├────────────────────────►│            │             │
  │             │          │◄────────────────────────┤            │             │
  │             │          │             │          │            │             │
  │             │    [Kafka: ApplicationApproved / ApplicationRejected]        │
  │             │          │             │          │            │             │
  │             │          │             │          │            ├────────────►│
  │             │          │             │          │            │  Notify     │
  │             │          │             │          │            │  Applicant  │
  │             │          │             │          │            │             │
  │  Webhook    │          │             │          │            │             │
  │◄────────────┤◄─────────┤             │          │            │             │
  │ (Status)    │          │             │          │            │             │
```

---

## 5. Error Handling & Retry Strategy

### 5.1 Retry Classification

| Error Type | Strategy | Example |
|-----------|----------|---------|
| Transient | Retry with exponential backoff | Network timeout, 503, Kafka broker unavailable |
| Business Validation | No retry, return error | Invalid amount, customer not found |
| Infrastructure | Circuit breaker + retry | Database connection pool exhausted |
| Partner API | Retry + circuit breaker + fallback | Bureau API timeout |
| Data Conflict | Retry with fresh read | Optimistic lock exception |
| Poison Message | Send to DLQ + alert | Deserialization failure |

### 5.2 Dead Letter Queue (DLQ) Strategy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DLQ STRATEGY                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Main Topic ──► Consumer ──► Process                                         │
│       │                          │                                           │
│       │                          │ Failure (after 3 retries)                 │
│       │                          ▼                                           │
│       │                    Retry Topic (with backoff)                         │
│       │                          │                                           │
│       │                          │ Still failing (after 3 more)              │
│       │                          ▼                                           │
│       │                    DLQ Topic                                          │
│       │                          │                                           │
│       │                          ▼                                           │
│       │                    Alert + Manual Review                              │
│       │                          │                                           │
│       │                          │ Fixed? Replay from DLQ                    │
│       │                          ▼                                           │
│       │◄─────────────────── Republish to Main Topic                          │
│                                                                              │
│  TOPIC NAMING:                                                               │
│  Main:   originex.lms.loan-events                                            │
│  Retry:  originex.lms.loan-events.retry-1                                    │
│  DLQ:    originex.lms.loan-events.dlq                                        │
│                                                                              │
│  RETRY DELAYS:                                                               │
│  Attempt 1: 1 second                                                         │
│  Attempt 2: 5 seconds                                                        │
│  Attempt 3: 30 seconds                                                       │
│  Attempt 4-6: Via retry topic (1min, 5min, 15min)                            │
│  After 6 attempts: DLQ                                                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Idempotency Strategy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         IDEMPOTENCY IMPLEMENTATION                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  REST API (Client-Generated Idempotency Key):                                │
│  ──────────────────────────────────────────────                              │
│  Header: Idempotency-Key: <UUID>                                             │
│                                                                              │
│  1. Client sends request with Idempotency-Key header                         │
│  2. Server checks Redis: GET idempotency:{tenant}:{key}                      │
│  3. If exists → return cached response (HTTP 200 with same body)             │
│  4. If not exists:                                                           │
│     a. SET idempotency:{tenant}:{key} PROCESSING NX EX 300                   │
│     b. Process request                                                       │
│     c. SET idempotency:{tenant}:{key} {response} EX 86400                    │
│     d. Return response                                                       │
│  5. If PROCESSING → return 409 Conflict (concurrent duplicate)               │
│                                                                              │
│  Event Processing (Event ID as Idempotency Key):                             │
│  ─────────────────────────────────────────────────                            │
│  1. Every event has unique event_id (UUIDv7)                                 │
│  2. Inbox table enforces uniqueness on event_id                              │
│  3. INSERT INTO inbox ... ON CONFLICT DO NOTHING                             │
│  4. If affected rows = 0 → skip processing                                  │
│                                                                              │
│  Payment Processing (Business Idempotency):                                  │
│  ──────────────────────────────────────────────                              │
│  1. PaymentOrder has unique reference_id (tenant + loan + date + sequence)   │
│  2. Unique constraint on (tenant_id, reference_id)                           │
│  3. Prevents duplicate disbursements/collections                             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Backpressure & Flow Control

| Layer | Mechanism | Configuration |
|-------|-----------|---------------|
| API Gateway | Rate limiting per tenant | 1000 req/min default, configurable |
| Kafka Consumer | `max.poll.records` | 500 records per poll |
| Kafka Consumer | Pause/Resume on backpressure | Monitor lag, pause at threshold |
| gRPC | Flow control (built-in) | Window-based flow control |
| Database | Connection pool limit | HikariCP max-pool-size: 20 |
| Flink | Backpressure propagation | Natural Flink mechanism |
| Bulkhead | Concurrent call limit | Resilience4j semaphore |
