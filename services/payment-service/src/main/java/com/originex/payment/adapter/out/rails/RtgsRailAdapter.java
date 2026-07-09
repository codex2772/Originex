package com.originex.payment.adapter.out.rails;

import com.originex.payment.application.port.out.PaymentRailPort;
import com.originex.payment.domain.model.PaymentOrder;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RTGS (Real Time Gross Settlement) adapter — for high-value transfers >= ₹2 lakhs.
 * Settles individually and instantly (during RBI RTGS hours: 7 AM–6 PM).
 */
@Component
public class RtgsRailAdapter implements PaymentRailPort {

    private static final Logger log = LoggerFactory.getLogger(RtgsRailAdapter.class);
    private final boolean sandboxMode;

    public RtgsRailAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    public PaymentOrder.PaymentRail rail() { return PaymentOrder.PaymentRail.RTGS; }

    @Override
    @CircuitBreaker(name = "rtgsRail", fallbackMethod = "fallbackSubmit")
    public PaymentSubmissionResult submit(PaymentOrder order) {
        if (sandboxMode) {
            String utr = "RTGS" + System.currentTimeMillis();
            log.info("[SANDBOX] RTGS submitted: ref={}, amount={}, utr={}", order.getPaymentReference(), order.getAmount(), utr);
            return new PaymentSubmissionResult(true, utr, "RTGS-BANK-" + utr.substring(4, 16), null);
        }
        throw new UnsupportedOperationException("RTGS LIVE mode not configured");
    }

    @Override
    public PaymentStatusResult query(String paymentReference) {
        if (sandboxMode) return new PaymentStatusResult("SUCCESS", "RTGS" + paymentReference.hashCode(), null, null);
        throw new UnsupportedOperationException("RTGS LIVE mode not configured");
    }

    @SuppressWarnings("unused")
    private PaymentSubmissionResult fallbackSubmit(PaymentOrder order, Throwable t) {
        log.warn("RTGS rail unavailable: {}", t.getMessage());
        return new PaymentSubmissionResult(false, null, null, "RTGS service temporarily unavailable");
    }
}
