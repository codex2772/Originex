package com.originex.los.adapter.out.persistence;

import com.originex.common.money.Money;
import com.originex.los.domain.model.LoanOffer;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_offers")
public class LoanOfferJpaEntity {

    @Id
    @Column(name = "offer_id")
    private UUID offerId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    private LoanApplicationJpaEntity application;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "sanctioned_amount", nullable = false)
    private BigDecimal sanctionedAmount;

    @Column(name = "interest_rate", nullable = false)
    private BigDecimal interestRate;

    @Column(name = "tenure_months", nullable = false)
    private int tenureMonths;

    @Column(name = "emi", nullable = false)
    private BigDecimal emi;

    @Column(name = "processing_fee")
    private BigDecimal processingFee;

    @Column(name = "apr", nullable = false)
    private BigDecimal apr;

    @Column(name = "total_interest")
    private BigDecimal totalInterest;

    @Column(name = "total_repayment")
    private BigDecimal totalRepayment;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public static LoanOfferJpaEntity fromDomain(LoanOffer domain, LoanApplicationJpaEntity application) {
        LoanOfferJpaEntity e = new LoanOfferJpaEntity();
        e.offerId = domain.getOfferId();
        e.application = application;
        e.tenantId = application.getTenantId();
        e.sanctionedAmount = domain.getSanctionedAmount().getAmount();
        e.currency = domain.getSanctionedAmount().getCurrencyCode();
        e.interestRate = domain.getInterestRate();
        e.tenureMonths = domain.getTenureMonths();
        e.emi = domain.getEmi().getAmount();
        e.processingFee = domain.getProcessingFee().getAmount();
        e.apr = domain.getApr();
        e.totalInterest = domain.getTotalInterestPayable() != null ? domain.getTotalInterestPayable().getAmount() : null;
        e.totalRepayment = domain.getTotalRepayment() != null ? domain.getTotalRepayment().getAmount() : null;
        e.generatedAt = domain.getGeneratedAt();
        e.expiresAt = domain.getExpiresAt();
        return e;
    }

    public LoanOffer toDomain() {
        return LoanOffer.create(
                Money.of(sanctionedAmount, currency),
                interestRate,
                tenureMonths,
                Money.of(emi, currency),
                Money.of(processingFee, currency),
                apr,
                expiresAt
        );
    }

    protected LoanOfferJpaEntity() {}
}
