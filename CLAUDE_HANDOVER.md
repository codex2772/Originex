# CLAUDE_HANDOVER.md
## Originex — Enterprise Lending-as-a-Service Platform
### AI Assistant Handover Document

**Last Updated:** 9 July 2026  
**Build Status:** ✅ 13 modules, 276 Java files — BUILD SUCCESS  
**Purpose:** Complete context for a new AI coding assistant to continue development without requiring repeated architecture explanations.

---

## 1. Project Overview

### What Is This System?

**Originex** is a production-grade, cloud-native, multi-tenant **Lending-as-a-Service (LaaS) platform** built for banks, NBFCs, and fintech companies operating in the Indian market. It provides the complete technology stack for running a digital lending business as a B2B white-label offering.

### Business Capabilities Implemented

| Capability | Service | Status |
|---|---|---|
| Customer registration, KYC, bank account onboarding | `customer-service` | ✅ Complete |
| Loan application intake, state machine, offer generation | `los-service` | ✅ Complete |
| Business rules engine — eligibility & auto-decisioning | `bre-service` | ✅ Complete |
| Credit bureau integration (CIBIL, Experian, Equifax, CRIF) | `partner-integration-service` | ✅ Complete (Sandbox) |
| Aadhaar eKYC (DigiLocker), PAN (NSDL), bank penny-drop | `partner-integration-service` | ✅ Complete (Sandbox) |
| Loan lifecycle management, EMI schedule, repayment waterfall | `lms-service` | ✅ Complete |
| Double-entry accounting ledger (event-sourced) | `ledger-service` | ✅ Complete |
| Payment disbursement (NEFT/RTGS/IMPS) and EMI collection (NACH) | `payment-service` | ✅ Complete (Sandbox) |
| Multi-channel notifications (SMS/Email/WhatsApp/Push) | `notification-service` | ✅ Complete (Sandbox) |
| IAM / Authentication | Not built | ❌ Missing |
| Collections & Dunning | Not built | ❌ Missing |
| Audit Service | Not built | ❌ Missing |
| Reporting & Analytics | Not built | ❌ Missing |
| Tenant & Config Management | Not built | ❌ Missing |
| Apache Flink streaming jobs | Not built | ❌ Missing |

### Scale Target
- 20M+ customers, 5M+ active loans
- 100,000+ loan applications/day
- 500M+ ledger transactions/day
- 99.99% availability, multi-region active-active

---

## 2. Technology Stack

### Core

| Technology | Version | Usage |
|---|---|---|
| **Java** | **21** | All services. Virtual threads enabled (`spring.threads.virtual.enabled=true`) |
| **Spring Boot** | **3.4.1** | All services. Parent POM: `spring-boot-starter-parent:3.4.1` |
| **Spring Cloud** | **2024.0.0** | BOM imported, components used as needed |
| **Maven** | 3.x | Build tool. Multi-module reactor. No Gradle. |
| **Maven Compiler Plugin** | 3.13.0 | `release=21`, `parameters=true` |

### Databases

| Technology | Version | Usage |
|---|---|---|
| **PostgreSQL** | 16 (dev), latest (prod) | Primary store for every service. One DB per service. |
| **Flyway** | 10.22.0 | Database migrations. `flyway-database-postgresql` module required. |
| **Spring Data JPA / Hibernate** | Spring Boot managed | ORM. `open-in-view=false` on all services. |
| **Redis** | 7 | Caching (customer-service, los-service, bre-service). `spring-boot-starter-data-redis` |

### Messaging

| Technology | Version | Usage |
|---|---|---|
| **Apache Kafka** | 3.7.2 | All async event communication. KRaft mode (no ZooKeeper) in dev. |
| **Confluent Schema Registry** | 7.7.1 | Proto schema registry (configured, not all services use yet) |
| **Strimzi** | — | Kafka operator on EKS (defined in `infra/kafka/topics.yaml`) |

### Serialization

| Technology | Version | Usage |
|---|---|---|
| **Protocol Buffers** | 4.29.2 | 6 `.proto` files generating 82 Java classes. Schema-first design. |
| **gRPC** | 1.69.0 | BOM imported. BRE service dropped gRPC starter due to private registry conflict. REST used instead. |
| **Jackson** | Spring Boot managed | JSON for REST APIs and current event payloads (not yet Protobuf-wired end-to-end) |

### Resilience

| Technology | Version | Usage |
|---|---|---|
| **Resilience4j** | 2.2.0 | `@CircuitBreaker` and `@Retry` on all outbound HTTP calls and payment rail adapters |

### Observability

| Technology | Version | Usage |
|---|---|---|
| **Micrometer Prometheus** | Spring Boot managed | All services expose `/actuator/prometheus` |
| **OpenTelemetry** | 1.44.1 | BOM imported. Instrumentation not yet wired into services. |
| **Spring Actuator** | Spring Boot managed | Health, info, prometheus endpoints on all services |

### Testing

| Technology | Version | Usage |
|---|---|---|
| **JUnit 5 (Jupiter)** | Spring Boot managed | All tests |
| **AssertJ** | Spring Boot managed | All tests |
| **Mockito** | Spring Boot managed | All tests |
| **Testcontainers** | 1.20.4 | Integration tests (PostgreSQL, Kafka containers) |
| **ArchUnit** | 1.3.0 | Hexagonal architecture enforcement (`HexagonalArchitectureTest.java` in template-service) |
| **WireMock** | 3.10.0 | Dependency declared, not yet used |
| **jqwik** | 1.9.2 | Property-based testing. Dependency declared. |
| **Awaitility** | 4.2.2 | Async test assertions |

### Infrastructure & Deployment

| Technology | Usage |
|---|---|
| **Docker** | Container images via Jib Maven Plugin (3.4.4). Base: `eclipse-temurin:21-jre-alpine` |
| **Docker Compose** | `dev/docker-compose.yml` for local stack (Postgres, Kafka, Redis, Schema Registry, Kafka UI) |
| **AWS EKS** | Kubernetes 1.30, Terraform-managed |
| **Terraform** | Modules for VPC, EKS, RDS, Redis. Environment configs in `infra/terraform/environments/` |
| **Helm** | Generic chart at `infra/helm/originex-service/`. One chart for all services. |
| **GitHub Actions** | CI: `.github/workflows/ci.yml` (compile + unit tests + integration tests) |
| **ArgoCD** | Planned (architecture doc), not yet configured |
| **Istio** | Planned (architecture doc), not yet configured |

### Code Quality

| Technology | Usage |
|---|---|
| **Spotless + Google Java Format** | `mvn spotless:apply`. AOSP style, version 1.24.0 |
| **Surefire** | Unit tests (`**/*Test.java`). Excludes `*IntegrationTest.java`. |
| **Failsafe** | Integration tests (`*IntegrationTest.java`, `*IT.java`) via `-Pintegration-test` |

---

## 3. Repository Structure

### Module Map

```
Originex/                           ← Maven reactor root (pom.xml)
├── proto/                          ← Protobuf schemas → generated Java classes
├── libs/
│   ├── common/                     ← Shared value objects, utilities (no Spring)
│   └── spring-boot-starter/        ← Spring auto-configurations (Outbox, Tenant, Exceptions)
└── services/
    ├── template-service/           ← Canonical reference implementation (copy to create new service)
    ├── customer-service/           ← Customer profiles, KYC, bank accounts  [port 8081]
    ├── los-service/                ← Loan origination, applications, offers  [port 8082]
    ├── lms-service/                ← Loan management, repayment, NPA         [port 8083]
    ├── ledger-service/             ← Double-entry accounting ledger           [port 8084]
    ├── partner-integration-service/← Bureau, Aadhaar, PAN, bank verification [port 8085]
    ├── payment-service/            ← NEFT/RTGS/IMPS disbursement, NACH       [port 8086]
    ├── notification-service/       ← SMS/Email/WhatsApp/Push notifications    [port 8087]
    └── bre-service/                ← Business rules engine, auto-decisioning  [port 8088]
```

