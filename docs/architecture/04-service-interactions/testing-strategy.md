# Testing Strategy

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Testing Philosophy

| Principle | Implementation |
|-----------|---------------|
| Test Pyramid | Unit (70%) > Integration (20%) > E2E (10%) |
| Shift Left | Catch defects at the earliest possible stage |
| Financial Correctness | Monetary calculations have 100% coverage + property-based tests |
| Contract Safety | Consumer-driven contracts prevent breaking changes across services |
| Realistic Environments | Testcontainers for real databases/Kafka in CI |
| Deterministic | No flaky tests; all tests produce consistent results |
| Fast Feedback | Unit tests < 60s; Integration < 5 min; E2E < 15 min |

---

## 2. Testing Pyramid

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         TESTING PYRAMID                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                          ╱╲                                                  │
│                         ╱  ╲      E2E Tests (Smoke / Critical Path)          │
│                        ╱ 10%╲     • 50-100 tests                             │
│                       ╱______╲    • Real environment (Staging)                │
│                      ╱        ╲   • 15 min execution                         │
│                     ╱          ╲                                              │
│                    ╱  20%       ╲  Integration Tests                          │
│                   ╱              ╲ • 200-500 tests per service                │
│                  ╱________________╲• Testcontainers (real DB, Kafka, Redis)   │
│                 ╱                  ╲• 3-5 min execution                       │
│                ╱                    ╲                                         │
│               ╱       70%           ╲ Unit Tests                              │
│              ╱                       ╲• 500-2000 tests per service            │
│             ╱_________________________╲• Mocked dependencies                  │
│                                        • < 60s execution                     │
│                                                                              │
│  ADDITIONAL LAYERS (Periodic, not per-commit):                               │
│  ┌─────────────────────────────────────────────────────────────────┐        │
│  │  Contract Tests      │ Per-PR    │ Pact (consumer-driven)        │        │
│  │  Architecture Tests  │ Per-PR    │ ArchUnit (structural)         │        │
│  │  Performance Tests   │ Weekly    │ Gatling (load/stress)         │        │
│  │  Security Tests      │ Weekly    │ OWASP ZAP (DAST)              │        │
│  │  Chaos Tests         │ Monthly   │ Litmus (fault injection)      │        │
│  │  Penetration Tests   │ Quarterly │ External vendor               │        │
│  │  DR Tests            │ Quarterly │ Full failover drill           │        │
│  └─────────────────────────────────────────────────────────────────┘        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Unit Testing

### 3.1 Scope & Tools

| Layer | What to Test | Tool | Coverage Target |
|-------|-------------|------|-----------------|
| Domain Model | Aggregate invariants, business rules, value objects | JUnit 5 | 95% |
| Domain Services | Business logic orchestration | JUnit 5 + Mockito | 90% |
| Application Services | Use case coordination, input validation | JUnit 5 + Mockito | 85% |
| Value Objects (Money) | Arithmetic, rounding, equality, immutability | JUnit 5 + Property-based | 100% |
| Event Serialization | Protobuf serialize/deserialize round-trip | JUnit 5 | 100% |
| Mappers/DTOs | DTO ↔ Domain mapping correctness | JUnit 5 | 90% |

### 3.2 Financial Calculation Tests (Property-Based)

