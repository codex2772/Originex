package com.originex.lms.domain.model;

import com.originex.common.money.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Loan Aggregate Root — manages the entire post-disbursement lifecycle.
 *
 * <p>Key invariants:
 * <ul>
 *   <li>Total disbursed cannot exceed sanctioned amount</li>
 *   <li>Repayment waterfall: Charges → Penal → Interest → Principal</li>
 *   <li>Only one active schedule version at a time</li>
 *   <li>NPA classification at 90+ DPD</li>
 * </ul>
 */
public class Loan {

    private UUID loanId;
    private UUID tenantId;
    private UUID customerId;
    private UUID applicationId;
    private String productCode;
    private String loanAccountNumber;
    private LoanStatus status;

    // Financial terms
    private Money sanctionedAmount;
    private Money disbursedAmount;
    private Money outstandingPrincipal;
    private Money outstandingInterest;
    private Money outstandingCharges;
    private BigDecimal interestRate;
    private String rateType; // FIXED, FLOATING
    private int tenureMonths;
    private int remainingTenure;
    private Money emiAmount;
    private String currency;

    // Dates
    private LocalDate sanctionDate;
    private LocalDate firstDisbursementDate;
    private LocalDate maturityDate;
    private LocalDate nextDueDate;
    private LocalDate lastPaymentDate;
    private LocalDate lastAccrualDate; // Date through which interest has been accrued (null until first disbursement)

    // Delinquency
    private int dpd;
    private int maxDpd;
    private String assetClassification; // STANDARD, SUB_STANDARD, DOUBTFUL, LOSS

    // Child entities
    private List<Installment> installments;
    private List<Disbursement> disbursements;
    private List<LoanCharge> charges;

    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    // ═══════════════════════════════════════════════════════════════════
    // Factory — creates loan from approved application (DisbursementRequested event)
    // ═══════════════════════════════════════════════════════════════════