### Module Dependency Graph

```
proto  ←──────────────────── All services (via originex-proto)
  ↑
libs/common ←──────────────── All services (Money, TenantContext, DomainEvent)
  ↑
libs/spring-boot-starter ←──── All services (Outbox, TenantFilter, ExceptionHandler)
  ↑
services/*  (no inter-service compile dependencies — communicate via HTTP REST or Kafka)
```

### Dependency Relationships (Compile-Time)
- Every service depends on: `originex-proto`, `originex-common`, `originex-spring-boot-starter`
- **No service depends on another service's JAR.** All cross-service communication is runtime (REST/Kafka).

### Shared Library: `libs/common` (`com.originex.common`)
| Package | Contents |
|---|---|
| `money/` | `Money.java` — immutable BigDecimal value object, HALF_EVEN rounding, scale=4 |
| `tenant/` | `TenantContext.java`, `TenantContextHolder.java` — ThreadLocal tenant isolation |
| `event/` | `DomainEvent.java`, `OutboxEvent.java`, `InboxEvent.java` — event marker interfaces |
| `error/` | `ErrorResponse.java` — RFC 7807 Problem Details response |
| `pagination/` | Cursor-based pagination utilities |

### Shared Library: `libs/spring-boot-starter` (`com.originex.starter`)
| Class | Purpose |
|---|---|
| `OriginexAutoConfiguration` | Registers `TenantResolutionFilter` and `GlobalExceptionHandler` as Spring beans automatically |
| `TenantResolutionFilter` | Servlet filter — reads `X-Tenant-Id` header, sets `TenantContextHolder`, adds to MDC |
| `GlobalExceptionHandler` | `@RestControllerAdvice` — maps exceptions to RFC 7807 JSON responses |
| `OutboxPublisher` | Writes events to `outbox_events` table within the same transaction |
| `OutboxPoller` | Scheduled every 500ms — reads PENDING outbox events, publishes to Kafka |
| `OutboxAutoConfiguration` | Auto-wires outbox beans when JPA + Kafka are on classpath |
| `InboxEventJpaEntity` | JPA entity for inbox deduplication (`inbox_events` table) |
| `OutboxEventJpaEntity` | JPA entity for outbox events (`outbox_events` table) |

---

## 4. Microservice Architecture

### Package Structure Convention (All Services)
Every service follows this **Hexagonal Architecture** (Ports & Adapters) layout:
```
com.originex.{service}/
├── domain/
│   ├── model/          ← Aggregates, Entities, Value Objects (pure Java, no Spring)
│   ├── service/        ← Domain Services
│   └── exception/      ← Domain exceptions
├── application/
│   ├── port/
│   │   ├── in/         ← Use case interfaces (inbound ports)
│   │   └── out/        ← Repository & external service interfaces (outbound ports)
│   └── service/        ← Application services (implements inbound ports)
└── adapter/
    ├── in/
    │   ├── rest/        ← REST controllers (call application ports)
    │   └── kafka/       ← Kafka consumers (call application ports)
    └── out/
        ├── persistence/ ← JPA entities, repositories, adapters (implements outbound ports)
        ├── rest/        ← REST clients to other services (implements outbound ports)
        └── {vendor}/    ← External API adapters (bureau, rails, channels)
```

---

### Service: `customer-service` (Port 8081)

**Business Responsibility:** Single source of truth for customer identity, KYC status, and bank accounts.

**Key Domain Classes:**
- `Customer.java` — Aggregate Root. Invariants: PAN unique per tenant; Aadhaar stored only as irreversible SHA-256 token (DPDPA compliance); KYC must be VERIFIED before loan application.
- `KycRecord.java` — KYC verification record (EKYC_AADHAAR, PAN, VIDEO_KYC etc.)
- `BankAccount.java` — Bank account value object; at most one primary account.

**Application Service:** `CustomerApplicationService.java`

**REST APIs Exposed:**
```
POST   /v1/customers                              Register customer
GET    /v1/customers/{id}                         Get customer
PUT    /v1/customers/{id}                         Update profile (ETag/optimistic lock)
POST   /v1/customers/{id}/kyc                     Submit KYC manually
POST   /v1/customers/{id}/kyc/{recordId}/complete Complete KYC
POST   /v1/customers/{id}/kyc/aadhaar-ekyc        Live Aadhaar eKYC via Partner Service
POST   /v1/customers/{id}/bank-accounts           Add bank account
POST   /v1/customers/{id}/bank-accounts/{id}/verify  Penny-drop verification
```

**External APIs Consumed:**
- `partner-integration-service:8085/v1/partner/pan/verify` — PAN verification (on registration)
- `partner-integration-service:8085/v1/partner/aadhaar/verify` — Aadhaar eKYC
- `partner-integration-service:8085/v1/partner/bank-account/verify` — Penny-drop

**Events Published** (via Outbox → `originex.customer.customers.events`):
- `originex.customer.CustomerRegistered`
- `originex.customer.KYCCompleted`

**Events Consumed:** None

**Database:** `originex_customer` — tables: `customers`, `kyc_records`, `bank_accounts`, `addresses`, `outbox_events`, `inbox_events`

**Security Note:** PAN is stored encrypted (`ENC:` prefix placeholder — AWS KMS integration TODO). Aadhaar stored only as `SHA-256(aadhaar + tenantSalt)` — never reversible.

---

### Service: `los-service` (Port 8082)

**Business Responsibility:** Loan application intake, state machine, credit check orchestration, BRE evaluation, offer generation.

**Key Domain Classes:**
- `LoanApplication.java` — Aggregate Root. 10-state FSM (see `ApplicationStatus.java`)
- `ApplicationStatus.java` — States: `DRAFT → SUBMITTED → IN_PROGRESS → APPROVED → OFFER_PENDING → OFFER_ACCEPTED → DISBURSEMENT_REQUESTED` (terminal)
- `LoanOffer.java` — Value Object. Contains sanctioned amount, rate, tenure, EMI, APR, expiry.

**Application Service:** `LoanApplicationService.java`

**REST APIs Exposed:**
```
POST   /v1/loan-applications                      Submit application
GET    /v1/loan-applications/{id}                 Get application
POST   /v1/loan-applications/{id}/documents        Add document
POST   /v1/loan-applications/{id}/credit-check     Trigger bureau pull + BRE evaluation (auto-decision)
POST   /v1/loan-applications/{id}/offer/accept     Accept offer → triggers disbursement
DELETE /v1/loan-applications/{id}                  Withdraw
```

**The Critical `credit-check` Flow:**
```
POST /credit-check
  1. Call partner-integration-service → bureau pull (CIBIL/Experian)
  2. Call bre-service → eligibility evaluation with 9 rules
  3. Auto-decision:
     - APPROVED  → app.approve() + generateOffer() + event: ApplicationApproved
     - REJECTED  → app.reject() + event: ApplicationRejected
     - REFER     → leave IN_PROGRESS for manual underwriter review
```

**External APIs Consumed:**
- `customer-service:8081/v1/customers/{id}` — Customer eligibility check
- `partner-integration-service:8085/v1/partner/credit-bureau/pull` — Bureau report
- `bre-service:8088/v1/bre/evaluate` — Eligibility + offer calculation

**Events Published** (via Outbox → `originex.los.applications.events`):
- `originex.los.ApplicationSubmitted`
- `originex.los.CreditCheckCompleted`
- `originex.los.ApplicationApproved`
- `originex.los.ApplicationRejected`
- `originex.los.DisbursementRequested` ← triggers LMS loan creation

**Events Consumed:** None

**Database:** `originex_los` — tables: `loan_applications`, `loan_offers`, `application_documents`, `outbox_events`

---

### Service: `bre-service` (Port 8088)

