# Domain Model — Loan Origination System (LOS)

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Context Overview

The Loan Origination System handles the entire loan application lifecycle from submission through credit decision, offer generation, and acceptance. It is the primary revenue funnel of the platform.

**Characteristics:**
- High volume: 100K+ applications per day
- Stateful workflow: Applications pass through multiple stages
- External dependencies: Bureau APIs, KYC providers, document verification
- Burst traffic: Marketing campaigns can spike volume 10x

---

## 2. Aggregate: LoanApplication

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    LOAN APPLICATION AGGREGATE                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  LoanApplication (Aggregate Root)                                            │
│  ├── applicationId: ApplicationId (Value Object — UUIDv7)                    │
│  ├── tenantId: TenantId (Value Object)                                       │
│  ├── customerId: CustomerId (Value Object)                                   │
│  ├── productCode: ProductCode (Value Object)                                 │
│  ├── status: ApplicationStatus (Value Object — enum state machine)           │
│  ├── requestedAmount: Money (Value Object)                                   │
│  ├── requestedTenure: Tenure (Value Object — months)                         │
│  ├── purpose: LoanPurpose (Value Object)                                     │
│  ├── channel: OriginationChannel (Value Object — API, PARTNER_PORTAL)        │
│  ├── applicant: Applicant (Entity)                                           │
│  │   ├── applicantId: ApplicantId                                            │
│  │   ├── name: PersonName (Value Object)                                     │
│  │   ├── dateOfBirth: LocalDate                                              │
│  │   ├── pan: PAN (Value Object — encrypted)                                 │
│  │   ├── aadhaarToken: AadhaarToken (Value Object — hashed)                  │
│  │   ├── employmentType: EmploymentType (Value Object)                       │
│  │   ├── monthlyIncome: Money (Value Object)                                 │
│  │   ├── employer: EmployerInfo (Value Object)                               │
│  │   └── existingObligations: Money (Value Object)                           │
│  ├── coApplicants: List<CoApplicant> (Entity, max 2)                         │
│  ├── documents: List<ApplicationDocument> (Entity)                           │
│  │   ├── documentId: DocumentId                                              │
│  │   ├── type: DocumentType (Value Object)                                   │
│  │   ├── status: DocumentStatus (PENDING, VERIFIED, REJECTED)                │
│  │   ├── storageUrl: DocumentUrl (Value Object)                              │
│  │   └── verifiedAt: Instant                                                 │
│  ├── creditCheck: CreditCheck (Entity)                                       │
│  │   ├── bureauName: BureauName (CIBIL, EXPERIAN, EQUIFAX, CRIF)            │
│  │   ├── score: CreditScore (Value Object — int + band)                      │
│  │   ├── reportRef: BureauReportReference (Value Object)                     │
│  │   ├── enquiryDate: Instant                                                │
│  │   └── expiresAt: Instant (valid for 30 days)                              │
│  ├── eligibilityResult: EligibilityResult (Value Object)                     │
│  │   ├── eligible: boolean                                                   │
│  │   ├── maxAmount: Money                                                    │
│  │   ├── offeredRate: InterestRate                                           │
│  │   ├── reasons: List<RejectionReason>                                      │
│  │   └── evaluatedAt: Instant                                                │
│  ├── offer: LoanOffer (Value Object — immutable once generated)              │
│  │   ├── offerId: OfferId                                                    │
│  │   ├── sanctionedAmount: Money                                             │
│  │   ├── interestRate: InterestRate                                          │
│  │   ├── tenure: Tenure                                                      │
│  │   ├── emi: Money (pre-calculated)                                         │
│  │   ├── processingFee: Money                                                │
│  │   ├── apr: BigDecimal (all-in-cost %)                                     │
│  │   ├── totalInterestPayable: Money                                         │
│  │   ├── totalRepayment: Money                                               │
│  │   ├── kfsDocumentUrl: DocumentUrl (Key Fact Statement)                    │
│  │   ├── generatedAt: Instant                                                │
│  │   └── expiresAt: Instant (default 7 days)                                 │
│  ├── assignedTo: UserId (underwriter assignment — nullable)                  │
│  ├── decisionNotes: String (underwriter remarks — nullable)                  │
│  ├── submittedAt: Instant                                                    │
│  ├── decidedAt: Instant                                                      │
│  ├── version: long (optimistic locking)                                      │
│  └── createdAt / updatedAt: Instant                                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. State Machine: ApplicationStatus

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    APPLICATION STATE MACHINE                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────┐                                                                │
│  │  DRAFT   │ (Partner started but not submitted)                            │
│  └────┬─────┘                                                                │
│       │ submit()                                                             │
│       ▼                                                                      │
│  ┌──────────┐                                                                │
│  │SUBMITTED │ (Received, pending initial checks)                             │
│  └────┬─────┘                                                                │
│       │ startProcessing()                                                    │
│       ▼                                                                      │
│  ┌──────────────┐                                                            │
│  │ IN_PROGRESS  │ (Documents being verified, bureau check in progress)       │
│  └────┬────┬────┘                                                            │
│       │    │                                                                 │
│       │    │ refer()           ┌──────────┐                                  │
│       │    └─────────────────►│ REFERRED  │ (Manual underwriting needed)     │
│       │                       └────┬──┬───┘                                  │
│       │                            │  │                                      │
│       │           approve()  ◄─────┘  │ reject()                             │
│       │ autoDecision()                │                                      │
│       │                               ▼                                      │
│       │                        ┌──────────┐                                  │
│       │                        │ REJECTED │ (Terminal — credit decision fail) │
│       │                        └──────────┘                                  │
│       ▼                                                                      │
│  ┌──────────┐                                                                │
│  │ APPROVED │ (Credit decision positive, offer being generated)              │
│  └────┬─────┘                                                                │
│       │ generateOffer()                                                      │
│       ▼                                                                      │
│  ┌──────────────┐                                                            │
│  │OFFER_PENDING │ (Offer generated, waiting for customer acceptance)         │
│  └────┬────┬────┘                                                            │
│       │    │ expire() (after validity period)                                │
│       │    ▼                                                                 │
│       │ ┌──────────┐                                                         │
│       │ │ EXPIRED  │ (Terminal — offer timed out)                             │
│       │ └──────────┘                                                         │
│       │                                                                      │
│       │ acceptOffer()                                                        │
│       ▼                                                                      │
│  ┌──────────────┐                                                            │
│  │OFFER_ACCEPTED│ (Customer accepted, ready for disbursement)                │
│  └────┬─────────┘                                                            │
│       │ requestDisbursement()                                                │
│       ▼                                                                      │
│  ┌───────────────────────┐                                                   │
│  │DISBURSEMENT_REQUESTED │ (Handed off to LMS — terminal for LOS)            │
│  └───────────────────────┘                                                   │
│                                                                              │
│  ADDITIONAL TRANSITIONS:                                                     │
│  • Any non-terminal state → WITHDRAWN (customer withdraws)                   │
│  • Any non-terminal state → CANCELLED (system/admin cancellation)            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Invariants (Business Rules)

