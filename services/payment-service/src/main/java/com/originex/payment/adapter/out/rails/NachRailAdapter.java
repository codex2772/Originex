package com.originex.payment.adapter.out.rails;

import com.originex.payment.application.port.out.PaymentRailPort;
import com.originex.payment.domain.model.PaymentOrder;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * NACH (National Automated Clearing House) adapter — for recurring EMI auto-debit.
 *
 * <p><b>SANDBOX MODE.</b> To go live:
 * <ol>
 *   <li>Integrate via NPCI NACH or a sponsor bank (SBI/HDFC/ICICI NACH API)</li>
 *   <li>Or use Digio/Razorpay/Cashfree e-NACH which handles NPCI integration</li>
 *   <li>NACH operates in T+1 settlement cycle</li>
 * </ol>
 */
@Component
public class NachRailAdapter implements PaymentRailPort {

    private static final Logger log = LoggerFactory.getLogger(NachRailAdapter.class);
    private final boolean sandboxMode;

    public NachRailAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    public PaymentOrder.PaymentRail rail() { return PaymentOrder.PaymentRail.NACH; }

    @Override
    @CircuitBreaker(name = "nachRail", fallbackMethod = "fallbackSubmit")
    public PaymentSubmissionResult submit(PaymentOrder order) {
        if (sandboxMode) {
            String txnRef = "NACH" + System.currentTimeMillis();
            log.info("[SANDBOX] NACH debit submitted: umrn={}, amount={}, ref={}",
                    order.getUmrn(), order.getAmount(), txnRef);
            return new PaymentSubmissionResult(true, txnRef, "NACH-CLG-" + txnRef.substring(4, 16), null);
        }
        throw new UnsupportedOperationException("NACH LIVE mode not configured");
    }

    @Override
    public PaymentStatusResult query(String paymentReference) {
        if (sandboxMode) return new PaymentStatusResult("SUCCESS", "NACH" + paymentReference.hashCode(), null, null);
        throw new UnsupportedOperationException("NACH LIVE mode not configured");
    }

    @SuppressWarnings("unused")
    private PaymentSubmissionResult fallbackSubmit(PaymentOrder order, Throwable t) {
        log.warn("NACH rail unavailable: {}", t.getMessage());
        return new PaymentSubmissionResult(false, null, null, "NACH service temporarily unavailable");
    }
}