**Business Responsibility:** Configurable per-tenant business rules engine for loan eligibility, auto-decisioning, and offer calculation.

**Key Domain Classes:**
- `Rule.java` — Single evaluatable condition. Types: HARD (reject on fail), SOFT (refer on fail), ADVISORY (warn).
- `RuleSet.java` — Ordered collection of rules per product + employment type.
- `EvaluationFacts.java` — Immutable record of applicant facts (credit score, FOIR, age, income, loan amount). Derived facts computed automatically.
- `EvaluationResult.java` — Output: APPROVED / REJECTED / REFER_TO_UNDERWRITER + per-rule audit trail + offer parameters.

**Domain Services:**
- `RuleEvaluationEngine.java` — Sorts rules by priority, evaluates each, derives decision.
- `OfferCalculator.java` — EMI (`P×r×(1+r)^n / ((1+r)^n-1)`), rate by credit score band, APR with processing fee. All BigDecimal/HALF_EVEN.

**REST APIs Exposed:**
```
POST /v1/bre/evaluate    Evaluate loan application — returns decision + offer params
```

**Pre-seeded Rules (from Flyway V1):**
| Rule Code | Type | Condition |
|---|---|---|
| MIN_CREDIT_SCORE | HARD | credit_score >= 600 |
| NO_WRITE_OFF | HARD | has_write_off = false |
| MIN_AGE | HARD | applicant_age >= 21 |
| MAX_AGE_AT_MATURITY | HARD | age_at_maturity <= 65 |
| MIN_INCOME | HARD | monthly_income >= 15000 |
| MAX_FOIR | SOFT | foir <= 0.50 |
| MAX_ENQUIRIES | SOFT | enquiries_last_6_months <= 4 |
| NO_SETTLEMENT | SOFT | has_settlement = false |
| MAX_ACTIVE_LOANS | ADVISORY | active_loans_count <= 3 |

**Database:** `originex_bre` — tables: `bre_rule_sets`, `bre_rules`

**Note:** gRPC was removed from the POM due to a private Maven registry conflict (`net.devh:grpc-server-spring-boot-starter`). BRE exposes REST only. gRPC can be re-added later with a publicly available starter.

---

### Service: `lms-service` (Port 8083)

**Business Responsibility:** Post-disbursement loan lifecycle — EMI schedule, repayment allocation waterfall, NPA/DPD tracking.

**Key Domain Classes:**
- `Loan.java` — Aggregate Root. States: `CREATED → PENDING_DISBURSAL → ACTIVE → NPA → FORECLOSED/MATURED/WRITTEN_OFF/SETTLED`
- `LoanStatus.java` — FSM with `canTransitionTo()` enforcement
- `Installment.java` — Single EMI installment in repayment schedule
- `Disbursement.java` — Disbursement tracking per loan
- `LoanCharge.java` — Penalty charges, late fees

**Domain Service:** `ScheduleGenerator.java` — generates EMI schedule using reducing balance method

**Repayment Waterfall:** Charges → Penal Interest → Interest → Principal (hardcoded order)

**NPA Rules:** 90+ DPD = NPA classification (per RBI guidelines)

**Application Service:** `LoanApplicationServiceImpl.java`

**REST APIs Exposed:**
```
POST /v1/loans                    Create loan (called internally from Kafka consumer)
GET  /v1/loans/{id}               Get loan
POST /v1/loans/{id}/disburse      Initiate disbursement
POST /v1/loans/{id}/repayment     Manual repayment recording
```

**Events Consumed** (`originex.los.applications.events`):
- `originex.los.DisbursementRequested` → `DisbursementRequestedConsumer.java` → creates Loan + EMI schedule

**Events Consumed** (`originex.payments.orders.events`):
- `originex.payments.DisbursementCompleted` → `PaymentEventConsumer.java` → `confirmDisbursementByPayment()`
- `originex.payments.PaymentReceived` → `PaymentEventConsumer.java` → `allocateRepaymentFromPayment()`
- `originex.payments.PaymentFailed` → logged for manual intervention

**Events Published** (via Outbox → `originex.lms.loans.events`):
- `originex.lms.LoanDisbursed`
- `originex.lms.RepaymentAllocated`
- `originex.lms.DisbursementConfirmed`

**Database:** `originex_lms` — tables: `loans`, `installments`, `disbursements`, `loan_charges`, `outbox_events`, `inbox_events`

---

### Service: `ledger-service` (Port 8084)

**Business Responsibility:** Immutable double-entry accounting ledger. Financial source of truth.

**Key Domain Classes:**
- `JournalEntry.java` — Aggregate. Invariant: SUM(debits) = SUM(credits). Once posted, immutable. Corrections via reversal only.
- `Account.java` — GL account (ASSET, LIABILITY, INCOME, EXPENSE). Normal balance (DEBIT/CREDIT).

**Architecture:** Event-sourced — `ledger_events` (append-only partitioned table) is the source of truth. `account_snapshots` and `journal_entries` are read models.

**Events Consumed** (`originex.lms.loans.events`):
- `LoanDisbursed` → DR Loan Receivable, CR Bank/Pool Account
- `RepaymentAllocated` → DR Bank/Cash, CR Loan Receivable (principal) + CR Interest Income (interest)
- `InterestAccrued` → DR Interest Receivable, CR Interest Income

**Database:** `originex_ledger` — tables: `ledger_events` (partitioned by month), `account_snapshots`, `journal_entries`, `postings`, `outbox_events`, `inbox_events`

**Known Issue:** GL account IDs (`POOL_ACCOUNT_ID`, `INTEREST_INCOME_ID`) are hardcoded as UUID constants in `LmsEventConsumer.java`. Should be resolved from a configurable chart-of-accounts.

---

### Service: `partner-integration-service` (Port 8085)

**Business Responsibility:** Anti-Corruption Layer for all external vendor APIs. No other service calls vendors directly.

**Adapters (all Sandbox mode by default):**

| Adapter | Vendor | Config Key | Production Requirement |
|---|---|---|---|
| `CibilBureauAdapter` | CIBIL TransUnion | `originex.partner.mode=LIVE` | CIBIL member agreement |
| `ExperianBureauAdapter` | Experian India | Same | Experian license |
| `EquifaxBureauAdapter` | Equifax India | Same | Equifax license |
| `CrifBureauAdapter` | CRIF High Mark | Same | CRIF member agreement |
| `DigiLockerAadhaarAdapter` | DigiLocker (UIDAI) | Same | Register at partners.digitallocker.gov.in |
| `NsdlPanAdapter` | NSDL/Protean | Same | Protean PAN Verification user entity |
| `PennyDropBankVerificationAdapter` | Decentro/Cashfree | Same | Decentro/Cashfree API key |

**Mode Switching:** `originex.partner.mode=SANDBOX` (default) vs `LIVE`. All adapters check this at startup.

**REST APIs Exposed:**
```
POST /v1/partner/credit-bureau/pull    Pull credit bureau report
POST /v1/partner/aadhaar/verify        Aadhaar eKYC verification
POST /v1/partner/pan/verify            PAN verification
POST /v1/partner/bank-account/verify   Bank account penny-drop
```

**Audit Trail:** Every external API call is logged in `integration_requests` table with PII-masked request/response. Response caching: 24h bureau, 30d KYC.

**Database:** `originex_partner` — tables: `integration_requests`

---

### Service: `payment-service` (Port 8086)

**Business Responsibility:** Orchestrates actual fund movement — disbursements to borrowers and EMI collection.

**Key Domain Classes:**
- `PaymentOrder.java` — Aggregate Root. States: `CREATED → INITIATED → PROCESSING → COMPLETED`. Retries: max 3 (configurable). Auto-selects rail by amount: ≥₹2L → RTGS, ≤₹5L → IMPS, else NEFT.
- `NachMandate.java` — NACH mandate lifecycle for recurring EMI auto-debit.

