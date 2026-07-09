package com.originex.lms.domain.model;

import com.originex.common.money.Money;

import java.time.Instant;
import java.util.UUID;

public class LoanCharge {

    private UUID chargeId;
    private Money amount;
    private String chargeType;  // LATE_FEE, PROCESSING_FEE, PREPAYMENT_FEE, BOUNCE_CHARGE
    private Money paidAmount;
    private ChargeStatus status;
    private Instant leviedAt;

    public static LoanCharge create(Money amount, String chargeType) {
        LoanCharge c = new LoanCharge();
        c.chargeId = UUID.randomUUID();
        c.amount = amount;
        c.chargeType = chargeType;
        c.paidAmount = Money.zero(amount.getCurrencyCode());
        c.status = ChargeStatus.PENDING;
        c.leviedAt = Instant.now();
        return c;
    }

    public UUID getChargeId() { return chargeId; }
    public Money getAmount() { return amount; }
    public String getChargeType() { return chargeType; }
    public ChargeStatus getStatus() { return status; }

    public LoanCharge() {}

    public enum ChargeStatus { PENDING, PAID, WAIVED }
}
