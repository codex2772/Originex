# Domain Model — Ledger & Accounting

**Version:** 1.0.0  
**Status:** Approved  
**Last Updated:** 2026-07-08  

---

## 1. Context Overview

The Ledger service is the **financial system of record** — a double-entry bookkeeping engine that records every monetary movement as balanced journal entries. It is **event-sourced** (see ADR-002) to guarantee complete auditability, immutability, and temporal query capability.

**Characteristics:**
- 500M+ transactions per day (highest volume service)
- Event-sourced: append-only, immutable event log
- Zero tolerance for data loss or inconsistency
- Double-entry: every posting must balance (debits = credits)
- Temporal: can reconstruct balance at any point in time
- Multi-currency support (extensible)

---

## 2. Aggregate: Account

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         ACCOUNT AGGREGATE                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Account (Aggregate Root — Event Sourced)                                    │
│  ├── accountId: AccountId (Value Object — UUIDv7)                            │
│  ├── tenantId: TenantId (Value Object)                                       │
│  ├── accountNumber: AccountNumber (Value Object — human-readable)            │
│  ├── name: String (e.g., "Interest Receivable - ACME Bank")                  │
│  ├── accountType: AccountType (Value Object)                                 │
│  │   └── enum: ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE                    │
│  ├── normalBalance: DebitCredit (DEBIT for Asset/Expense; CREDIT for others)│
│  ├── currency: Currency (Value Object — ISO 4217)                            │
│  ├── balance: Money (derived from events — cached in snapshot)               │
│  ├── status: AccountStatus (ACTIVE, FROZEN, CLOSED)                          │
│  ├── parentAccountId: AccountId (nullable — for hierarchy)                   │
│  ├── glCode: GLCode (Value Object — Chart of Accounts code)                  │
│  ├── loanId: LoanId (nullable — loan-specific sub-accounts)                  │
│  ├── customerId: CustomerId (nullable — customer-specific accounts)          │
│  ├── metadata: Map<String, String>                                           │
│  ├── sequenceNumber: long (last applied event sequence)                      │
│  ├── version: long (snapshot version for optimistic locking)                 │
│  └── openedAt / closedAt: Instant                                            │
│                                                                              │
│  STATE DERIVATION:                                                           │
│  balance = SUM(all postings where side = normalBalance)                      │
│          - SUM(all postings where side ≠ normalBalance)                      │
│                                                                              │
│  For ASSET account (normalBalance = DEBIT):                                  │
│    balance = SUM(debits) - SUM(credits)                                      │
│  For LIABILITY account (normalBalance = CREDIT):                             │
│    balance = SUM(credits) - SUM(debits)                                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Aggregate: JournalEntry

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         JOURNAL ENTRY AGGREGATE                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  JournalEntry (Aggregate Root — Immutable once posted)                       │
│  ├── entryId: JournalEntryId (Value Object — UUIDv7)                         │
│  ├── tenantId: TenantId (Value Object)                                       │
│  ├── entryType: JournalEntryType (Value Object)                              │
│  │   └── enum: DISBURSEMENT, REPAYMENT, INTEREST_ACCRUAL, FEE_LEVY,         │
│  │             REVERSAL, WRITE_OFF, PROVISION, ADJUSTMENT, CLOSURE           │
│  ├── postingDate: LocalDate (when recorded in books)                         │
│  ├── valueDate: LocalDate (when economically effective)                      │
│  ├── description: String                                                     │
│  ├── reference: TransactionReference (Value Object)                          │
│  │   ├── sourceSystem: String (e.g., "LMS", "PAYMENT")                      │
│  │   ├── sourceId: String (e.g., loan_id, payment_order_id)                 │
│  │   └── sourceEventId: String (triggering event ID)                         │
│  ├── postings: List<Posting> (Entity — min 2, balanced)                      │
│  │   ├── postingId: PostingId                                                │
│  │   ├── accountId: AccountId                                                │
│  │   ├── side: DebitCredit (DEBIT or CREDIT)                                 │
│  │   ├── amount: Money                                                       │
│  │   └── narration: String (line-level description)                          │
│  ├── status: EntryStatus (POSTED, REVERSED)                                  │
│  ├── reversalOf: JournalEntryId (nullable — if this is a reversal)           │
│  ├── reversedBy: JournalEntryId (nullable — if this was reversed)            │
│  ├── correlationId: CorrelationId                                            │
│  ├── postedBy: ActorId (system or user who posted)                           │
│  ├── postedAt: Instant                                                       │
│  └── metadata: Map<String, String>                                           │
│                                                                              │
│  INVARIANTS:                                                                 │
│  1. SUM(debit amounts) MUST EQUAL SUM(credit amounts)                        │
│  2. Minimum 2 postings (one debit, one credit)                               │
│  3. All postings must be in the same currency (or explicit FX rate)          │
│  4. Once posted, NEVER modified — corrections via reversal only              │
│  5. Reversal creates a mirror entry (debits↔credits swapped)                │
│  6. Amount must be positive (side determines direction)                       │
│  7. Account must be ACTIVE to receive postings                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Chart of Accounts (COA)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CHART OF ACCOUNTS (Lending Platform)                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1000 — ASSETS                                                               │
│  ├── 1100 — Cash & Bank                                                      │
│  │   ├── 1101 — Settlement Account (bank pool)                               │
│  │   └── 1102 — Float Account                                                │
│  ├── 1200 — Loans & Advances                                                │
│  │   ├── 1201 — Loan Principal Outstanding                                   │
│  │   ├── 1202 — Interest Receivable (accrued but not due)                    │
│  │   ├── 1203 — Interest Overdue (due but not received)                      │
│  │   ├── 1204 — Charges Receivable                                           │
│  │   └── 1205 — Penal Interest Receivable                                    │
│  ├── 1300 — Provisions (Contra-Asset)                                        │
│  │   ├── 1301 — Provision for Standard Assets                                │
│  │   ├── 1302 — Provision for Sub-Standard Assets                            │
│  │   ├── 1303 — Provision for Doubtful Assets                                │
│  │   └── 1304 — Provision for Loss Assets                                    │
│  └── 1400 — Other Assets                                                     │
│      └── 1401 — Processing Fee Receivable                                    │
│                                                                              │
│  2000 — LIABILITIES                                                          │
│  ├── 2100 — Borrower Liabilities                                             │
│  │   ├── 2101 — Customer Advance (excess payment)                            │
│  │   └── 2102 — Security Deposit                                             │
│  └── 2200 — Other Liabilities                                                │
│      ├── 2201 — Tax Payable (TDS on interest)                                │
│      └── 2202 — GST Payable                                                  │
│                                                                              │
│  3000 — EQUITY                                                               │
│  └── 3100 — Retained Earnings                                                │
│                                                                              │
│  4000 — REVENUE                                                              │
│  ├── 4100 — Interest Income                                                  │
│  │   ├── 4101 — Interest Income - Standard                                   │
│  │   └── 4102 — Interest Income - NPA (recognized on cash basis)             │
│  ├── 4200 — Fee Income                                                       │
│  │   ├── 4201 — Processing Fee Income                                        │
│  │   ├── 4202 — Late Payment Fee Income                                      │
│  │   ├── 4203 — Prepayment/Foreclosure Fee Income                            │
│  │   └── 4204 — Bounce Charges Income                                        │
│  └── 4300 — Other Income                                                     │
│      └── 4301 — Recovery from Written-Off Accounts                           │
│                                                                              │
│  5000 — EXPENSES                                                             │
│  ├── 5100 — Provisioning Expense                                             │
│  ├── 5200 — Write-Off Expense                                                │
│  └── 5300 — Interest Expense (cost of funds)                                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Standard Journal Entry Templates