**Payment Rail Adapters** (all Sandbox):

| Adapter | Rail | Production Requirement |
|---|---|---|
| `NeftRailAdapter` | NEFT | Banking partner API (ICICI/HDFC corporate) or aggregator (Cashfree/Razorpay X) |
| `RtgsRailAdapter` | RTGS | Same |
| `ImpsRailAdapter` | IMPS | Same |
| `NachRailAdapter` | NACH | Sponsor bank NACH API or e-NACH provider (Digio/Razorpay/Cashfree) |

**Retry Scheduler:** `@Scheduled(fixedDelayString="${originex.payment.retry-interval-ms:300000}")` — polls RETRY_PENDING orders every 5 minutes.

**REST APIs Exposed:**
```
POST /v1/payments/disbursements           Initiate disbursement
GET  /v1/payments/{id}                    Get payment order
POST /v1/payments/inbound                 Record inbound payment (UPI/manual)
POST /v1/payments/callbacks               Webhook from payment rail
POST /v1/payments/mandates                Register NACH mandate
POST /v1/payments/mandates/{id}/collect   Trigger NACH collection
```

**Events Consumed** (`originex.lms.loans.events`):
- `originex.lms.LoanDisbursed` → `LmsPaymentEventConsumer.java` → initiates NEFT/RTGS/IMPS transfer

**Events Published** (via Outbox → `originex.payments.orders.events`):
- `originex.payments.DisbursementInitiated`
- `originex.payments.DisbursementCompleted`
- `originex.payments.PaymentReceived`
- `originex.payments.PaymentFailed`
- `originex.payments.NachMandateRegistered`
- `originex.payments.CollectionInitiated`

**Database:** `originex_payment` — tables: `payment_orders`, `nach_mandates`

---

### Service: `notification-service` (Port 8087)

**Business Responsibility:** RBI-mandated multi-channel borrower communication at every loan lifecycle event.

**Key Domain Classes:**
- `NotificationRequest.java` — Aggregate Root. States: PENDING → DELIVERED/PARTIALLY_DELIVERED/FAILED. Max 3 retries.
- `ChannelDispatch.java` — Per-channel delivery tracking with provider reference.
- `NotificationTemplate.java` — Per-tenant, per-trigger, per-channel, per-language. `{{variable}}` substitution.
- `NotificationTrigger.java` — 33 triggers covering every RBI-mandated event.

**Channel Adapters** (all Sandbox):

| Adapter | Channel | Vendor | Production Requirement |
|---|---|---|---|
| `Msg91SmsAdapter` | SMS | MSG91 | DLT-registered templates (TRAI mandate) + MSG91 API key |
| `SesEmailAdapter` | Email | AWS SES | Domain verification + exit SES sandbox |
| `GupshupWhatsAppAdapter` | WhatsApp | Gupshup | WABA approved by Meta + HSM templates |
| `FcmPushAdapter` | Push | Firebase FCM | Firebase project + service account key |

**Universal Kafka Consumer:** `DomainEventNotificationConsumer.java` — listens to ALL 4 domain topics simultaneously. Idempotent by `source_event_id`.

**Pre-seeded Templates (Flyway V1):** 11 English templates for RBI-mandated events (APPLICATION_SUBMITTED, APPLICATION_APPROVED, APPLICATION_REJECTED, LOAN_DISBURSED, REPAYMENT_RECEIVED, PAYMENT_FAILED, KYC_COMPLETED, NACH_MANDATE_REGISTERED, DISBURSEMENT_COMPLETED).

**Retry Scheduler:** Every 10 minutes, retries FAILED notifications independently per channel.

**Default Templates:** Use sentinel `tenant_id = 00000000-0000-0000-0000-000000000001`. Tenant-specific overrides inserted per-tenant.

**Database:** `originex_notification` — tables: `notification_requests`, `channel_dispatches`, `notification_templates`

---

### Service: `template-service` (Port — not assigned)

**Purpose:** Canonical reference implementation and scaffold for creating new services. Copy this service, replace `template`/`Sample` with your domain, and follow the existing patterns. Contains `HexagonalArchitectureTest.java` — copy this test to every new service.

---

## 5. Business Domain Understanding

### Customer Lifecycle
```
Customer registers (POST /v1/customers)
  → PAN verified live (NSDL via partner-service) during registration
  → CustomerRegistered event published
  → Customer initiates Aadhaar eKYC (POST /kyc/aadhaar-ekyc)
      → DigiLocker/UIDAI verification
      → Aadhaar tokenized (irreversible SHA-256 hash)
      → KYC status → VERIFIED
      → KYCCompleted event published
  → Customer adds bank account (POST /bank-accounts)
      → Penny-drop verification (POST /bank-accounts/{id}/verify)
```
**Classes:** `Customer` → `KycRecord` → `BankAccount`

### Loan Application Lifecycle (LOS)
```
Submit (POST /v1/loan-applications)  [status: SUBMITTED]
  → Credit check (POST /{id}/credit-check)  [status: IN_PROGRESS]
      → Bureau pull (partner-service)
      → BRE evaluation (bre-service)
      → Auto-decision:
          APPROVED  [status: APPROVED → OFFER_PENDING]  → offer generated
          REJECTED  [status: REJECTED] (terminal)
          REFER     [status: IN_PROGRESS] → awaits manual underwriter decision
  → Customer accepts offer (POST /{id}/offer/accept)  [status: OFFER_ACCEPTED]
  → System triggers disbursement [status: DISBURSEMENT_REQUESTED] (terminal for LOS)
      → DisbursementRequested event → LMS picks up
```
**Classes:** `LoanApplication`, `ApplicationStatus`, `LoanOffer`

### BRE Evaluation Flow
```
LOS calls POST /v1/bre/evaluate
  → Load matching RuleSet (product + employment type, fallback to DEFAULT)
  → Build EvaluationFacts (computed: FOIR, loan-to-income, age at maturity)
  → Evaluate rules in priority order:
      HARD rule fails → REJECTED
      SOFT rule fails → REFER_TO_UNDERWRITER
      All pass        → APPROVED
  → Calculate offer (rate by credit score, EMI by reducing balance, APR with processing fee)
  → Return EvaluationResult with per-rule audit trail
```

### Loan Disbursement Flow
```
LOS publishes DisbursementRequested
  → LMS: DisbursementRequestedConsumer → createLoan() + ScheduleGenerator → loan + installments created
  → LMS publishes LoanDisbursed
  → Payment: LmsPaymentEventConsumer → initiateDisbursement() → selects NEFT/RTGS/IMPS
  → Payment rail (sandbox) → completes immediately; live = webhook callback
  → Payment publishes DisbursementCompleted
  → LMS: PaymentEventConsumer → confirmDisbursementByPayment() → loan status: ACTIVE
  → Ledger: LmsEventConsumer → DR Loan Receivable, CR Bank/Pool Account
```

### Repayment Lifecycle
```
Payment received (NACH/UPI/manual) → payment-service records PaymentReceived
  → Payment publishes PaymentReceived
  → LMS: PaymentEventConsumer → allocateRepaymentFromPayment()
      Waterfall: Charges → Penal Interest → Interest → Principal
  → LMS publishes RepaymentAllocated
  → Ledger: auto-posts DR Cash, CR Loan Receivable + Interest Income
  → Notification: DomainEventNotificationConsumer → SMS/Email receipt to borrower
```

### NPA / DPD
- DPD tracked on `Loan` aggregate: `loan.updateDpd()`
- 90+ DPD → `assetClassification = NPA` (RBI Sub-Standard)
- Domain method exists; **Flink streaming job to automate this is NOT YET BUILT**

---

## 6. Data Architecture

### Database-per-Service (Strict Isolation)