| # | Invariant | Enforcement Point |
|---|-----------|-------------------|
| 1 | Requested amount must be within product min/max limits | submit() |
| 2 | Customer must have verified KYC before application | submit() — sync check to Customer Service |
| 3 | Duplicate application check: same PAN + product + 30 days | submit() — unique constraint + query |
| 4 | All mandatory documents must be verified before credit check | initiateCredit() |
| 5 | Credit score must be fetched within last 30 days | evaluateEligibility() |
| 6 | Offer validity cannot exceed configured maximum (default 7 days) | generateOffer() |
| 7 | Only one active offer per application at a time | generateOffer() |
| 8 | Sanctioned amount cannot exceed eligibility max amount | generateOffer() |
| 9 | State transitions must follow state machine (no skipping states) | All commands |
| 10 | Rejected/Expired/Withdrawn are terminal (no further transitions) | State machine guard |
| 11 | Application can have max 2 co-applicants | addCoApplicant() |
| 12 | APR must be calculated and disclosed before offer acceptance | acceptOffer() pre-check |

---

## 5. Commands & Domain Events

### Commands (Inbound)

| Command | Initiator | Pre-conditions |
|---------|-----------|----------------|
| SubmitApplication | Partner API | Valid customer, valid product, KYC complete |
| UploadDocument | Partner API | Application in SUBMITTED or IN_PROGRESS |
| VerifyDocument | Document Service (async) | Document exists, application not terminal |
| InitiateCreditCheck | System (auto after docs verified) | All mandatory docs verified |
| RecordCreditResult | Partner Service (bureau callback) | Credit check initiated |
| EvaluateEligibility | System (auto after credit result) | Credit result available |
| ApproveApplication | Underwriter / System | Eligibility positive |
| RejectApplication | Underwriter / System | Eligibility negative or policy rejection |
| ReferApplication | System (edge cases) | Eligibility inconclusive |
| GenerateOffer | System (auto after approval) | Application approved |
| AcceptOffer | Customer / Partner API | Offer valid and not expired |
| WithdrawApplication | Customer / Partner API | Application not terminal |
| RequestDisbursement | System (auto after acceptance) | Offer accepted |