### 5.1 Loan Disbursement

```
Event: LoanDisbursed (amount: ₹5,00,000)

DR  1201 Loan Principal Outstanding     ₹5,00,000
CR  1101 Settlement Account             ₹5,00,000

(Processing Fee — if upfront):
DR  1401 Processing Fee Receivable      ₹5,000
CR  4201 Processing Fee Income          ₹5,000
```

### 5.2 Daily Interest Accrual

```
Event: InterestAccrued (amount: ₹147.95, loan performing)

DR  1202 Interest Receivable            ₹147.95
CR  4101 Interest Income - Standard     ₹147.95
```

### 5.3 EMI Repayment Received

```
Event: RepaymentAllocated (interest: ₹4,000, principal: ₹11,000)

(Interest portion):
DR  1101 Settlement Account             ₹4,000
CR  1202 Interest Receivable            ₹4,000

(Principal portion):
DR  1101 Settlement Account             ₹11,000
CR  1201 Loan Principal Outstanding     ₹11,000
```

### 5.4 Late Payment Fee

```
Event: ChargeLevied (type: LATE_FEE, amount: ₹500)

DR  1204 Charges Receivable             ₹500
CR  4202 Late Payment Fee Income        ₹500
```

### 5.5 NPA Classification (Interest Reversal)

```
Event: NPAClassified (unrealized interest: ₹12,000)

(Reverse accrued income):
DR  4101 Interest Income - Standard     ₹12,000
CR  1202 Interest Receivable            ₹12,000

(Create provision):
DR  5100 Provisioning Expense           ₹50,000
CR  1302 Provision for Sub-Standard     ₹50,000
```

### 5.6 Loan Write-Off

```
Event: LoanWrittenOff (principal: ₹4,50,000, interest: ₹25,000)

DR  5200 Write-Off Expense              ₹4,75,000
CR  1201 Loan Principal Outstanding     ₹4,50,000
CR  1203 Interest Overdue               ₹25,000

(Reverse provision):
DR  1304 Provision for Loss Assets      ₹4,75,000
CR  5100 Provisioning Expense           ₹4,75,000
```

---

