# Domain Model — Loan Management System (LMS)

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Context Overview

The Loan Management System manages the entire post-disbursement loan lifecycle: schedule generation, repayment allocation, interest accrual, prepayment, restructuring, NPA classification, and maturity. It is the largest and most complex domain.

**Characteristics:**
- 5M+ active loans under management
- Daily interest accrual across entire portfolio (Flink)
- Complex repayment waterfall logic
- Multiple restructure scenarios
- RBI NPA classification (DPD-based)
- Event-heavy: most events in the platform originate from LMS

---

## 2. Aggregate: Loan

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         LOAN AGGREGATE                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Loan (Aggregate Root)                                                       │
│  ├── loanId: LoanId (Value Object — UUIDv7)                                 │
│  ├── tenantId: TenantId (Value Object)                                       │
│  ├── customerId: CustomerId (Value Object)                                   │
│  ├── applicationId: ApplicationId (Value Object — link to LOS)               │
│  ├── productCode: ProductCode (Value Object)                                 │
│  ├── status: LoanStatus (Value Object — state machine)                       │
│  │                                                                           │
│  ├── ── FINANCIAL TERMS ──                                                   │
│  ├── sanctionedAmount: Money                                                 │
│  ├── disbursedAmount: Money                                                  │
│  ├── outstandingPrincipal: Money                                             │
│  ├── outstandingInterest: Money                                              │
│  ├── outstandingCharges: Money                                               │
│  ├── totalOverdue: Money (derived: interest + principal + charges overdue)   │
│  ├── interestRate: InterestRate (Value Object — rate + type)                 │
│  ├── effectiveRate: BigDecimal (after reset, if floating)                    │
│  ├── tenure: Tenure (Value Object — original tenure in months)               │
│  ├── remainingTenure: int (months remaining)                                 │
│  ├── emiAmount: Money (current EMI)                                          │
│  ├── currency: Currency                                                      │
│  │                                                                           │
│  ├── ── DATES ──                                                             │
│  ├── sanctionDate: LocalDate                                                 │
│  ├── firstDisbursementDate: LocalDate                                        │
│  ├── maturityDate: LocalDate                                                 │
│  ├── nextDueDate: LocalDate                                                  │
│  ├── lastPaymentDate: LocalDate                                              │
│  ├── npaDate: LocalDate (null if performing)                                 │
│  ├── closureDate: LocalDate (null if active)                                 │
│  │                                                                           │
│  ├── ── DELINQUENCY ──                                                       │
│  ├── dpd: int (Days Past Due — calculated by Flink)                          │
│  ├── maxDpd: int (maximum DPD ever reached)                                  │
│  ├── dpdBucket: DPDBucket (Value Object: CURRENT, 1-30, 31-60, 61-90, 90+) │
│  ├── assetClassification: AssetClassification (STANDARD, SUB, DOUBTFUL, LOSS)│
│  │                                                                           │
│  ├── ── SCHEDULE ──                                                          │
│  ├── repaymentSchedule: RepaymentSchedule (Entity)                           │
│  │   ├── scheduleId: ScheduleId                                              │
│  │   ├── version: int (increments on restructure/prepayment)                 │
│  │   ├── generatedAt: Instant                                                │
│  │   ├── dayCountConvention: DayCountConvention (30/360, ACT/365, ACT/360)  │
│  │   └── installments: List<Installment> (Entity)                            │
│  │       ├── installmentNumber: int                                          │
│  │       ├── dueDate: LocalDate                                              │
│  │       ├── principalDue: Money                                             │
│  │       ├── interestDue: Money                                              │
│  │       ├── totalDue: Money                                                 │
│  │       ├── principalPaid: Money                                            │
│  │       ├── interestPaid: Money                                             │
│  │       ├── chargesPaid: Money                                              │
│  │       ├── status: InstallmentStatus (UPCOMING, DUE, PAID, OVERDUE,       │
│  │       │                              PARTIALLY_PAID, WAIVED)              │
│  │       ├── paidDate: LocalDate                                             │
│  │       └── penalInterest: Money                                            │
│  │                                                                           │
│  ├── ── DISBURSEMENTS ──                                                     │
│  ├── disbursements: List<Disbursement> (Entity)                              │
│  │   ├── disbursementId: DisbursementId                                      │
│  │   ├── amount: Money                                                       │
│  │   ├── disbursedAt: Instant                                                │
│  │   ├── beneficiaryAccount: BankAccount (Value Object)                      │
│  │   ├── paymentReference: PaymentReference                                  │
│  │   └── status: DisbursementStatus (INITIATED, COMPLETED, FAILED, REVERSED)│
│  │                                                                           │
│  ├── ── CHARGES ──                                                           │
│  ├── charges: List<LoanCharge> (Entity)                                      │
│  │   ├── chargeId: ChargeId                                                  │
│  │   ├── type: ChargeType (PROCESSING_FEE, LATE_FEE, PREPAYMENT_FEE, etc.) │
│  │   ├── amount: Money                                                       │
│  │   ├── leviedOn: LocalDate                                                 │
│  │   ├── paidAmount: Money                                                   │
│  │   └── status: ChargeStatus (PENDING, PAID, WAIVED)                        │
│  │                                                                           │
│  ├── ── METADATA ──                                                          │
│  ├── loanAccountNumber: LoanAccountNumber (Value Object — human-readable)    │
│  ├── branchCode: BranchCode (Value Object)                                   │
│  ├── relationship Manager: UserId                                            │
│  ├── version: long (optimistic locking)                                      │
│  └── createdAt / updatedAt: Instant                                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. State Machine: LoanStatus

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         LOAN STATUS STATE MACHINE                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌───────────────────┐                                                       │
│  │     CREATED        │ (Loan record created from approved application)      │
│  └─────────┬─────────┘                                                       │
│            │ initiateDisbursement()                                           │
│            ▼                                                                 │
│  ┌───────────────────┐                                                       │
│  │ PENDING_DISBURSAL │ (Awaiting payment processing)                         │
│  └─────────┬────┬────┘                                                       │
│            │    │ disbursementFailed()                                        │
│            │    ▼                                                             │
│            │ ┌─────────────────────┐                                         │
│            │ │ DISBURSEMENT_FAILED │ (Retryable)                             │
│            │ └─────────────────────┘                                         │
│            │                                                                 │
│            │ confirmDisbursement()                                            │
│            ▼                                                                 │
│  ┌───────────────────┐                                                       │
│  │      ACTIVE        │ (Loan is live, EMIs being collected)                 │
│  └────┬───┬───┬───┬──┘                                                       │
│       │   │   │   │                                                          │
│       │   │   │   │ classifyNPA() (90+ DPD)                                 │
│       │   │   │   ▼                                                          │
│       │   │   │ ┌──────┐                                                     │
│       │   │   │ │ NPA  │ (Non-Performing Asset)                              │
│       │   │   │ └──┬───┘                                                     │
│       │   │   │    │ writeOff()                                              │
│       │   │   │    ▼                                                         │
│       │   │   │ ┌────────────┐                                               │
│       │   │   │ │WRITTEN_OFF │ (Terminal — removed from books)               │
│       │   │   │ └────────────┘                                               │
│       │   │   │                                                              │
│       │   │   │ restructure()                                                │
│       │   │   ▼                                                              │
│       │   │ ┌──────────────┐                                                 │
│       │   │ │ RESTRUCTURED │ (Terms modified, schedule regenerated)          │
│       │   │ └──────┬───────┘                                                 │
│       │   │        │ (continues as ACTIVE with new terms)                    │
│       │   │        ▼ (→ ACTIVE)                                              │
│       │   │                                                                  │
│       │   │ foreclose() (full prepayment)                                    │
│       │   ▼                                                                  │
│       │ ┌────────────┐                                                       │
│       │ │ FORECLOSED │ (Terminal — prepaid in full)                           │
│       │ └────────────┘                                                       │
│       │                                                                      │
│       │ mature() (all installments paid, tenure complete)                    │
│       ▼                                                                      │
│  ┌──────────┐                                                                │
│  │ MATURED  │ (Terminal — natural completion)                                 │
│  └──────────┘                                                                │
│                                                                              │
│  ADDITIONAL:                                                                 │
│  • ACTIVE → SETTLED (collections settlement accepted)                        │
│  • ACTIVE → CANCELLED (pre-first-EMI cancellation during cooling-off)        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Invariants

