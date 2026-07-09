package com.originex.los.domain.model;

import com.originex.common.money.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Loan Offer — immutable once generated, has a validity period.
 * Contains all financial terms disclosed per RBI Digital Lending Guidelines.
 */
public class LoanOffer {

    private UUID offerId;
    private Money sanctionedAmount;
    private BigDecimal interestRate;     // Annual percentage
    private int tenureMonths;
    private Money emi;
    private Money processingFee;
    private BigDecimal apr;              // All-in APR (RBI requirement)
    private Money totalInterestPayable;
    private Money totalRepayment;
    private Instant generatedAt;
    private Instant expiresAt;

    public static LoanOffer create(Money sanctionedAmount, BigDecimal interestRate,
                                   int tenureMonths, Money emi, Money processingFee,
                                   BigDecimal apr, Instant expiresAt) {
        LoanOffer offer = new LoanOffer();
        offer.offerId = UUID.randomUUID();
        offer.sanctionedAmount = sanctionedAmount;
        offer.interestRate = interestRate;
        offer.tenureMonths = tenureMonths;
        offer.emi = emi;
        offer.processingFee = processingFee;
        offer.apr = apr;
        offer.totalInterestPayable = emi.multiply(BigDecimal.valueOf(tenureMonths)).subtract(sanctionedAmount);
        offer.totalRepayment = emi.multiply(BigDecimal.valueOf(tenureMonths));
        offer.generatedAt = Instant.now();
        offer.expiresAt = expiresAt;
        return offer;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    // Accessors
    public UUID getOfferId() { return offerId; }
    public Money getSanctionedAmount() { return sanctionedAmount; }
    public BigDecimal getInterestRate() { return interestRate; }
    public int getTenureMonths() { return tenureMonths; }
    public Money getEmi() { return emi; }
    public Money getProcessingFee() { return processingFee; }
    public BigDecimal getApr() { return apr; }
    public Money getTotalInterestPayable() { return totalInterestPayable; }
    public Money getTotalRepayment() { return totalRepayment; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public LoanOffer() {}
}