| Service | Database Name | Key Tables |
|---|---|---|
| `customer-service` | `originex_customer` | `customers`, `kyc_records`, `bank_accounts`, `addresses` |
| `los-service` | `originex_los` | `loan_applications`, `loan_offers`, `application_documents` |
| `lms-service` | `originex_lms` | `loans`, `installments`, `disbursements`, `loan_charges` |
| `ledger-service` | `originex_ledger` | `ledger_events` (partitioned), `account_snapshots`, `journal_entries`, `postings` |
| `partner-service` | `originex_partner` | `integration_requests` |
| `payment-service` | `originex_payment` | `payment_orders`, `nach_mandates` |
| `notification-service` | `originex_notification` | `notification_requests`, `channel_dispatches`, `notification_templates` |
| `bre-service` | `originex_bre` | `bre_rule_sets`, `bre_rules` |

### Outbox / Inbox Tables
Every service that uses Kafka has `outbox_events` and `inbox_events` tables. These are auto-created by the `OutboxAutoConfiguration` from `originex-spring-boot-starter`.

### Row-Level Security (RLS)
**Every table in every service has RLS enabled.** Policy pattern:
```sql
CREATE POLICY tenant_isolation ON table_name
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
```
Before any query, the application must set `app.tenant_id` via `SET LOCAL app.tenant_id = 'uuid'`.

**⚠️ IMPORTANT:** The `TenantContextHolder` is set but the `app.tenant_id` PostgreSQL setting is **NOT yet wired** in Hibernate interceptors. RLS will block queries in production until this is implemented.

### Migrations
- Tool: **Flyway 10.22.0**
- Location: `src/main/resources/db/migration/`
- Naming: `V{n}__{description}.sql`
- Each service currently has only `V1__create_*.sql`

### Key Monetary Column Types
All monetary values use `NUMERIC(19,4)` in PostgreSQL — matching `Money.java`'s scale=4.

---

## 7. API Architecture

### No API Gateway Yet
**⚠️ There is no API Gateway deployed.** Services are called directly. The architecture doc specifies Kong/Istio Gateway, but it has not been implemented. All requests go directly to service ports.

### Tenant Header (Mandatory on All Services)
```
X-Tenant-Id: {tenant-uuid}
```
Enforced by `TenantResolutionFilter`. Returns HTTP 400 if missing (when `enforce=true`). Notification service has `enforce=false` since it processes Kafka events.

### REST API Conventions

**URL Pattern:** `/v{version}/{resource}` e.g., `/v1/customers`, `/v1/loan-applications`

**Request/Response:** `application/json`. All DTOs defined as Java `record` types inside the controller class.

**Optimistic Locking:** `If-Match: {version}` header on PUT requests (customer profile update). Returns HTTP 409 on conflict.

**Error Response Format (RFC 7807):**
```json
{
  "type": "https://api.originex.io/problems/...",
  "title": "Bad Request",
  "status": 400,
  "detail": "Specific error message"
}
```

**Validation:** Jakarta Bean Validation (`@Valid`, `@NotBlank`, `@Positive`) on all request DTOs.

### Inter-Service Communication

| Caller | Target | Method | Purpose |
|---|---|---|---|
| `los-service` | `customer-service` | REST GET | Customer eligibility check |
| `los-service` | `partner-service` | REST POST | Bureau pull |
| `los-service` | `bre-service` | REST POST | Eligibility evaluation |
| `customer-service` | `partner-service` | REST POST | PAN/Aadhaar/bank verify |
| `payment-service` | ← `lms-service` | Kafka | LoanDisbursed event |
| `lms-service` | ← `los-service` | Kafka | DisbursementRequested event |
| `ledger-service` | ← `lms-service` | Kafka | All loan lifecycle events |
| `notification-service` | ← All services | Kafka | All domain events |

### Service URLs (Local Development)
```
customer-service            http://localhost:8081
los-service                 http://localhost:8082
lms-service                 http://localhost:8083
ledger-service              http://localhost:8084
partner-integration-service http://localhost:8085
payment-service             http://localhost:8086
notification-service        http://localhost:8087
bre-service                 http://localhost:8088
```

### Complete Loan Origination Request Flow Example
```
1. POST http://localhost:8081/v1/customers          → Create customer
   Headers: X-Tenant-Id: {tenant-uuid}

2. POST http://localhost:8081/v1/customers/{id}/kyc/aadhaar-ekyc
   Body: { aadhaarNumberOrVid, consentArtifactId, otpReference }

3. POST http://localhost:8082/v1/loan-applications  → Submit application
   Body: { customerId, productCode, amount, currency, tenureMonths, ... }

4. POST http://localhost:8082/v1/loan-applications/{id}/credit-check
   Body: { consentArtifactId }
   → Internally: bureau pull → BRE eval → auto-decision → offer generated

5. POST http://localhost:8082/v1/loan-applications/{id}/offer/accept
   → DisbursementRequested event → LMS → Payment → Ledger auto-posts
```

---

## 8. Security Architecture

### Current State: ⚠️ NO AUTHENTICATION IMPLEMENTED

There is no IAM service, no JWT validation, no OAuth2, no API Gateway. Any caller with a valid `X-Tenant-Id` header can call any endpoint. This is a **P0 production blocker**.

### What Is Implemented

| Component | Status | Location |
|---|---|---|
| Tenant isolation via header | ✅ | `TenantResolutionFilter.java` |
| Row-Level Security (PostgreSQL) | ✅ Schema only | All Flyway migrations |
| PAN encryption (stub) | 🟡 Placeholder | `CustomerApplicationService.java` — `encryptPan()` returns `"ENC:" + pan` |
| Aadhaar tokenization | ✅ | `CustomerApplicationService.java` — SHA-256 + tenant salt, irreversible |
| PII masking in logs | ✅ | All adapters mask phone/PAN/email before logging |
| GlobalExceptionHandler | ✅ | No stack traces in API responses |

### Planned (Architecture Docs Only)
- Keycloak for OAuth2 + OIDC + JWT
- JWT validation filter in `originex-spring-boot-starter`
- RBAC roles: `ADMIN`, `UNDERWRITER`, `OPERATIONS`, `BORROWER`, `PARTNER`
- mTLS between services via Istio
- AWS KMS for PAN/account number encryption
- HashiCorp Vault for secrets