```java
// Property-based testing for financial correctness
@Property(tries = 10000)
void interestAccrual_shouldBeDeterministic(
    @ForAll @BigDecimalRange(min = "1000", max = "10000000") BigDecimal principal,
    @ForAll @BigDecimalRange(min = "0.01", max = "0.36") BigDecimal annualRate,
    @ForAll @IntRange(min = 1, max = 365) int days
) {
    // Given same inputs
    Money principalMoney = Money.of(principal, "INR");
    
    // When calculated twice
    Money accrual1 = interestCalculator.calculateDailyAccrual(
        principalMoney, annualRate, days, DayCountConvention.ACTUAL_365
    );
    Money accrual2 = interestCalculator.calculateDailyAccrual(
        principalMoney, annualRate, days, DayCountConvention.ACTUAL_365
    );
    
    // Then results are identical (deterministic)
    assertThat(accrual1).isEqualTo(accrual2);
    
    // And result is non-negative
    assertThat(accrual1.getAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    
    // And result uses HALF_EVEN rounding
    assertThat(accrual1.getAmount().scale()).isEqualTo(4);
}

@Property(tries = 1000)
void repaymentAllocation_shouldExhaustPaymentAmount(
    @ForAll @BigDecimalRange(min = "100", max = "100000") BigDecimal paymentAmount,
    @ForAll @BigDecimalRange(min = "10", max = "5000") BigDecimal charges,
    @ForAll @BigDecimalRange(min = "100", max = "50000") BigDecimal interest,
    @ForAll @BigDecimalRange(min = "1000", max = "500000") BigDecimal principal
) {
    Money payment = Money.of(paymentAmount, "INR");
    AllocationResult result = allocationService.allocate(
        payment, 
        Money.of(charges, "INR"),
        Money.of(interest, "INR"),
        Money.of(principal, "INR")
    );
    
    // Total allocated must equal payment or total outstanding (whichever is less)
    Money totalOutstanding = Money.of(charges.add(interest).add(principal), "INR");
    Money expectedAllocation = payment.min(totalOutstanding);
    
    assertThat(result.totalAllocated()).isEqualTo(expectedAllocation);
}
```

### 3.3 Architecture Tests (ArchUnit)

```java
@AnalyzeClasses(packages = "com.originex.lms")
class LmsArchitectureTest {

    @ArchTest
    static final ArchRule domainShouldNotDependOnInfrastructure =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..adapter..", "..config..", "..infrastructure..");

    @ArchTest
    static final ArchRule applicationShouldNotDependOnAdapters =
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule noFloatOrDoubleForMoney =
        noClasses().that().resideInAPackage("com.originex..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("java.lang.Float")
            .orShould().dependOnClassesThat()
            .haveFullyQualifiedName("java.lang.Double");

    @ArchTest
    static final ArchRule allEntitiesMustHaveTenantId =
        classes().that().areAnnotatedWith(Entity.class)
            .should().haveAtLeastOneFieldOfType(TenantId.class);

    @ArchTest
    static final ArchRule controllersShouldOnlyCallUseCases =
        classes().that().resideInAPackage("..adapter.in.rest..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(
                "..application.port.in..",
                "..application.dto..",
                "..domain.model..",
                "java..",
                "org.springframework.."
            );
}
```

---

## 4. Integration Testing