| # | Invariant | Enforcement |
|---|-----------|-------------|
| 1 | Total disbursed amount cannot exceed sanctioned amount | disbursement validation |
| 2 | Repayment waterfall order: Charges → Penal Interest → Interest → Principal | allocateRepayment() |
| 3 | Interest accrual is daily, deterministic, and uses HALF_EVEN rounding | Flink accrual job |
| 4 | DPD is calculated as (today - oldest unpaid installment due date) | Flink DPD job |
| 5 | NPA classification at 90+ DPD (RBI norms) | Flink classification job |
| 6 | Schedule recalculation on prepayment: reduce tenure (default) or EMI | prepay() |
| 7 | Restructure creates new schedule version (old preserved for audit) | restructure() |
| 8 | Foreclosure requires outstanding + penal + charges clearance | foreclose() |
| 9 | Loan account number is unique per tenant | DB constraint |
| 10 | Only one active repayment schedule per loan | schedule version management |
| 11 | EMI calculation uses reducing balance method | schedule generation |
| 12 | Cooling-off exit allowed only within configured period post-disbursement | cancelWithinCoolingOff() |

---

## 5. Repayment Waterfall Logic

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    REPAYMENT ALLOCATION WATERFALL                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Input: Payment amount (e.g., ₹15,000)                                      │
│                                                                              │
│  Step 1: Allocate to CHARGES (oldest first)                                  │
│  ├── Late payment fee: ₹500 → Allocated ₹500, Remaining: ₹14,500           │
│  ├── Bounce charges: ₹300 → Allocated ₹300, Remaining: ₹14,200             │
│  └── Total charges allocated: ₹800                                          │
│                                                                              │
│  Step 2: Allocate to PENAL INTEREST (if any)                                 │
│  ├── Penal interest accrued: ₹200 → Allocated ₹200, Remaining: ₹14,000    │
│  └── Total penal allocated: ₹200                                            │
│                                                                              │
│  Step 3: Allocate to INTEREST (oldest installment first)                     │
│  ├── Installment 5 interest: ₹4,000 → Allocated ₹4,000, Remaining: ₹10,000│
│  ├── Installment 6 interest: ₹3,800 → Allocated ₹3,800, Remaining: ₹6,200 │
│  └── Total interest allocated: ₹7,800                                       │
│                                                                              │
│  Step 4: Allocate to PRINCIPAL (oldest installment first)                    │
│  ├── Installment 5 principal: ₹6,000 → Allocated ₹6,000, Remaining: ₹200  │
│  ├── Installment 6 principal: ₹6,200 → Allocated ₹200 (partial)            │
│  └── Total principal allocated: ₹6,200                                      │
│                                                                              │
│  Step 5: Excess (if any) → Advance EMI or Principal Prepayment              │
│  └── In this case: ₹0 excess                                               │
│                                                                              │
│  RESULT:                                                                     │
│  AllocationResult {                                                          │
│    chargesAllocated: ₹800,                                                  │
│    penalInterestAllocated: ₹200,                                            │
│    interestAllocated: ₹7,800,                                               │
│    principalAllocated: ₹6,200,                                              │
│    excess: ₹0,                                                              │
│    installmentsFullyPaid: [5],                                               │
│    installmentsPartiallyPaid: [6]                                            │
│  }                                                                           │
│                                                                              │
│  NOTE: Waterfall order is configurable per product/tenant.                   │
│  Above is RBI default. Some products allow different ordering.               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Interest Accrual Model

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    DAILY INTEREST ACCRUAL                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  FORMULA (Reducing Balance):                                                 │
│                                                                              │
│  Daily Interest = Outstanding Principal × (Annual Rate / Days in Year)       │
│                                                                              │
│  Day Count Conventions:                                                      │
│  ┌─────────────────────────────────────────────────────────────┐            │
│  │  30/360:    Days = 30, Year = 360 (standard for many loans) │            │
│  │  ACT/365:   Days = actual, Year = 365 (India RBI norm)      │            │
│  │  ACT/360:   Days = actual, Year = 360 (money market)        │            │
│  │  ACT/ACT:   Days = actual, Year = actual (365 or 366)       │            │
│  └─────────────────────────────────────────────────────────────┘            │
│                                                                              │
│  EXAMPLE (ACT/365):                                                          │
│  Outstanding Principal: ₹4,50,000                                            │
│  Annual Rate: 12.00%                                                         │
│  Daily Interest: 450000 × (0.12 / 365) = ₹147.9452                         │
│  Stored with scale 4, HALF_EVEN rounding                                    │
│                                                                              │
│  ACCRUAL JOURNAL ENTRY (published to Ledger):                                │
│  DR: Interest Receivable (Asset)     ₹147.9452                              │
│  CR: Interest Income (Revenue)       ₹147.9452                              │
│                                                                              │
│  PROCESSING:                                                                 │
│  • Flink job reads LoanDisbursed/RepaymentAllocated/RateChanged events       │
│  • Maintains per-loan state: principal, rate, last_accrual_date              │
│  • Timer triggers daily at EOD (event-time watermark)                        │
│  • Produces InterestAccrued event for each active loan                       │
│  • Ledger consumes event and posts journal entry                             │
│                                                                              │
│  DETERMINISM GUARANTEE:                                                      │
│  • Same inputs (principal, rate, date, convention) = same output always      │
│  • No floating point: BigDecimal with explicit scale and rounding            │
│  • Replay-safe: re-running accrual for same date produces identical result   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. EMI Calculation