### To Activate Live Vendor APIs
Set in each service's `application.yml` or Vault secret:
```yaml
originex.partner.mode: LIVE
```
Then add vendor credentials to HashiCorp Vault (path documented in each adapter's Javadoc).

---

## 9. Event-Driven Architecture

### Kafka Topology (from `infra/kafka/topics.yaml`)

| Topic | Partitions | Retention | Producer | Consumers |
|---|---|---|---|---|
| `originex.los.applications.events` | 32 | 30 days | los-service (Outbox) | lms-service, notification-service |
| `originex.los.applications.commands` | 32 | 7 days | — | — |
| `originex.lms.loans.events` | 64 | 30 days | lms-service (Outbox) | ledger-service, payment-service, notification-service |
| `originex.ledger.journal-entries.events` | — | — | ledger-service | (future: reporting) |
| `originex.customer.customers.events` | — | — | customer-service | notification-service |
| `originex.payments.orders.events` | — | — | payment-service | lms-service, notification-service |

### Transactional Outbox Pattern
**Every service uses the Outbox pattern.** Never call `KafkaTemplate` directly.

```java
// Correct — within @Transactional method:
outboxPublisher.publish("LoanApplication", applicationId,
    "originex.los.ApplicationSubmitted", tenantId, payloadBytes);

// The OutboxPoller (every 500ms) reads PENDING events and publishes to Kafka
```

### Inbox Idempotency Pattern
**Every Kafka consumer checks inbox before processing.** Pattern from `DisbursementRequestedConsumer.java`:
```java
UUID eventUuid = UUID.fromString(eventId);
if (inboxRepository.existsById(eventUuid)) {
    log.debug("Duplicate event, skipping: eventId={}", eventId);
    return;
}
// ... process ...
inboxRepository.save(InboxEventJpaEntity.of(eventUuid, eventType));
```

### Event Payload Format
Currently **JSON bytes** (not Protobuf). Protobuf schemas are defined in `proto/` but the end-to-end Protobuf wiring is not yet complete. Consumers parse with `ObjectMapper.readTree()`.

### Kafka Headers (set by OutboxPoller)
```
event_id        UUID (idempotency key for consumers)
event_type      e.g., "originex.los.DisbursementRequested"
aggregate_type  e.g., "LoanApplication"
aggregate_id    UUID of the aggregate
tenant_id       UUID of the tenant
```

### Topic Routing (OutboxPoller)
```java
// OutboxPoller.resolveTopicFromEventType()
"originex.los.*"       → "originex.los.applications.events"
"originex.lms.*"       → "originex.lms.loans.events"
"originex.customer.*"  → "originex.customer.customers.events"
"originex.ledger.*"    → "originex.ledger.journal-entries.events"
"originex.payments.*"  → "originex.payments.orders.events"
```

---

## 10. Current Implementation Status

### ✅ Completed

**Infrastructure:**
- 13-module Maven reactor with centralized version management
- `libs/common`: `Money`, `TenantContext`, event interfaces
- `libs/spring-boot-starter`: Outbox, Inbox, TenantFilter, GlobalExceptionHandler (auto-configured)
- `proto/`: 6 `.proto` files generating 82 Java classes (LOS, LMS, Ledger, Customer, common, event envelope)
- Docker Compose local dev stack (PostgreSQL 16, Kafka KRaft, Redis 7, Schema Registry, Kafka UI)
- Helm chart (generic, `infra/helm/originex-service/`)
- Terraform modules (VPC, EKS K8s 1.30, RDS, Redis)
- GitHub Actions CI pipeline
- Kafka topic CRDs (Strimzi, 30+ topics defined)

**Business Services:**
- `customer-service`: Full CRUD, KYC lifecycle (EKYC_AADHAAR, PAN, manual), bank accounts, penny-drop, PAN verification on registration
- `los-service`: 10-state application FSM, bureau pull, BRE integration, auto-decisioning, offer engine, disbursement trigger
- `bre-service`: Configurable rule engine (9 pre-seeded rules), offer calculator (reducing balance EMI, APR), per-tenant rule sets
- `lms-service`: EMI schedule generation, repayment waterfall, NPA/DPD tracking, NACH collection trigger
- `ledger-service`: Event-sourced double-entry ledger, auto-posting from LMS events, partitioned event store
- `partner-integration-service`: 4 bureau adapters, Aadhaar (DigiLocker), PAN (NSDL), bank (penny-drop) — all sandbox with live hooks
- `payment-service`: NEFT/RTGS/IMPS/NACH adapters, auto-rail selection, retry scheduler, LMS event consumer
- `notification-service`: 4 channel adapters (SMS/Email/WhatsApp/Push), 33 triggers, 11 pre-seeded templates, retry scheduler

**Testing (Domain Unit Tests):**
- `LoanApplicationTest.java` (LOS)
- `EndToEndDomainFlowTest.java` (LOS)
- `LoanTest.java` (LMS)
- `ScheduleGeneratorTest.java` (LMS)
- `JournalEntryTest.java` (Ledger)
- `MoneyTest.java` (common lib)
- `PaymentOrderTest.java` (Payment)
- `NotificationRequestTest.java` (Notification)
- `BREDomainTest.java` (BRE — 10 test cases)
- `HexagonalArchitectureTest.java` (template-service — ArchUnit)

### 🟡 Partially Implemented / Stubs

| Item | Location | What's Missing |
|---|---|---|
| PAN encryption | `CustomerApplicationService.encryptPan()` | AWS KMS integration (`// TODO Phase 4`) |
| RLS enforcement | Flyway migrations (policies exist) | Hibernate interceptor to set `app.tenant_id` session variable |
| Protobuf event serialization | `proto/` has schemas, `OutboxPublisher` stores `byte[]` | Actual Protobuf serialization in application services (currently JSON) |
| Notification customer phone/email | `DomainEventNotificationConsumer.java` | Fetch from customer profile; currently reads from event payload only |
| GL account resolution | `LmsEventConsumer.java` | Hardcoded UUID constants; needs chart-of-accounts service |

### ❌ Not Yet Built

| Feature | Priority | Notes |
|---|---|---|
| IAM Service (Keycloak/Spring Auth Server) | 🔴 P0 | Zero authentication today |
| API Gateway (Kong/Istio) | 🔴 P0 | No rate limiting, WAF, or JWT validation at edge |
| Collection Service | 🟡 P1 | Delinquency cases, dunning workflows, write-offs |
| Audit Service | 🟡 P1 | Immutable audit trail to OpenSearch/S3 (RBI 8-year requirement) |
| Apache Flink Jobs | 🟡 P2 | Interest accrual, DPD aging, NPA classification streaming pipeline |
| BRE gRPC endpoint | 🟡 P2 | Removed due to private registry issue; REST works as replacement |
| Reporting Service | 🟢 P3 | MIS, regulatory reports, portfolio analytics |
| Tenant Service | 🟢 P3 | Tenant onboarding, product config, feature flags |
| Config Service | 🟢 P3 | Centralized feature flags and configuration |
| ArgoCD GitOps | 🟢 P3 | Configured in arch docs only |
| Istio Service Mesh | 🟢 P3 | Helm annotations present, not deployed |
| OpenTelemetry tracing | 🟢 P3 | BOM imported, auto-instrumentation not wired |
| Chaos Engineering | 🟢 P3 | Architecture planned |

---

## 11. Important Code Locations Quick Reference

| Feature | File(s) | Notes |
|---|---|---|
| **Money value object** | `libs/common/.../money/Money.java` | Always use this for monetary values. Never `double`/`float`. |
| **Tenant context** | `libs/common/.../tenant/TenantContextHolder.java` | ThreadLocal. Set by `TenantResolutionFilter`. |
| **Outbox publishing** | `libs/spring-boot-starter/.../outbox/OutboxPublisher.java` | Must be called within `@Transactional` |
| **Outbox Kafka dispatch** | `libs/spring-boot-starter/.../outbox/OutboxPoller.java` | Topic routing logic here |
| **Tenant HTTP filter** | `libs/spring-boot-starter/.../tenant/TenantResolutionFilter.java` | `X-Tenant-Id` header → `TenantContextHolder` |
| **Global exceptions** | `libs/spring-boot-starter/.../exception/GlobalExceptionHandler.java` | Do not add `@ExceptionHandler` in individual controllers |
| **LOS state machine** | `services/los-service/.../domain/model/ApplicationStatus.java` | `canTransitionTo()` enforces valid transitions |
| **LMS state machine** | `services/lms-service/.../domain/model/LoanStatus.java` | Same pattern |
| **LOS application service** | `services/los-service/.../service/LoanApplicationService.java` | `initiateCreditCheck()` is the most complex method — bureau + BRE + auto-decision |
| **BRE rule evaluator** | `services/bre-service/.../engine/RuleEvaluationEngine.java` | Core evaluation logic |
| **BRE offer calculator** | `services/bre-service/.../engine/OfferCalculator.java` | EMI formula, rate bands |
| **EMI schedule generator** | `services/lms-service/.../domain/service/ScheduleGenerator.java` | Reducing balance |
| **Repayment waterfall** | `services/lms-service/.../domain/model/Loan.java` — `allocateRepayment()` | Charges → Interest → Principal |
| **Double-entry invariant** | `services/ledger-service/.../domain/model/JournalEntry.java` | Sum(debits)==Sum(credits) enforced in `create()` |
| **Auto ledger posting** | `services/ledger-service/.../adapter/in/kafka/LmsEventConsumer.java` | LoanDisbursed/RepaymentAllocated → journal entries |
| **Notification triggers** | `services/notification-service/.../domain/model/NotificationTrigger.java` | 33 RBI-mandated + business triggers |
| **Event-to-trigger mapping** | `services/notification-service/.../domain/service/EventToNotificationMapper.java` | Kafka event type → trigger |
| **Payment rail selection** | `services/payment-service/.../service/PaymentApplicationService.java` — `selectRail()` | ≥₹2L→RTGS, ≤₹5L→IMPS, else NEFT |
| **Bureau sandbox adapters** | `services/partner-integration-service/.../adapter/out/bureau/` | Each has `TODO Phase 4` comments for LIVE mode |
| **Inbox idempotency** | `services/lms-service/.../adapter/in/kafka/DisbursementRequestedConsumer.java` | Reference implementation for all Kafka consumers |
| **Hexagonal arch test** | `services/template-service/.../HexagonalArchitectureTest.java` | Copy to every new service |
| **New service template** | `services/template-service/` | Copy entire service to create a new microservice |
| **Kafka topic definitions** | `infra/kafka/topics.yaml` | Strimzi CRDs |
| **Local dev stack** | `dev/docker-compose.yml` | `docker compose -f dev/docker-compose.yml up -d` |
| **All Flyway migrations** | Each service: `src/main/resources/db/migration/V1__create_*.sql` | RLS policies included |

---

## 12. Coding Standards

### Package Naming
```
com.originex.{service-name}.{layer}.{sub-layer}
Examples:
  com.originex.los.domain.model.LoanApplication
  com.originex.los.application.port.in.LoanApplicationUseCase
  com.originex.los.application.service.LoanApplicationService
  com.originex.los.adapter.in.rest.LoanApplicationController
  com.originex.los.adapter.out.persistence.LoanApplicationJpaEntity
  com.originex.los.adapter.out.rest.CustomerServiceAdapter
```

### Domain Model Patterns
- **Aggregate Roots:** Plain Java. No Spring annotations. No JPA annotations. Factory methods (static `create()` or `register()`). State machine via `transitionTo()` with `canTransitionTo()` guard.
- **Value Objects:** `record` or final class with no setters. `Money.java` is the canonical example.
- **JPA Entities:** Separate class in `adapter.out.persistence`. Has `fromDomain()` + `toDomain()` methods. No domain logic.
- **No Lombok.** All getters/setters written explicitly.
- **IDs:** Always `UUID`. Never sequential integers for business entities.

### DTO Patterns
- All request/response DTOs are Java `record` types defined **inside** the controller class (private nested records).
- Use `@NotBlank`, `@NotNull`, `@Positive` for validation on records.
- Response DTOs have a static `from(DomainObject)` factory method.

### Financial Computation Rules (Non-Negotiable)
- **ALWAYS use `Money.java` for monetary values.** Never `BigDecimal` directly in domain/application code without wrapping in `Money`.
- **NEVER use `double` or `float` for money.** Build will break code review if found.
- **Rounding:** `RoundingMode.HALF_EVEN` (Banker's rounding) everywhere.
- **Scale:** 4 decimal places in storage (`NUMERIC(19,4)`), 4 in `Money.DEFAULT_SCALE`.
- **BigDecimal arithmetic** in domain services uses `MathContext.DECIMAL128`.

### Exception Handling
- Domain exceptions in `domain/exception/` package. Extend `RuntimeException`.
- Application services throw domain exceptions or `IllegalArgumentException` / `IllegalStateException`.
- **Do NOT add `@ExceptionHandler` in controllers.** `GlobalExceptionHandler` in the starter handles all exceptions automatically.
- Standard mapping: `IllegalArgumentException` → 400, `IllegalStateException` → 422 (or 409 for version conflicts), uncaught → 500.

### Logging Approach
```java
// Use SLF4J Logger (not Spring's Logger)
private static final Logger log = LoggerFactory.getLogger(MyClass.class);

// Always include business IDs in log statements
log.info("Loan disbursed: loanId={}, amount={}", loanId, amount);

// Never log PII (phone, email, PAN) without masking
log.info("Customer registered: phone={}", maskPhone(command.phone()));
```
Log pattern includes `[%X{tenantId:-}]` MDC context — set automatically by `TenantResolutionFilter` and Kafka consumers.

### Validation Approach
- Input validation: Jakarta Bean Validation on `record` DTOs with `@Valid` in controller method signatures.
- Business invariants: enforced inside domain aggregate methods (throw `IllegalArgumentException` or `IllegalStateException`).
- Never validate business rules in controllers or application services — push to domain.

### API Response Patterns
- HTTP 201 Created for `POST` that creates a resource. Include `Location` header.
- HTTP 200 OK for commands that return the updated resource.
- HTTP 204 No Content for delete/withdraw.
- HTTP 409 Conflict for optimistic lock violations (`If-Match` mismatch).
- HTTP 422 Unprocessable Entity for business rule violations.
- HTTP 400 Bad Request for input validation errors (with field errors list).

### Transaction Handling
- `@Transactional` on application service methods.
- `@Transactional(readOnly = true)` on query methods.
- `OutboxPublisher.publish()` uses `Propagation.MANDATORY` — must be called within an existing transaction.
- Kafka consumers use `@Transactional` on `@KafkaListener` methods — outbox write + inbox write in same transaction.

### Resilience Patterns (Outbound HTTP Calls)
All outbound REST calls use `@CircuitBreaker` + `@Retry` from Resilience4j:
```java
@Override
@CircuitBreaker(name = "serviceNameHere", fallbackMethod = "fallbackMethod")
@Retry(name = "serviceNameHere")
public Result callExternalService(Request request) { ... }

private Result fallbackMethod(Request request, Throwable t) {
    log.warn("Service unavailable: {}", t.getMessage());
    return Result.defaultFallback();
}
```
Circuit breaker names must match entries in `application.yml` under `resilience4j.circuitbreaker.instances`.

---

## 13. How Claude Code Should Work In This Repository

### Before Making Any Change

1. **Read the domain model first.** Every service's `domain/model/` package defines the business rules. Understand the aggregate invariants before touching application or adapter code.
2. **Check the state machine.** `ApplicationStatus.java` and `LoanStatus.java` define all valid state transitions. Never bypass `canTransitionTo()`.
3. **Never add new frameworks** without explicitly evaluating impact on all 13 modules. The POM manages all versions centrally — add new dependencies to root `pom.xml` `dependencyManagement` first.
4. **Do not use `float` or `double` for money.** Use `Money.java`. This is non-negotiable.
5. **Check for an existing utility first.** `Money`, `TenantContextHolder`, `OutboxPublisher`, `GlobalExceptionHandler` — use these, don't re-implement.

### Adding a New Service
1. Copy `services/template-service/` entirely.
2. Replace all `template`/`Template`/`TEMPLATE` with your service name.
3. Add the new module to root `pom.xml` `<modules>` section.
4. Assign a port (next available: 8089+).
5. Create a new database in `dev/init-scripts/init-databases.sql`.
6. Add Kafka consumer group in `application.yml`.
7. Copy `HexagonalArchitectureTest.java` to the new service.

### Adding a New Kafka Event
1. Define the event type string (format: `originex.{domain}.{EventName}`).
2. Add the `proto` message to the relevant `.proto` file in `proto/src/main/proto/`.
3. Add routing in `OutboxPoller.resolveTopicFromEventType()` if it's a new domain prefix.
4. Add the trigger mapping in `EventToNotificationMapper.mapTrigger()` if a notification should be sent.
5. Create a Kafka consumer in the receiving service with inbox idempotency.

### Adding a New BRE Rule
1. Insert into `bre_rules` table: either via new Flyway migration or REST API.
2. Rules are evaluated in `priority` order (lower = first).
3. HARD rules reject; SOFT rules refer; ADVISORY rules warn.
4. Supported `fact_key` values are in `RuleEvaluationEngine.extractFact()`.
5. Adding a new `fact_key` requires adding a case to `extractFact()`.

### Modifying an Existing Service
1. Run `mvn compile` after changes to catch issues early.
2. Run `mvn test -pl services/{service-name}` to run unit tests for that service.
3. If changing a domain model, check all JPA entity `fromDomain()`/`toDomain()` mappers.
4. If changing an API, update the consuming service's adapter (e.g., changing LOS API means updating LMS's `DisbursementRequestedConsumer`).
5. If adding a new state to a state machine enum, update `canTransitionTo()` and add tests.
6. Never modify `outbox_events` or `inbox_events` tables — managed by the starter library.

### Running the Full Build
```bash
# Full compile
mvn compile

# Unit tests only
mvn test -DskipITs=true

# Full build with integration tests
mvn verify -Pintegration-test

# Single service
mvn compile -pl services/los-service

# Install shared libs (needed after changing libs/common or libs/spring-boot-starter)
mvn install -pl proto,libs/common,libs/spring-boot-starter -am -DskipTests
```

### Starting Local Development Environment
```bash
# Start all infrastructure
docker compose -f dev/docker-compose.yml up -d

# Verify Kafka is up
open http://localhost:8080  # Kafka UI

# Verify Schema Registry
curl http://localhost:8081/subjects

# Connect to PostgreSQL
psql -h localhost -U originex -d originex_dev
```

---

## 14. Known Issues / Technical Debt

### Critical (Production Blockers)
1. **No Authentication:** Zero IAM. Any caller can access any endpoint. `iam-service` not built.
2. **RLS Not Enforced at Runtime:** `CREATE POLICY` exists in every Flyway migration, but there is no Hibernate interceptor setting `SET LOCAL app.tenant_id`. Queries will fail in production with `current_setting('app.tenant_id')` errors.
3. **PAN Encryption is a Stub:** `CustomerApplicationService.encryptPan()` returns `"ENC:" + pan.substring(0,5) + "XXXXX"` — not real encryption. AWS KMS integration needed.

### High Priority
4. **Hardcoded GL Account IDs:** In `LmsEventConsumer.java`:
   ```java
   private static final UUID POOL_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
   private static final UUID INTEREST_INCOME_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
   ```
   These accounts don't exist in the schema — posting will fail until chart-of-accounts is seeded.
5. **Outbox Topic Routing Missing `notifications`:** `OutboxPoller.resolveTopicFromEventType()` has no case for `originex.notification.*` events.
6. **Notification Consumer Missing Customer Data:** `DomainEventNotificationConsumer.java` reads `phone`/`email` from the Kafka event payload. Most events don't include this. Needs a customer profile lookup.
7. **Event Payload is JSON not Protobuf:** All `OutboxPublisher.publish()` calls pass `String.format("{...}").getBytes()` JSON. The Protobuf schemas in `proto/` are not yet used for actual serialization.

### Medium Priority
8. **No Apache Flink Jobs:** Interest accrual, DPD aging, NPA classification are methods on `Loan.java` but no scheduler or Flink job calls them at scale.
9. **Ledger Partitioning:** Only 2 monthly partitions created (`y2026m07`, `y2026m08`). Need `pg_partman` or a script to create future partitions.
10. **BRE gRPC Removed:** `net.devh:grpc-server-spring-boot-starter` removed due to private Maven registry conflict. If gRPC is needed, use `io.grpc:grpc-netty-shaded` directly (available via Maven Central).
11. **No Dead Letter Queue handling:** Kafka consumers have no DLQ configuration for poison messages.

### Low Priority / Technical Debt
12. **Missing pagination on GET endpoints:** `/v1/customers`, `/v1/loan-applications` return all records without pagination.
13. **No API versioning strategy on media type:** Uses URL versioning (`/v1/`) only. No content negotiation.
14. **No integration tests written yet:** `*IntegrationTest.java` files don't exist yet. Only domain unit tests.
15. **Testcontainers not used:** Despite dependency being declared, no `@Testcontainers` integration tests exist.
16. **`template-service` port not assigned** in `application.yml`.
17. **BRE `EMPLOYMENT_TYPE` IN rule update via SQL UPDATE:** The allowed values for the `EMPLOYMENT_TYPE` rule in Flyway V1 use a `UPDATE` statement after the `INSERT`, which is fragile. Should use upsert.

---

## 15. First Steps for a New AI Assistant

### Recommended Reading Order

1. **Start with the build:**
   ```bash
   cd /Users/RABHOSA/Desktop/Originex
   mvn compile
   # Should show: 13 modules, ~276 files, BUILD SUCCESS
   ```

2. **Understand shared infrastructure:**
   - `libs/common/src/main/java/com/originex/common/money/Money.java` — the money value object used everywhere
   - `libs/spring-boot-starter/src/main/java/com/originex/starter/outbox/OutboxPublisher.java` — how events are published
   - `libs/spring-boot-starter/src/main/java/com/originex/starter/outbox/OutboxPoller.java` — how events reach Kafka
   - `libs/spring-boot-starter/src/main/java/com/originex/starter/tenant/TenantResolutionFilter.java` — tenant isolation

3. **Understand the core loan flow:**
   - `services/los-service/.../domain/model/LoanApplication.java` — application aggregate
   - `services/los-service/.../domain/model/ApplicationStatus.java` — state machine
   - `services/los-service/.../application/service/LoanApplicationService.java` — key method: `initiateCreditCheck()`

4. **Understand the LMS:**
   - `services/lms-service/.../domain/model/Loan.java` — loan aggregate
   - `services/lms-service/.../domain/model/LoanStatus.java` — loan state machine
   - `services/lms-service/.../adapter/in/kafka/DisbursementRequestedConsumer.java` — canonical Kafka consumer implementation

5. **Understand the BRE:**
   - `services/bre-service/.../domain/engine/RuleEvaluationEngine.java`
   - `services/bre-service/.../domain/engine/OfferCalculator.java`
   - `services/bre-service/src/test/java/com/originex/bre/domain/BREDomainTest.java` — all edge cases

6. **Understand event flow:**
   - `services/ledger-service/.../adapter/in/kafka/LmsEventConsumer.java` — auto-posts journal entries
   - `services/notification-service/.../adapter/in/kafka/DomainEventNotificationConsumer.java` — universal consumer

7. **Check what's missing:**
   - The `# 10. Current Implementation Status` section above — especially the ❌ and 🟡 items.

8. **Before making any change, understand the architecture rule:**
   - `services/template-service/src/test/java/com/originex/template/HexagonalArchitectureTest.java`
   - Domain → no Spring, no JPA, no adapters
   - Application → no adapters
   - Inbound adapters → no outbound adapters

### Most Important Files to Understand

| Priority | File | Why |
|---|---|---|
| 1 | `libs/common/.../money/Money.java` | Every monetary operation uses this |
| 2 | `libs/spring-boot-starter/.../outbox/OutboxPublisher.java` | Every event goes through this |
| 3 | `pom.xml` (root) | All dependency versions managed here |
| 4 | `services/los-service/.../application/service/LoanApplicationService.java` | Most complex orchestration |
| 5 | `services/lms-service/.../domain/model/Loan.java` | Core financial aggregate |
| 6 | `services/bre-service/.../domain/engine/RuleEvaluationEngine.java` | Auto-decisioning logic |
| 7 | `services/ledger-service/.../adapter/in/kafka/LmsEventConsumer.java` | Double-entry posting rules |
| 8 | `dev/docker-compose.yml` | How to start local environment |
| 9 | `infra/kafka/topics.yaml` | All Kafka topics and config |

---

*This document was generated by analyzing all source code, configuration files, database migrations, Maven POMs, and infrastructure definitions in the Originex repository on July 9, 2026. All information is derived from actual code — no information has been invented.*
