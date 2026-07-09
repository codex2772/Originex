# Domain Model — Bounded Contexts & Context Map

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Strategic Domain Classification

### 1.1 Core Domains (Competitive Advantage)

These domains represent the primary business value and competitive differentiation of the platform:

| Domain | Justification |
|--------|---------------|
| **Loan Origination** | Application intake, workflow orchestration, underwriting decisions — the primary revenue funnel |
| **Loan Management** | Loan lifecycle, schedule management, restructuring — core operational capability |
| **Ledger & Accounting** | Double-entry bookkeeping, financial correctness, regulatory reporting — trust foundation |
| **Risk & Underwriting** | Credit decisioning, scoring models, policy execution — risk-adjusted profitability |

### 1.2 Supporting Domains (Enable Core)

These domains support core business operations but are not primary differentiators:

| Domain | Justification |
|--------|---------------|
| **Payment Processing** | Disbursement & collection orchestration; integrates with payment rails |
| **Collections** | Delinquency management, recovery workflows; directly impacts NPA ratios |
| **Business Rules Engine** | Configurable rules for eligibility, pricing, routing; enables product flexibility |
| **Offer Engine** | Pre-approved offers, personalized pricing; drives conversion |

### 1.3 Generic Domains (Buy/Standard)

These domains are standard capabilities with minimal business differentiation:

| Domain | Justification |
|--------|---------------|
| **Identity & Access Management** | Authentication, authorization, tenant management; standard OIDC/OAuth2 |
| **Customer Management** | Customer profiles, KYC data, consent; standard CRM capability |
| **Document Management** | Document storage, OCR, verification; commodity functionality |
| **Notification** | Multi-channel messaging (SMS, Email, Push, WhatsApp); standard capability |
| **Reporting & Analytics** | Dashboards, MIS, regulatory reports; standard BI functionality |
| **Audit** | Immutable event log, compliance audit trail; standard infrastructure |
| **Configuration** | Tenant config, product config, feature flags; standard platform capability |
| **Partner Integration** | Bureau APIs, payment gateways, insurance; anti-corruption layer |

---

## 2. Bounded Context Map

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                              ORIGINEX CONTEXT MAP                                             │
├─────────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                              │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐              │
│  │   CUSTOMER   │    │     IAM      │    │    TENANT    │    │    CONFIG    │              │
│  │  Management  │    │  (Identity)  │    │  Management  │    │  Management  │              │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘    └──────┬───────┘              │
│         │                   │                    │                    │                      │
│         │ U/D               │ U/D                │ U/D               │ U/D                  │
│         ▼                   ▼                    ▼                    ▼                      │
│  ┌─────────────────────────────────────────────────────────────────────────────┐            │
│  │                        LOAN ORIGINATION SYSTEM (LOS)                         │            │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐         │            │
│  │  │  App    │  │  BRE    │  │ Bureau  │  │  Offer  │  │  Doc    │         │            │
│  │  │ Intake  │  │(Rules)  │  │  Check  │  │ Engine  │  │  Mgmt   │         │            │
│  │  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘         │            │
│  └───────┼─────────────┼───────────┼─────────────┼─────────────┼─────────────┘            │
│          │             │           │             │             │                            │
│          │ Published Events: ApplicationSubmitted, CreditDecisionMade, OfferGenerated       │
│          ▼             ▼           ▼             ▼             ▼                            │
│  ┌─────────────────────────────────────────────────────────────────────────────┐            │
│  │                        LOAN MANAGEMENT SYSTEM (LMS)                          │            │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐         │            │
│  │  │  Loan   │  │Schedule │  │Disburse │  │Repay    │  │Restruc- │         │            │
│  │  │Lifecycle│  │  Mgmt   │  │  ment   │  │  ment   │  │  ture   │         │            │
│  │  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘  └────┬────┘         │            │
│  └───────┼─────────────┼───────────┼─────────────┼─────────────┼─────────────┘            │
│          │             │           │             │             │                            │
│          ▼             ▼           ▼             ▼             ▼                            │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐               │
│  │    LEDGER     │  │   PAYMENT     │  │  COLLECTIONS  │  │ NOTIFICATIONS │               │
│  │  (Accounting) │  │  PROCESSING   │  │               │  │               │               │
│  └───────┬───────┘  └───────┬───────┘  └───────┬───────┘  └───────────────┘               │
│          │                   │                   │                                          │
│          ▼                   ▼                   ▼                                          │
│  ┌───────────────────────────────────────────────────────────┐                             │
│  │              REPORTING & ANALYTICS                          │                             │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐                │                             │
│  │  │   MIS    │  │Regulatory│  │ Portfolio │                │                             │
│  │  │Dashboard │  │ Reports  │  │ Analytics │                │                             │
│  │  └──────────┘  └──────────┘  └──────────┘                │                             │
│  └───────────────────────────────────────────────────────────┘                             │
│                                                                                              │
│  ┌────────────────────────────────────────────┐                                            │
│  │         PARTNER INTEGRATION (ACL)           │                                            │
│  │  ┌────────┐ ┌────────┐ ┌────────┐         │                                            │
│  │  │ Bureau │ │Payment │ │Insurance│         │                                            │
│  │  │  APIs  │ │  Rails │ │  APIs   │         │                                            │
│  │  └────────┘ └────────┘ └────────┘         │                                            │
│  └────────────────────────────────────────────┘                                            │
│                                                                                              │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