### 4.1 Testcontainers Setup

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
abstract class BaseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("originex_test")
        .withInitScript("init-test-db.sql");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.6.0")
    );

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
```

### 4.2 Integration Test Categories

| Category | What It Validates | Example |
|----------|------------------|---------|
| Repository Tests | SQL queries, JPA mappings, RLS | Save and retrieve a Loan with correct tenant isolation |
| Kafka Producer Tests | Event serialization, topic routing | Publish LoanDisbursed event, verify on topic |
| Kafka Consumer Tests | Event handling, idempotency, inbox | Consume PaymentReceived, verify allocation |
| gRPC Client Tests | Service-to-service calls | Call BRE service, verify eligibility response |
| Outbox Pattern Tests | Atomicity of business + event | Save loan + outbox entry in single transaction |
| Saga Tests | Multi-step orchestration | Disbursement saga: happy path + compensation |
| Cache Tests | Redis caching behavior | Cache loan schedule, verify cache hit/miss |

### 4.3 Event-Driven Integration Tests

```java
@Test
void whenPaymentReceived_shouldAllocateAndPublishEvents() {
    // Given: An active loan with outstanding balance
    Loan loan = loanFixture.createActiveLoan(tenantId, "500000.00");
    
    // When: Payment received event is published
    PaymentReceivedEvent event = PaymentReceivedEvent.newBuilder()
        .setLoanId(loan.getId().toString())
        .setAmount(Money.newBuilder().setValue("15000.00").setCurrency("INR"))
        .setPaymentDate(Timestamps.fromMillis(Instant.now().toEpochMilli()))
        .build();
    
    kafkaTemplate.send("originex.payments.orders.events", 
        loan.getId().toString(), wrapInEnvelope(event));
    
    // Then: Repayment is allocated (waterfall: charges → interest → principal)
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
        Loan updated = loanRepository.findById(loan.getId()).orElseThrow();
        assertThat(updated.getOutstandingInterest())
            .isLessThan(loan.getOutstandingInterest());
    });
    
    // And: RepaymentAllocated event is published
    ConsumerRecord<String, byte[]> published = 
        kafkaConsumer.poll("originex.lms.loans.events", Duration.ofSeconds(5));
    assertThat(published).isNotNull();
    
    EventEnvelope envelope = EventEnvelope.parseFrom(published.value());
    assertThat(envelope.getEventType())
        .isEqualTo("originex.lms.RepaymentAllocated");
    
    // And: Inbox table has entry (idempotency)
    assertThat(inboxRepository.existsById(event.getEventId())).isTrue();
    
    // And: Reprocessing same event is idempotent
    kafkaTemplate.send("originex.payments.orders.events", 
        loan.getId().toString(), wrapInEnvelope(event));
    
    await().during(Duration.ofSeconds(3)).untilAsserted(() -> {
        // Balance should not change again
        Loan recheck = loanRepository.findById(loan.getId()).orElseThrow();
        assertThat(recheck.getOutstandingPrincipal())
            .isEqualTo(updated.getOutstandingPrincipal());
    });
}
```

---

## 5. Contract Testing (Pact)

### 5.1 Consumer-Driven Contract Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CONTRACT TESTING FLOW                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  CONSUMER SIDE (e.g., LOS consuming BRE API):                                │
│  1. LOS defines a Pact (expected request/response)                           │
│  2. Pact file published to Pact Broker                                       │
│  3. LOS CI passes ✓                                                          │
│                                                                              │
│  PROVIDER SIDE (e.g., BRE providing eligibility API):                        │
│  4. BRE CI pulls consumer pacts from Broker                                  │
│  5. BRE replays consumer expectations against real service                   │
│  6. If all pass → BRE is compatible with LOS ✓                               │
│  7. If any fail → BRE broke the contract → CI fails ✗                        │
│                                                                              │
│  KAFKA EVENTS (Message Pact):                                                │
│  • Producer defines event schema as Pact                                     │
│  • Consumers verify they can parse producer's events                         │
│  • Ensures Protobuf evolution doesn't break consumers                        │
│                                                                              │
│  DEPLOYMENT SAFETY:                                                          │
│  • "Can I deploy?" check before production promotion                         │
│  • Pact Broker matrix shows compatibility between all versions               │
│  • Deploy only if all consumer/provider combinations are compatible          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Contract Test Example

```java
// Consumer side (LOS testing its expectation of BRE)
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "bre-service")
class BreClientContractTest {