```java
/**
 * EMI = P × r × (1+r)^n / ((1+r)^n - 1)
 * Where:
 *   P = Principal (loan amount)
 *   r = Monthly interest rate (annual rate / 12)
 *   n = Number of monthly installments (tenure in months)
 *
 * All calculations use BigDecimal with scale=10 intermediate,
 * final result rounded to scale=4 with HALF_EVEN.
 */
public Money calculateEMI(Money principal, InterestRate annualRate, Tenure tenure) {
    BigDecimal P = principal.getAmount();
    BigDecimal r = annualRate.monthlyRate(); // annualRate / 12 / 100
    int n = tenure.months();
    
    if (r.compareTo(BigDecimal.ZERO) == 0) {
        // Zero interest: simple division
        return Money.of(P.divide(BigDecimal.valueOf(n), 4, HALF_EVEN), 
                       principal.getCurrency());
    }
    
    // (1 + r)^n
    BigDecimal onePlusR = BigDecimal.ONE.add(r);
    BigDecimal power = onePlusR.pow(n, MathContext.DECIMAL128);
    
    // P × r × (1+r)^n
    BigDecimal numerator = P.multiply(r).multiply(power);
    
    // (1+r)^n - 1
    BigDecimal denominator = power.subtract(BigDecimal.ONE);
    
    // EMI
    BigDecimal emi = numerator.divide(denominator, 4, HALF_EVEN);
    
    return Money.of(emi, principal.getCurrency());
}
```

---

## 8. Domain Services

| Service | Responsibility |
|---------|---------------|
| LoanCreationService | Create loan from approved application, generate initial schedule |
| ScheduleGenerationService | Calculate amortization schedule (reducing balance) |
| RepaymentAllocationService | Apply waterfall logic to incoming payment |
| PrepaymentService | Handle partial/full prepayment, recalculate schedule |
| RestructureService | Modify loan terms, generate new schedule version |
| DisbursementSagaOrchestrator | Coordinate multi-service disbursement flow |
| InterestAccrualService | (Flink) Daily interest calculation per loan |
| DelinquencyService | (Flink) DPD calculation, NPA classification |
| ForeclosureService | Calculate foreclosure amount (principal + interest + charges) |
| CoolingOffService | Handle regulatory cooling-off period exits |