Legend:
  U/D = Upstream/Downstream (Published Language)
  ACL = Anti-Corruption Layer
  ──► = Event Flow (Async)
  ──> = Command/Query (Sync)
```

---

## 3. Context Relationships & Integration Patterns

| Upstream Context | Downstream Context | Relationship | Pattern |
|-----------------|-------------------|--------------|---------|
| Customer | LOS | Customer-Supplier | Published Language (Events) |
| IAM | All Services | Conformist | Shared Kernel (JWT claims) |
| Tenant | All Services | Conformist | Shared Kernel (tenant context) |
| LOS | LMS | Customer-Supplier | Domain Events via Kafka |
| LMS | Ledger | Customer-Supplier | Domain Events (financial postings) |
| LMS | Payment Processing | Customer-Supplier | Commands via Kafka |
| LMS | Collections | Customer-Supplier | Domain Events (delinquency) |
| LMS | Notifications | Customer-Supplier | Domain Events |
| Ledger | Reporting | Customer-Supplier | CDC + Read Models |
| Payment Processing | LMS | Customer-Supplier | Events (payment confirmation) |
| Partner Integration | LOS, Payments | Anti-Corruption Layer | Adapter Pattern |
| BRE | LOS, LMS | Open Host Service | gRPC API |
| Config | All Services | Published Language | Config Events |

---

## 4. Bounded Context Definitions

### 4.1 Customer Management Context

**Responsibility:** Single source of truth for customer identity, profile, and KYC status.

**Aggregates:**
- `Customer` (Aggregate Root)
  - `CustomerProfile` (Entity)
  - `KYCRecord` (Entity)
  - `BankAccount` (Entity)
  - `Address` (Value Object)
  - `PhoneNumber` (Value Object)
  - `Email` (Value Object)
  - `CustomerId` (Value Object)
  - `PANNumber` (Value Object)
  - `AadhaarToken` (Value Object — tokenized)

**Domain Events:**
- `CustomerRegistered`
- `CustomerProfileUpdated`
- `KYCCompleted`
- `KYCExpired`
- `BankAccountAdded`
- `BankAccountVerified`

**Invariants:**
- A customer must have verified KYC before any loan application
- PAN is unique across the system
- Phone number verified via OTP before registration
- Aadhaar stored only as irreversible token (DPDPA compliance)

**Microservice decision:** ✅ Independent service — distinct lifecycle, high query volume, shared across all loan products.

---

### 4.2 Identity & Access Management (IAM) Context

**Responsibility:** Authentication, authorization, session management, API key management, tenant-scoped access control.

**Aggregates:**
- `User` (Aggregate Root)
  - `Credential` (Entity)
  - `Role` (Entity)
  - `Permission` (Value Object)
  - `Session` (Entity)
- `APIKey` (Aggregate Root)
- `TenantRole` (Aggregate Root)

**Domain Events:**
- `UserCreated`
- `UserAuthenticated`
- `PasswordChanged`
- `RoleAssigned`
- `APIKeyGenerated`
- `APIKeyRevoked`

**Invariants:**
- Password must meet complexity requirements
- MFA required for admin roles
- API keys scoped to specific tenants and permissions
- Session timeout configurable per tenant

**Microservice decision:** ✅ Independent service — security boundary; uses Keycloak internally; scales independently for auth storms.

---

### 4.3 Loan Origination System (LOS) Context

**Responsibility:** Loan application lifecycle from submission through credit decision to offer acceptance.

**Aggregates:**
- `LoanApplication` (Aggregate Root)
  - `Applicant` (Entity)
  - `CoApplicant` (Entity)
  - `ApplicationDocument` (Entity)
  - `CreditCheck` (Entity)
  - `EligibilityResult` (Value Object)
  - `CreditScore` (Value Object)
  - `ApplicationStatus` (Value Object)
  - `Money` (Value Object)
  - `LoanProduct` (Value Object)
- `ApplicationWorkflow` (Aggregate Root)
  - `WorkflowStep` (Entity)
  - `WorkflowDecision` (Value Object)

**Domain Events:**
- `ApplicationSubmitted`
- `ApplicationAssigned`
- `DocumentUploaded`
- `DocumentVerified`
- `CreditCheckInitiated`
- `CreditCheckCompleted`
- `EligibilityDetermined`
- `ApplicationApproved`
- `ApplicationRejected`
- `ApplicationReferred`
- `OfferGenerated`
- `OfferAccepted`
- `OfferExpired`
- `ApplicationDisbursementRequested`

**Invariants:**
- Application amount must be within product limits
- All mandatory documents must be verified before credit check
- Credit score must be fetched within 30 days
- Offer validity period is configurable (default 7 days)
- Duplicate application check (same PAN + product + 30-day window)

**Microservice decision:** ✅ Independent service — complex workflow state machine; high volume (100K/day); distinct scaling profile (burst during marketing campaigns).

---

### 4.4 Business Rules Engine (BRE) Context

**Responsibility:** Centralized, configurable business rule evaluation for eligibility, pricing, routing, and policy decisions.

**Aggregates:**
- `RuleSet` (Aggregate Root)
  - `Rule` (Entity)
  - `RuleCondition` (Value Object)
  - `RuleAction` (Value Object)
  - `RuleVersion` (Value Object)
- `DecisionTable` (Aggregate Root)
- `ScoreCard` (Aggregate Root)

**Domain Events:**
- `RuleSetPublished`
- `RuleSetDeprecated`
- `RuleEvaluated`
- `DecisionRecorded`

**Invariants:**
- Only one active version of a rule set at a time
- Rule changes require approval workflow
- All rule evaluations are logged immutably
- Rules are tenant-scoped

**Microservice decision:** ✅ Independent service — stateless evaluation engine; must be low-latency (<50ms); scales horizontally; used by multiple contexts (LOS, LMS, Collections).

---

### 4.5 Loan Management System (LMS) Context

**Responsibility:** Post-disbursement loan lifecycle management including schedule generation, repayment processing, interest accrual, and restructuring.

**Aggregates:**
- `Loan` (Aggregate Root)
  - `RepaymentSchedule` (Entity)
  - `Installment` (Entity)
  - `Disbursement` (Entity)
  - `Prepayment` (Entity)
  - `LoanStatus` (Value Object)
  - `InterestRate` (Value Object)
  - `Money` (Value Object)
  - `Tenure` (Value Object)
- `LoanRestructure` (Aggregate Root)
  - `RestructureTerms` (Entity)
  - `RestructureApproval` (Entity)

**Domain Events:**
- `LoanCreated`
- `LoanDisbursed`
- `ScheduleGenerated`
- `InstallmentDue`
- `RepaymentReceived`
- `RepaymentAllocated`
- `InterestAccrued`
- `PrepaymentProcessed`
- `LoanForeclosed`
- `LoanMatured`
- `LoanRestructured`
- `LoanNPAClassified`
- `LoanWrittenOff`

**Invariants:**
- Total disbursement cannot exceed sanctioned amount
- Repayment allocation follows waterfall: charges → interest → principal
- Interest accrual is daily and deterministic
- Schedule recalculation on prepayment/restructure
- NPA classification per RBI norms (90+ DPD)

**Microservice decision:** ✅ Independent service — largest aggregate complexity; 5M+ active loans; event-sourced for audit trail; distinct scaling for EOD processing.

---

### 4.6 Ledger & Accounting Context

**Responsibility:** Double-entry bookkeeping, chart of accounts, financial postings, trial balance, and reconciliation.

**Aggregates:**
- `Account` (Aggregate Root)
  - `AccountBalance` (Entity)
  - `AccountType` (Value Object — ASSET, LIABILITY, INCOME, EXPENSE, EQUITY)
  - `Currency` (Value Object)
- `JournalEntry` (Aggregate Root)
  - `Posting` (Entity)
  - `PostingLeg` (Value Object — DEBIT/CREDIT)
  - `Money` (Value Object)
  - `PostingDate` (Value Object)
  - `ValueDate` (Value Object)
- `ChartOfAccounts` (Aggregate Root)
  - `GLAccount` (Entity)
  - `AccountHierarchy` (Value Object)

**Domain Events:**
- `AccountOpened`
- `JournalEntryPosted`
- `JournalEntryReversed`
- `BalanceUpdated`
- `TrialBalanceGenerated`
- `ReconciliationCompleted`
- `ReconciliationDiscrepancyDetected`

**Invariants:**
- Every journal entry must balance (total debits = total credits)
- Entries are immutable — corrections via reversal entries only
- Account balance is derived from sum of postings (event-sourced)
- Posting date vs value date distinction for accrual accounting
- Multi-currency support with exchange rate at posting time

**Microservice decision:** ✅ Independent service — **EVENT SOURCED** — immutable append-only log is natural fit; 500M+ transactions/day; strictest correctness requirements; isolated failure domain.

---

### 4.7 Payment Processing Context

**Responsibility:** Orchestration of payment disbursements and collections across multiple payment rails (NEFT, RTGS, IMPS, UPI, NACH, e-Mandate).

**Aggregates:**
- `PaymentOrder` (Aggregate Root)
  - `PaymentInstruction` (Entity)
  - `PaymentAttempt` (Entity)
  - `PaymentStatus` (Value Object)
  - `PaymentRail` (Value Object)
  - `Money` (Value Object)
  - `BeneficiaryAccount` (Value Object)
- `Mandate` (Aggregate Root)
  - `MandateRegistration` (Entity)
  - `MandateStatus` (Value Object)
- `Reconciliation` (Aggregate Root)
  - `BankStatement` (Entity)
  - `MatchResult` (Value Object)

**Domain Events:**
- `PaymentOrderCreated`
- `PaymentInitiated`
- `PaymentSucceeded`
- `PaymentFailed`
- `PaymentReversed`
- `MandateRegistered`
- `MandateActivated`
- `MandateRevoked`
- `ReconciliationCompleted`

**Invariants:**
- Payment idempotency — same order ID never processed twice
- Disbursement requires valid beneficiary verification
- NACH mandate must be active before auto-debit
- Daily/monthly limits per payment rail
- Reconciliation within T+1

**Microservice decision:** ✅ Independent service — integrates with external payment rails; distinct SLA requirements; circuit breaker isolation from partner failures.

---

### 4.8 Collections Context

**Responsibility:** Delinquency management, dunning workflows, recovery strategies, and legal proceedings tracking.

**Aggregates:**
- `CollectionCase` (Aggregate Root)
  - `DelinquencyRecord` (Entity)
  - `CollectionAction` (Entity)
  - `CollectionAssignment` (Entity)
  - `DPD` (Value Object — Days Past Due)
  - `CollectionBucket` (Value Object)
- `DunningStrategy` (Aggregate Root)
  - `DunningStep` (Entity)
  - `CommunicationTemplate` (Value Object)

**Domain Events:**
- `DelinquencyDetected`
- `CollectionCaseOpened`
- `CollectionActionExecuted`
- `PromiseToPayReceived`
- `PromiseToPayBroken`
- `SettlementOffered`
- `SettlementAccepted`
- `LegalNoticeIssued`
- `CollectionCaseClosed`

**Invariants:**
- DPD calculation is deterministic based on due date
- Collection actions respect RBI fair practices code
- No collection calls outside permitted hours
- Settlement amount must exceed minimum recovery threshold

**Microservice decision:** ✅ Independent service — distinct workflow engine; different SLA; regulatory isolation; separate team ownership.

---

### 4.9 Notification Context

**Responsibility:** Multi-channel notification delivery (SMS, Email, Push, WhatsApp, IVR) with template management and delivery tracking.

**Aggregates:**
- `NotificationRequest` (Aggregate Root)
  - `NotificationChannel` (Value Object)
  - `NotificationTemplate` (Entity)
  - `DeliveryAttempt` (Entity)
  - `DeliveryStatus` (Value Object)
- `Template` (Aggregate Root)
  - `TemplateVersion` (Entity)
  - `TemplateVariable` (Value Object)

**Domain Events:**
- `NotificationRequested`
- `NotificationDelivered`
- `NotificationFailed`
- `NotificationBounced`
- `TemplatePublished`

**Invariants:**
- Regulatory notifications (EMI reminder, overdue) are mandatory
- DND compliance check before marketing communications
- Template changes require approval
- Delivery SLA: SMS < 30s, Email < 60s

**Microservice decision:** ✅ Independent service — high volume, async, scales independently; vendor abstraction (multiple SMS/email providers).

---

### 4.10 Reporting & Analytics Context

**Responsibility:** Business intelligence, regulatory reporting, MIS dashboards, and portfolio analytics.

**Aggregates:**
- `Report` (Aggregate Root)
  - `ReportDefinition` (Entity)
  - `ReportSchedule` (Entity)
  - `ReportExecution` (Entity)
- `Dashboard` (Aggregate Root)
  - `Widget` (Entity)
  - `DataSource` (Value Object)

**Domain Events:**
- `ReportGenerated`
- `ReportScheduled`
- `DashboardCreated`

**Invariants:**
- Regulatory reports must be generated within prescribed timelines
- Report data must be consistent (point-in-time snapshot)
- Data freshness SLA: real-time for dashboards, T+1 for regulatory

**Microservice decision:** ✅ Independent service — CQRS read-side; heavy compute; different scaling profile; OpenSearch/ClickHouse backend.

---

### 4.11 Tenant Management Context

**Responsibility:** Multi-tenant configuration, onboarding, billing, feature flags, and resource isolation.

**Aggregates:**
- `Tenant` (Aggregate Root)
  - `TenantConfiguration` (Entity)
  - `TenantPlan` (Entity)
  - `TenantLimit` (Value Object)
  - `FeatureFlag` (Value Object)
- `TenantBilling` (Aggregate Root)
  - `UsageRecord` (Entity)
  - `Invoice` (Entity)

**Domain Events:**
- `TenantOnboarded`
- `TenantConfigUpdated`
- `TenantSuspended`
- `TenantPlanChanged`
- `UsageRecorded`

**Invariants:**
- Tenant ID present in every request (header/JWT claim)
- Resource limits enforced (API rate, storage, users)
- Tenant data isolation at database level (RLS)
- Configuration changes are versioned and auditable

**Microservice decision:** ✅ Independent service — platform-level concern; low volume but high criticality; distinct security boundary.

---

### 4.12 Partner Integration Context (Anti-Corruption Layer)

**Responsibility:** Adapter layer for all external partner integrations — credit bureaus, payment gateways, insurance providers, data providers.

**Aggregates:**
- `PartnerConnection` (Aggregate Root)
  - `PartnerCredential` (Entity)
  - `PartnerEndpoint` (Entity)
  - `CircuitBreakerState` (Value Object)
- `IntegrationRequest` (Aggregate Root)
  - `RequestPayload` (Value Object)
  - `ResponsePayload` (Value Object)
  - `RetryPolicy` (Value Object)

**Domain Events:**
- `PartnerRequestSent`
- `PartnerResponseReceived`
- `PartnerTimeout`
- `PartnerCircuitOpened`
- `PartnerCircuitClosed`

**Invariants:**
- All partner responses cached with configurable TTL
- Circuit breaker prevents cascade failures
- Request/response logged for audit (PII masked)
- Credentials rotated per partner schedule

**Microservice decision:** ✅ Independent service — fault isolation; each partner adapter is independently deployable; bulkhead pattern prevents one partner failure from affecting others.

---

### 4.13 Audit Context

**Responsibility:** Immutable, tamper-evident audit trail for all system actions, data changes, and business decisions.

**Aggregates:**
- `AuditEvent` (Aggregate Root — append-only)
  - `Actor` (Value Object)
  - `Action` (Value Object)
  - `Resource` (Value Object)
  - `ChangeSet` (Value Object)
  - `Timestamp` (Value Object)
  - `CorrelationId` (Value Object)

**Domain Events:**
- `AuditEventRecorded` (meta-event)

**Invariants:**
- Audit events are immutable — no updates or deletes
- Every state change produces an audit event
- Retention: 8 years minimum (regulatory requirement)
- Tamper-evident: hash chain linking events

**Microservice decision:** ✅ Independent service — append-only store (OpenSearch + S3 archival); must never be a bottleneck; async ingestion via Kafka.

---

## 5. Ubiquitous Language Glossary

| Term | Definition |
|------|-----------|
| **Loan Application** | A request to borrow, containing applicant details and desired terms |
| **Sanctioned Amount** | The approved loan amount after credit decision |
| **Disbursement** | The actual transfer of funds to the borrower |
| **EMI** | Equated Monthly Installment — periodic repayment amount |
| **DPD** | Days Past Due — number of days a payment is overdue |
| **NPA** | Non-Performing Asset — loan classified as delinquent (90+ DPD per RBI) |
| **Prepayment** | Early repayment of loan (partial or full) |
| **Foreclosure** | Complete early repayment and closure of loan |
| **Restructure** | Modification of loan terms (rate, tenure, EMI) |
| **Journal Entry** | A double-entry accounting record (debits = credits) |
| **GL Account** | General Ledger account in the chart of accounts |
| **NACH** | National Automated Clearing House — auto-debit mandate system |
| **e-Mandate** | Electronic mandate for recurring payments |
| **Bureau Pull** | Credit information report request from CIBIL/Experian/Equifax |
| **Waterfall** | Priority order for repayment allocation (charges → interest → principal) |
| **Write-Off** | Accounting removal of NPA from books (does not forgive debt) |
| **Settlement** | Agreed reduced payment to close a delinquent account |
| **Dunning** | Systematic collection communication strategy |
| **Accrual** | Recognition of income/expense before cash movement |
| **Posting Date** | Date the entry is recorded in the ledger |
| **Value Date** | Date the entry takes economic effect |