### Domain Events (Published)

| Event | When | Key Consumers |
|-------|------|---------------|
| ApplicationSubmitted | After submit validation passes | Audit, Analytics, Notification |
| DocumentUploaded | Document attached to application | Document Service (verification) |
| DocumentVerified | Document passes verification | LOS (check all docs ready) |
| DocumentRejected | Document fails verification | Notification (re-upload request) |
| CreditCheckInitiated | Bureau request sent | Audit, Analytics |
| CreditCheckCompleted | Bureau response received | LOS (eligibility evaluation) |
| EligibilityDetermined | BRE evaluation complete | Analytics |
| ApplicationApproved | Positive credit decision | LMS (loan creation), Notification |
| ApplicationRejected | Negative credit decision | Notification, Analytics |
| ApplicationReferred | Needs manual review | Notification (underwriter) |
| OfferGenerated | Offer calculated and ready | Notification (customer) |
| OfferAccepted | Customer accepts terms | LMS (disbursement), Notification |
| OfferExpired | Validity period elapsed | Notification, Analytics |
| ApplicationWithdrawn | Customer withdraws | Notification, Analytics |
| DisbursementRequested | Handed to LMS | LMS (create loan + disburse) |

---

## 6. Value Objects

```java
// Money — core value object for all monetary amounts
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount, "Amount cannot be null");
        Objects.requireNonNull(currency, "Currency cannot be null");
        if (amount.scale() > 4) {
            throw new IllegalArgumentException("Amount scale cannot exceed 4");
        }
    }
    
    public static Money of(String amount, String currencyCode) {
        return new Money(
            new BigDecimal(amount).setScale(4, RoundingMode.HALF_EVEN),
            Currency.getInstance(currencyCode)
        );
    }
    
    public Money add(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }
    
    public Money subtract(Money other) {
        validateSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }
    
    public boolean isGreaterThan(Money other) {
        validateSameCurrency(other);
        return this.amount.compareTo(other.amount) > 0;
    }
}

// CreditScore — includes score and risk band
public record CreditScore(int score, RiskBand band) {
    public CreditScore {
        if (score < 0 || score > 900) {
            throw new IllegalArgumentException("Score must be 0-900");
        }
    }
    
    public enum RiskBand {
        EXCELLENT(750, 900),
        GOOD(700, 749),
        FAIR(650, 699),
        POOR(550, 649),
        VERY_POOR(0, 549);
        
        // ... range logic
    }
}

// InterestRate — annual rate with precision
public record InterestRate(BigDecimal annualRate, RateType type) {
    public InterestRate {
        if (annualRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Rate cannot be negative");
        }
        if (annualRate.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Rate cannot exceed 100%");
        }
    }
    
    public enum RateType { FIXED, FLOATING }
}

// Tenure — loan duration
public record Tenure(int months) {
    public Tenure {
        if (months < 1 || months > 360) {
            throw new IllegalArgumentException("Tenure must be 1-360 months");
        }
    }
}
```

---

## 7. Domain Services

| Service | Responsibility | Dependencies |
|---------|---------------|--------------|
| DuplicateApplicationChecker | Detect same customer + product within 30 days | ApplicationRepository |
| EligibilityEvaluator | Orchestrate BRE call with application context | BRE Service (gRPC) |
| OfferCalculator | Calculate EMI, APR, total cost, KFS | Product Config, Rate Tables |
| ApplicationWorkflowService | Enforce state machine transitions | ApplicationRepository |
| CreditCheckOrchestrator | Initiate and correlate bureau requests | Partner Service (gRPC) |

---

## 8. Anti-Corruption Layer (External Integrations)

```
┌─────────────────────────────────────────────────────────────────┐
│  LOS → Partner Service (ACL)                                     │
│                                                                  │
│  Internal Domain Model          External API Format              │
│  ─────────────────────          ────────────────────             │
│  CreditCheckRequest    ──►      Bureau-specific XML/JSON         │
│  CreditCheckResult     ◄──      Bureau-specific response         │
│                                                                  │
│  The Partner Service adapter translates between our domain       │
│  model and each bureau's proprietary format. LOS never deals     │
│  with external data formats directly.                            │
│                                                                  │
│  Bureau adapters:                                                │
│  ├── CibilAdapter (XML-based API)                                │
│  ├── ExperianAdapter (REST/JSON)                                 │
│  ├── EquifaxAdapter (SOAP/XML)                                   │
│  └── CrifAdapter (REST/JSON)                                     │
│                                                                  │
│  Each adapter implements: BureauPort (interface in domain layer) │
└─────────────────────────────────────────────────────────────────┘
```