    @Pact(consumer = "los-service")
    V4Pact eligibilityCheckPact(PactDslWithProvider builder) {
        return builder
            .given("customer with good credit score")
            .uponReceiving("eligibility check request")
            .method("POST")
            .path("/v1/rules/evaluate")
            .headers("Content-Type", "application/json")
            .body(newJsonBody(body -> {
                body.stringValue("rule_set", "PERSONAL_LOAN_ELIGIBILITY");
                body.object("context", ctx -> {
                    ctx.numberValue("credit_score", 750);
                    ctx.stringValue("employment_type", "SALARIED");
                    ctx.decimalType("monthly_income", 85000.00);
                });
            }).build())
            .willRespondWith()
            .status(200)
            .body(newJsonBody(body -> {
                body.booleanValue("eligible", true);
                body.decimalType("max_amount", 1000000.00);
                body.decimalType("offered_rate", 12.5);
            }).build())
            .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "eligibilityCheckPact")
    void testEligibilityCheck(MockServer mockServer) {
        BreClient client = new BreClient(mockServer.getUrl());
        EligibilityResult result = client.checkEligibility(request);
        
        assertThat(result.isEligible()).isTrue();
        assertThat(result.getMaxAmount()).isGreaterThan(BigDecimal.ZERO);
    }
}
```

---

## 6. Performance Testing

### 6.1 Performance Test Strategy

| Test Type | Purpose | Tool | Frequency | Duration |
|-----------|---------|------|-----------|----------|
| Load Test | Validate under expected load | Gatling | Weekly | 30 min |
| Stress Test | Find breaking point | Gatling | Bi-weekly | 60 min (ramp) |
| Soak Test | Detect memory leaks, degradation | Gatling | Monthly | 8 hours |
| Spike Test | Validate auto-scaling | Gatling | Monthly | 15 min |
| Capacity Planning | Project future requirements | Gatling + analysis | Quarterly | Variable |

### 6.2 Performance Baselines

| Endpoint / Flow | Target p50 | Target p95 | Target p99 | Target TPS |
|----------------|-----------|-----------|-----------|-----------|
| POST /v1/loan-applications | < 100ms | < 300ms | < 500ms | 1,200 |
| GET /v1/loans/{id} | < 20ms | < 50ms | < 100ms | 5,000 |
| POST /v1/loans/{id}/disbursements | < 200ms | < 500ms | < 1000ms | 500 |
| Repayment allocation (event) | < 50ms | < 100ms | < 200ms | 10,000 |
| Interest accrual (Flink, per loan) | < 5ms | < 10ms | < 20ms | 100,000 |
| Ledger journal entry | < 30ms | < 80ms | < 150ms | 20,000 |
| BRE rule evaluation | < 10ms | < 30ms | < 50ms | 20,000 |

### 6.3 Gatling Scenario Example

```scala
class LoanOriginationSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("https://staging-api.originex.io/acme-bank")
    .header("Authorization", "Bearer ${token}")
    .header("Content-Type", "application/json")

  val submitApplication = scenario("Loan Application Submission")
    .exec(
      http("Submit Application")
        .post("/v1/loan-applications")
        .body(ElFileBody("application-payload.json"))
        .check(status.is(202))
        .check(jsonPath("$.data.id").saveAs("applicationId"))
    )
    .pause(1.second)
    .exec(
      http("Check Application Status")
        .get("/v1/loan-applications/${applicationId}")
        .check(status.is(200))
    )

  val repaymentProcessing = scenario("Repayment Burst")
    .exec(
      http("Submit Repayment")
        .post("/v1/payments/orders")
        .body(ElFileBody("repayment-payload.json"))
        .header("X-Idempotency-Key", "${idempotencyKey}")
        .check(status.is(202))
    )

  setUp(
    submitApplication.inject(
      rampUsersPerSec(10).to(100).during(5.minutes),   // Ramp up
      constantUsersPerSec(100).during(20.minutes),      // Sustained load
      rampUsersPerSec(100).to(10).during(5.minutes)    // Ramp down
    ),
    repaymentProcessing.inject(
      constantUsersPerSec(500).during(10.minutes)       // High-throughput burst
    )
  ).protocols(httpProtocol)
   .assertions(
     global.responseTime.percentile(99).lt(500),
     global.successfulRequests.percent.gt(99.5)
   )
}
```

---

## 7. Security Testing

| Test Type | Tool | Frequency | Focus |
|-----------|------|-----------|-------|
| SAST (Static) | SonarQube, Semgrep | Every commit | Code vulnerabilities, SQL injection patterns |
| SCA (Dependency) | OWASP Dependency Check, Snyk | Every build | Known CVEs in dependencies |
| DAST (Dynamic) | OWASP ZAP | Weekly (staging) | Runtime vulnerabilities, XSS, injection |
| Container Scan | Trivy | Every build | OS-level CVEs in container images |
| Secret Scan | GitLeaks, TruffleHog | Every commit | Leaked credentials, API keys |
| Penetration Test | External vendor | Quarterly | Full adversarial assessment |
| API Security Test | Custom suite | Weekly | Auth bypass, BOLA, rate limit bypass |
| Infrastructure Audit | Prowler, ScoutSuite | Weekly | AWS misconfigurations |

---

## 8. Chaos Engineering

### 8.1 Chaos Experiments

| Experiment | Target | Expected Outcome | Recovery |
|-----------|--------|-------------------|----------|
| Kill random pod | Any service | Auto-restart, no data loss | < 30s via Kubernetes |
| Network partition (service-to-DB) | LMS ↔ PostgreSQL | Circuit breaker opens, graceful degradation | Reconnect on partition heal |
| Kafka broker failure | 1 of 6 brokers | Automatic leader election, no message loss | < 10s failover |
| Slow downstream (latency injection) | Partner Service | Timeout + circuit breaker, fallback response | Circuit closes after recovery |
| CPU stress (100%) | LMS pods | HPA scales up, request queue grows | Scale event < 60s |
| Redis cluster node failure | 1 of 6 nodes | Automatic failover to replica | < 5s failover |
| Full AZ failure | One availability zone | Traffic routes to remaining AZs | Seamless (multi-AZ) |
| DLQ flood | Kafka consumer | DLQ alert fires, consumer continues processing new events | Manual review |
| Database failover | RDS Multi-AZ | Automatic promotion of standby | < 2 min |

### 8.2 Chaos Testing Framework (Litmus)

```yaml
apiVersion: litmuschaos.io/v1alpha1
kind: ChaosEngine
metadata:
  name: lms-pod-kill
  namespace: originex-core