## 6. Event Sourcing Mechanics

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    EVENT SOURCING — LEDGER                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  WRITE PATH:                                                                 │
│  ═══════════                                                                 │
│  1. Command received: PostJournalEntry(entries[])                             │
│  2. Load Account aggregate (from snapshot + replay since snapshot)            │
│  3. Validate: debits == credits, accounts active, sufficient balance         │
│  4. Generate event: JournalEntryPosted                                       │
│  5. Append to ledger_events table (within TX):                               │
│     - Optimistic concurrency: expected_sequence = current + 1                │
│     - If conflict (concurrent write) → retry with fresh state                │
│  6. Update account_snapshots (balance, last_event_seq)                       │
│  7. Publish event via outbox → Kafka                                         │
│                                                                              │
│  READ PATH (Hot — Balance Inquiry):                                          │
│  ══════════════════════════════════                                           │
│  1. Check Redis cache: balance:{tenant}:{account_id}                         │
│  2. Cache hit → return immediately (< 1ms)                                   │
│  3. Cache miss → read from account_snapshots table                           │
│  4. Populate cache (TTL: 60s)                                                │
│  5. Return balance                                                           │
│                                                                              │
│  READ PATH (Historical — Point-in-Time Balance):                             │
│  ════════════════════════════════════════════════                             │
│  1. Load most recent snapshot BEFORE requested timestamp                     │
│  2. Replay events from snapshot to requested timestamp                       │
│  3. Return reconstructed balance                                             │
│  Note: This is slower but enables regulatory temporal queries                │
│                                                                              │
│  SNAPSHOT STRATEGY:                                                          │
│  ═══════════════════                                                         │
│  • Snapshot created every 100 events per account                             │
│  • Also created at month-end (for fast period queries)                       │
│  • Snapshot contains: balance, timestamp, event_sequence                     │
│  • Old snapshots retained indefinitely (regulatory)                          │
│                                                                              │
│  CONSISTENCY:                                                                │
│  ════════════                                                                │
│  • Strong consistency within single account (optimistic locking)             │
│  • Cross-account consistency via atomic multi-row insert to ledger_events    │
│  • Journal entry affects multiple accounts atomically (single TX)            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Reconciliation

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    RECONCILIATION ARCHITECTURE                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  TYPES:                                                                      │
│                                                                              │
│  1. Internal Reconciliation (Daily):                                         │
│     • Verify: SUM(all debits) = SUM(all credits) for the day                │
│     • Verify: Account snapshot balance = replay(events) balance              │
│     • Verify: Trial balance (Assets + Expenses = Liabilities + Equity + Rev)│
│     • Automated Flink job; alert on any discrepancy > ₹1                    │
│                                                                              │
│  2. External Reconciliation (T+1):                                           │
│     • Match: Ledger payment entries vs Bank statement entries                │
│     • Source: Bank files (MT940/CAMT.053) ingested daily                     │
│     • Matching: Flink windowed join on reference + amount + date             │
│     • Unmatched items → investigation queue                                  │
│                                                                              │
│  3. Cross-System Reconciliation (Daily):                                     │
│     • LMS outstanding balance == Ledger principal account balance            │
│     • Payment service settled amount == Ledger settlement account            │
│     • Flink job compares projections from different sources                  │
│                                                                              │
│  DISCREPANCY HANDLING:                                                       │
│  • Auto-heal: Discrepancies < ₹1 (rounding) → auto-adjustment entry         │
│  • Alert: Discrepancies ₹1 - ₹1000 → P3 ticket                            │
│  • Escalate: Discrepancies > ₹1000 → P1 alert + immediate investigation    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Domain Events

| Event | Trigger | Downstream |
|-------|---------|-----------|
| AccountOpened | Loan created (LMS event) | Reporting |
| JournalEntryPosted | Any financial movement | Reporting, Analytics, Reconciliation |
| JournalEntryReversed | Error correction | Reporting, Analytics |
| BalanceSnapshotCreated | Every 100 events or month-end | Internal (cache warm) |
| ReconciliationCompleted | Daily recon job finishes | Audit, Dashboard |
| ReconciliationDiscrepancyDetected | Mismatch found | Alert, Investigation queue |
| AccountFrozen | Fraud/legal hold | LMS (block transactions) |
| TrialBalanceGenerated | Month-end closing | Regulatory reporting |

---

## 9. Performance Optimization

| Technique | Purpose | Impact |
|-----------|---------|--------|
| Monthly partitioning (ledger_events) | Efficient range queries by date | 10x faster historical queries |
| Covering indexes on account_snapshots | Balance lookup without heap access | Sub-ms reads |
| Redis caching of hot balances | Avoid DB round-trip for active accounts | < 1ms for 95% of reads |
| Batch event appends | Group multiple postings per TX | 5x throughput improvement |
| Async snapshot updates | Don't block write path for snapshot refresh | Lower write latency |
| Connection pooling (dedicated pool) | Isolated from other services' load | Predictable performance |
| Read replicas for reporting queries | Offload analytical queries from primary | Zero impact on writes |
