package com.originex.payment.adapter.out.rails;

import com.originex.payment.application.port.out.PaymentRailPort;
import com.originex.payment.domain.model.PaymentOrder;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * IMPS (Immediate Payment Service) adapter — instant 24x7 transfers up to ₹5 lakhs.
 * Preferred for urgent disbursements.
 */
@Component
public class ImpsRailAdapter implements PaymentRailPort {

    private static final Logger log = LoggerFactory.getLogger(ImpsRailAdapter.class);
    private final boolean sandboxMode;

    public ImpsRailAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    public PaymentOrder.PaymentRail rail() { return PaymentOrder.PaymentRail.IMPS; }

    @Override
    @CircuitBreaker(name = "impsRail", fallbackMethod = "fallbackSubmit")
    public PaymentSubmissionResult submit(PaymentOrder order) {
        if (sandboxMode) {
            String rrn = "IMPS" + System.currentTimeMillis();
            log.info("[SANDBOX] IMPS submitted: ref={}, amount={}, rrn={}", order.getPaymentReference(), order.getAmount(), rrn);
            return new PaymentSubmissionResult(true, rrn, "IMPS-BANK-" + rrn.substring(4, 16), null);
        }
        throw new UnsupportedOperationException("IMPS LIVE mode not configured");
    }

    @Override
    public PaymentStatusResult query(String paymentReference) {
        if (sandboxMode) return new PaymentStatusResult("SUCCESS", "IMPS" + paymentReference.hashCode(), null, null);
        throw new UnsupportedOperationException("IMPS LIVE mode not configured");
    }

    @SuppressWarnings("unused")
    private PaymentSubmissionResult fallbackSubmit(PaymentOrder order, Throwable t) {
        log.warn("IMPS rail unavailable: {}", t.getMessage());
        return new PaymentSubmissionResult(false, null, null, "IMPS service temporarily unavailable");
    }
}
