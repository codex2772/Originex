package com.originex.payment.adapter.out.rails;

import com.originex.payment.application.port.out.PaymentRailPort;
import com.originex.payment.domain.model.PaymentOrder;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * NEFT (National Electronic Funds Transfer) adapter.
 *
 * <p><b>SANDBOX MODE</b>. To go live:
 * <ol>
 *   <li>Integrate via banking partner API (e.g., ICICI Corporate API, HDFC SmartPay,
 *       or a payment aggregator like Cashfree Payouts / Razorpay X)</li>
 *   <li>NEFT settles in hourly batches (6 AM–8 PM) — async confirmation via webhook</li>
 *   <li>Store API credentials in Vault at: secret/payment/neft</li>
 * </ol>
 */
@Component
public class NeftRailAdapter implements PaymentRailPort {

    private static final Logger log = LoggerFactory.getLogger(NeftRailAdapter.class);
    private final boolean sandboxMode;

    public NeftRailAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    public PaymentOrder.PaymentRail rail() { return PaymentOrder.PaymentRail.NEFT; }

    @Override
    @CircuitBreaker(name = "neftRail", fallbackMethod = "fallbackSubmit")
    @Retry(name = "neftRail")
    public PaymentSubmissionResult submit(PaymentOrder order) {
        if (sandboxMode) {
            String utr = "NEFT" + System.currentTimeMillis();
            log.info("[SANDBOX] NEFT submitted: ref={}, amount={}, utr={}", order.getPaymentReference(), order.getAmount(), utr);
            return new PaymentSubmissionResult(true, utr, "NEFT-BANK-" + utr.substring(4, 16), null);
        }
        throw new UnsupportedOperationException("NEFT LIVE mode not configured — add banking partner credentials to Vault");
    }

    @Override
    public PaymentStatusResult query(String paymentReference) {
        if (sandboxMode) {
            return new PaymentStatusResult("SUCCESS", "NEFT" + paymentReference.hashCode(), null, null);
        }
        throw new UnsupportedOperationException("NEFT LIVE mode not configured");
    }

    @SuppressWarnings("unused")
    private PaymentSubmissionResult fallbackSubmit(PaymentOrder order, Throwable t) {
        log.warn("NEFT rail unavailable: {}", t.getMessage());
        return new PaymentSubmissionResult(false, null, null, "NEFT service temporarily unavailable");
    }
}