    public static Loan createFromApplication(UUID tenantId, UUID customerId, UUID applicationId,
                                             String productCode, Money sanctionedAmount,
                                             BigDecimal interestRate, String rateType,
                                             int tenureMonths, Money emiAmount) {
        Loan loan = new Loan();
        loan.loanId = UUID.randomUUID();
        loan.tenantId = tenantId;
        loan.customerId = customerId;
        loan.applicationId = applicationId;
        loan.productCode = productCode;
        loan.loanAccountNumber = generateAccountNumber();
        loan.status = LoanStatus.CREATED;
        loan.sanctionedAmount = sanctionedAmount;
        loan.disbursedAmount = Money.zero(sanctionedAmount.getCurrencyCode());
        loan.outstandingPrincipal = Money.zero(sanctionedAmount.getCurrencyCode());
        loan.outstandingInterest = Money.zero(sanctionedAmount.getCurrencyCode());
        loan.outstandingCharges = Money.zero(sanctionedAmount.getCurrencyCode());
        loan.interestRate = interestRate;
        loan.rateType = rateType;
        loan.tenureMonths = tenureMonths;
        loan.remainingTenure = tenureMonths;
        loan.emiAmount = emiAmount;
        loan.currency = sanctionedAmount.getCurrencyCode();
        loan.sanctionDate = LocalDate.now();
        loan.dpd = 0;
        loan.maxDpd = 0;
        loan.assetClassification = "STANDARD";
        loan.installments = new ArrayList<>();
        loan.disbursements = new ArrayList<>();
        loan.charges = new ArrayList<>();
        loan.version = 0;
        loan.createdAt = Instant.now();
        loan.updatedAt = Instant.now();
        return loan;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Domain Behavior
    // ═══════════════════════════════════════════════════════════════════

    public void initiateDisbursement(Money amount, String beneficiaryAccount) {
        if (amount.add(disbursedAmount).isGreaterThan(sanctionedAmount)) {
            throw new IllegalStateException("Disbursement would exceed sanctioned amount");
        }
        transitionTo(LoanStatus.PENDING_DISBURSAL);

        Disbursement disb = Disbursement.create(amount, beneficiaryAccount);
        this.disbursements.add(disb);
        this.updatedAt = Instant.now();
    }

    public void confirmDisbursement(UUID disbursementId, String paymentReference) {
        Disbursement disb = findDisbursement(disbursementId);
        disb.confirm(paymentReference);

        this.disbursedAmount = this.disbursedAmount.add(disb.getAmount());
        this.outstandingPrincipal = this.outstandingPrincipal.add(disb.getAmount());
        this.status = LoanStatus.ACTIVE;

        if (this.firstDisbursementDate == null) {
            this.firstDisbursementDate = LocalDate.now();
            this.maturityDate = this.firstDisbursementDate.plusMonths(this.tenureMonths);
            // Interest accrual starts from the activation (first disbursement) date.
            this.lastAccrualDate = this.firstDisbursementDate;
        }
        this.updatedAt = Instant.now();
    }

    /**
     * Apply repayment using waterfall logic: Charges → Penal Interest → Interest → Principal
     */
    public RepaymentAllocation allocateRepayment(Money paymentAmount) {
        assertActive();
        Objects.requireNonNull(paymentAmount, "Payment amount required");
        if (!paymentAmount.isPositive()) {
            throw new IllegalArgumentException("Payment must be positive");
        }

        Money remaining = paymentAmount;
        Money chargesAllocated = Money.zero(currency);
        Money interestAllocated = Money.zero(currency);
        Money principalAllocated = Money.zero(currency);
        Money excess = Money.zero(currency);

        // Step 1: Allocate to charges
        if (remaining.isPositive() && outstandingCharges.isPositive()) {
            Money chargesPortion = remaining.min(outstandingCharges);
            chargesAllocated = chargesPortion;
            outstandingCharges = outstandingCharges.subtract(chargesPortion);
            remaining = remaining.subtract(chargesPortion);
        }

        // Step 2: Allocate to interest
        if (remaining.isPositive() && outstandingInterest.isPositive()) {
            Money interestPortion = remaining.min(outstandingInterest);
            interestAllocated = interestPortion;
            outstandingInterest = outstandingInterest.subtract(interestPortion);
            remaining = remaining.subtract(interestPortion);
        }

        // Step 3: Allocate to principal
        if (remaining.isPositive() && outstandingPrincipal.isPositive()) {
            Money principalPortion = remaining.min(outstandingPrincipal);
            principalAllocated = principalPortion;
            outstandingPrincipal = outstandingPrincipal.subtract(principalPortion);
            remaining = remaining.subtract(principalPortion);
        }

        // Step 4: Excess
        if (remaining.isPositive()) {
            excess = remaining;
        }

        this.lastPaymentDate = LocalDate.now();

        // Settle the amortization schedule oldest-installment-first with the
        // interest + principal actually allocated, and advance the next due date.
        settleSchedule(interestAllocated.add(principalAllocated));

        this.updatedAt = Instant.now();

        // Check if loan is fully paid
        if (outstandingPrincipal.isZero() && outstandingInterest.isZero()) {
            this.status = LoanStatus.MATURED;
        }

        return new RepaymentAllocation(
                chargesAllocated, interestAllocated, principalAllocated, excess,
                outstandingPrincipal, outstandingInterest, outstandingCharges
        );
    }

    /**
     * Adds accrued interest to the outstanding interest balance.
     *
     * <p><b>v1 scope (Interest Accrual):</b> the daily accrual scheduler only
     * feeds this method for loans in {@code ACTIVE} status (enforced by the
     * accrual eligibility query, not by {@link #assertActive()} — which also
     * permits {@code NPA} for repayment). NPA interest is therefore <b>not</b>
     * accrued in v1.
     *
     * <p><b>Deferred (future) — NPA / regulatory interest treatment:</b> RBI
     * norms require that once a loan is classified NPA, interest income
     * recognition stops but interest may still accrue into an interest-suspense
     * / memorandum account (not recognised as income). Implementing that will
     * require: interest-suspense accounts, separate ledger postings, and
     * reversal/reclassification logic on NPA transition and on cure. This is
     * intentionally out of scope for v1 and must be revisited before NPA loans
     * are handled for income recognition.
     */
    public void accrueInterest(Money accruedAmount) {
        assertActive();
        this.outstandingInterest = this.outstandingInterest.add(accruedAmount);
        this.updatedAt = Instant.now();
    }

    public void levyCharge(Money chargeAmount, String chargeType) {
        assertActive();
        this.outstandingCharges = this.outstandingCharges.add(chargeAmount);
        this.charges.add(LoanCharge.create(chargeAmount, chargeType));
        this.updatedAt = Instant.now();
    }

    /**
     * Days-past-due as of {@code asOf}: calendar days since the oldest unpaid
     * installment's due date ({@link #nextDueDate}, which repayment advances to
     * the oldest not-fully-paid installment). Zero when nothing is due yet or the
     * schedule is absent. The caller supplies {@code asOf} in the business zone.
     */
    public int calculateDpd(LocalDate asOf) {
        if (nextDueDate == null || !asOf.isAfter(nextDueDate)) {
            return 0;
        }
        return (int) java.time.temporal.ChronoUnit.DAYS.between(nextDueDate, asOf);
    }

    public void updateDpd(int newDpd) {
        this.dpd = newDpd;
        if (newDpd > this.maxDpd) this.maxDpd = newDpd;

        // NPA classification per RBI norms
        if (newDpd >= 90 && this.status == LoanStatus.ACTIVE) {
            this.status = LoanStatus.NPA;
            this.assetClassification = "SUB_STANDARD";
        }
        if (newDpd >= 365) this.assetClassification = "DOUBTFUL";
        if (newDpd >= 730) this.assetClassification = "LOSS";

        this.updatedAt = Instant.now();
    }

    public void foreclose(Money foreclosureAmount) {
        assertActive();
        this.outstandingPrincipal = Money.zero(currency);
        this.outstandingInterest = Money.zero(currency);
        this.outstandingCharges = Money.zero(currency);
        transitionTo(LoanStatus.FORECLOSED);
    }

    public void setSchedule(List<Installment> newInstallments) {
        this.installments = new ArrayList<>(newInstallments);
        if (!newInstallments.isEmpty()) {
            this.nextDueDate = newInstallments.get(0).getDueDate();
        }
        this.updatedAt = Instant.now();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Distributes {@code amount} across the schedule oldest-installment-first
     * (each installment interest-due then principal-due), then advances
     * {@code nextDueDate} to the oldest installment not yet fully paid. Leaves
     * {@code nextDueDate} unchanged when the schedule is absent (a loan loaded
     * without its children must not be settled) or fully paid.
     */
    private void settleSchedule(Money amount) {
        if (installments == null || installments.isEmpty() || !amount.isPositive()) {
            return;
        }
        List<Installment> ordered = installments.stream()
                .sorted(Comparator.comparingInt(Installment::getInstallmentNumber))
                .toList();

        Money remaining = amount;
        for (Installment inst : ordered) {
            if (!remaining.isPositive()) break;
            if (inst.isFullyPaid()) continue;
            remaining = remaining.subtract(inst.applyPayment(remaining));
        }

        ordered.stream()
                .filter(i -> !i.isFullyPaid())
                .map(Installment::getDueDate)
                .findFirst()
                .ifPresent(d -> this.nextDueDate = d);
    }

    private void transitionTo(LoanStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Invalid transition: " + status + " → " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    private void assertActive() {
        if (this.status != LoanStatus.ACTIVE && this.status != LoanStatus.NPA) {
            throw new IllegalStateException("Loan is not active: " + this.status);
        }
    }

    private Disbursement findDisbursement(UUID id) {
        return disbursements.stream().filter(d -> d.getDisbursementId().equals(id))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Disbursement not found: " + id));
    }

    private static String generateAccountNumber() {
        // Format: OX + 12 random digits
        return "OX" + String.format("%012d", (long) (Math.random() * 1_000_000_000_000L));
    }

    public Money getTotalOutstanding() {
        return outstandingPrincipal.add(outstandingInterest).add(outstandingCharges);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Accessors
    // ═══════════════════════════════════════════════════════════════════

    public UUID getLoanId() { return loanId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getCustomerId() { return customerId; }
    public UUID getApplicationId() { return applicationId; }
    public String getProductCode() { return productCode; }
    public String getLoanAccountNumber() { return loanAccountNumber; }
    public LoanStatus getStatus() { return status; }
    public Money getSanctionedAmount() { return sanctionedAmount; }
    public Money getDisbursedAmount() { return disbursedAmount; }
    public Money getOutstandingPrincipal() { return outstandingPrincipal; }
    public Money getOutstandingInterest() { return outstandingInterest; }
    public Money getOutstandingCharges() { return outstandingCharges; }
    public BigDecimal getInterestRate() { return interestRate; }
    public String getRateType() { return rateType; }
    public int getTenureMonths() { return tenureMonths; }
    public int getRemainingTenure() { return remainingTenure; }
    public Money getEmiAmount() { return emiAmount; }
    public String getCurrency() { return currency; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public LocalDate getNextDueDate() { return nextDueDate; }
    public LocalDate getLastPaymentDate() { return lastPaymentDate; }
    public LocalDate getLastAccrualDate() { return lastAccrualDate; }
    public int getDpd() { return dpd; }
    public int getMaxDpd() { return maxDpd; }
    public String getAssetClassification() { return assetClassification; }
    public List<Installment> getInstallments() { return installments; }
    public List<Disbursement> getDisbursements() { return disbursements; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public Loan() {}
    public void setLoanId(UUID id) { this.loanId = id; }
    public void setTenantId(UUID id) { this.tenantId = id; }
    public void setCustomerId(UUID id) { this.customerId = id; }
    public void setApplicationId(UUID id) { this.applicationId = id; }
    public void setProductCode(String s) { this.productCode = s; }
    public void setLoanAccountNumber(String s) { this.loanAccountNumber = s; }
    public void setStatus(LoanStatus s) { this.status = s; }
    public void setSanctionedAmount(Money m) { this.sanctionedAmount = m; }
    public void setDisbursedAmount(Money m) { this.disbursedAmount = m; }
    public void setOutstandingPrincipal(Money m) { this.outstandingPrincipal = m; }
    public void setOutstandingInterest(Money m) { this.outstandingInterest = m; }
    public void setOutstandingCharges(Money m) { this.outstandingCharges = m; }
    public void setInterestRate(BigDecimal r) { this.interestRate = r; }
    public void setRateType(String s) { this.rateType = s; }
    public void setTenureMonths(int i) { this.tenureMonths = i; }
    public void setRemainingTenure(int i) { this.remainingTenure = i; }
    public void setEmiAmount(Money m) { this.emiAmount = m; }
    public void setCurrency(String s) { this.currency = s; }
    public void setMaturityDate(LocalDate d) { this.maturityDate = d; }
    public void setNextDueDate(LocalDate d) { this.nextDueDate = d; }
    public void setLastPaymentDate(LocalDate d) { this.lastPaymentDate = d; }
    public void setLastAccrualDate(LocalDate d) { this.lastAccrualDate = d; }
    public void setDpd(int i) { this.dpd = i; }
    public void setMaxDpd(int i) { this.maxDpd = i; }
    public void setAssetClassification(String s) { this.assetClassification = s; }
    public void setInstallments(List<Installment> l) { this.installments = l; }
    public void setDisbursements(List<Disbursement> l) { this.disbursements = l; }
    public void setCharges(List<LoanCharge> l) { this.charges = l; }
    public void setVersion(long v) { this.version = v; }
    public void setCreatedAt(Instant i) { this.createdAt = i; }
    public void setUpdatedAt(Instant i) { this.updatedAt = i; }

    // ─── Inner record for repayment result ───
    public record RepaymentAllocation(
            Money chargesAllocated,
            Money interestAllocated,
            Money principalAllocated,
            Money excess,
            Money outstandingPrincipalAfter,
            Money outstandingInterestAfter,
            Money outstandingChargesAfter
    ) {}
}