spec:
  appinfo:
    appns: originex-core
    applabel: app=lms-service
    appkind: deployment
  chaosServiceAccount: litmus-admin
  experiments:
  - name: pod-delete
    spec:
      components:
        env:
        - name: TOTAL_CHAOS_DURATION
          value: '30'
        - name: CHAOS_INTERVAL
          value: '10'
        - name: FORCE
          value: 'true'
  # Steady-state hypothesis: API still responds
  # Probe: HTTP health check returns 200
```

---

## 9. Disaster Recovery Testing

| Test | Frequency | Scope | Validation |
|------|-----------|-------|-----------|
| Backup restoration | Weekly | Single service DB | Restore from latest snapshot, verify data integrity |
| Cross-region failover (partial) | Monthly | Single service | Route traffic to DR, verify functionality |
| Cross-region failover (full) | Quarterly | All services | Complete DR activation, validate all flows |
| Kafka MirrorMaker validation | Weekly | Event replication | Verify lag < 1s, message integrity |
| Point-in-time recovery | Monthly | Ledger database | Restore to specific timestamp, verify balances |
| Runbook execution drill | Monthly | Operational team | Execute runbook steps, measure completion time |

---

## 10. Test Data Management

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    TEST DATA STRATEGY                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ENVIRONMENTS:                                                               │
│                                                                              │
│  Unit Tests:                                                                 │
│  • In-memory fixtures (Builder pattern)                                      │
│  • No external dependencies                                                  │
│  • Deterministic, fast                                                       │
│                                                                              │
│  Integration Tests:                                                          │
│  • Testcontainers (fresh DB per test class)                                  │
│  • Flyway migrations applied automatically                                   │
│  • Test fixtures loaded per test                                             │
│  • Cleanup via @Transactional rollback or truncation                         │
│                                                                              │
│  Staging:                                                                    │
│  • Anonymized production snapshot (weekly refresh)                            │
│  • PII replaced with synthetic data (Faker library)                          │
│  • Realistic volume (10% of production)                                      │
│  • Financial data maintains statistical properties                           │
│                                                                              │
│  Performance Tests:                                                          │
│  • Generated data at production-equivalent volume                            │
│  • Realistic distribution (loan amounts, tenures, DPD)                       │
│  • Gatling feeders for parameterized requests                                │
│                                                                              │
│  NEVER use production data in non-production environments without            │
│  anonymization. PII must be replaced with synthetic equivalents.             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 11. Quality Gates

### 11.1 CI Pipeline Gates

| Gate | Metric | Threshold | Action on Failure |
|------|--------|-----------|-------------------|
| Unit Test Coverage | Line coverage | ≥ 80% (domain ≥ 95%) | Block merge |
| Integration Tests | Pass rate | 100% | Block merge |
| Architecture Tests | Violations | 0 | Block merge |
| Static Analysis (Sonar) | Bugs, Vulnerabilities | 0 Critical/High | Block merge |
| Security Scan | CVE severity | No Critical, No High (unpatched) | Block merge |
| Contract Tests | Compatibility | 100% | Block merge |
| Code Formatting | Spotless | Clean | Block merge |
| PR Review | Approvals | ≥ 2 (1 must be senior) | Block merge |

### 11.2 Deployment Gates

| Gate | When | Threshold | Action on Failure |
|------|------|-----------|-------------------|
| Smoke Tests | After dev deploy | 100% pass | Block staging promotion |
| E2E Tests | After staging deploy | 100% critical path pass | Block prod promotion |
| Performance Baseline | After staging deploy | No regression > 10% | Block prod promotion |
| Canary SLO | During prod canary | p99 < 500ms, error < 0.5% | Auto-rollback |
| Pact "Can I Deploy?" | Before prod | All contracts satisfied | Block prod promotion |
