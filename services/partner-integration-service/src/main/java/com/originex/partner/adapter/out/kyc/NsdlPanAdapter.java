package com.originex.partner.adapter.out.kyc;

import com.originex.partner.application.port.out.PanVerificationPort;
import com.originex.partner.domain.model.PanVerificationResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * PAN verification adapter — NSDL / Protean e-Gov Technologies "PAN Verification API"
 * (or Income Tax Department's PAN verification service via an authorized GSP).
 *
 * <p><b>SANDBOX MODE.</b> To go live:
 * <ol>
 *   <li>Register as a "PAN verification user entity" with Protean (formerly NSDL e-Gov)</li>
 *   <li>Or integrate via an aggregator (Karza, Signzy, IDfy) who already holds NSDL access</li>
 *   <li>API returns: PAN status (E=Existing/Valid, D=Deleted, F=Fake...), name, aadhaar-seeding status</li>
 * </ol>
 */
@Component
public class NsdlPanAdapter implements PanVerificationPort {

    private static final Logger log = LoggerFactory.getLogger(NsdlPanAdapter.class);
    private static final Pattern PAN_FORMAT = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");

    private final boolean sandboxMode;

    public NsdlPanAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    @CircuitBreaker(name = "panVerification", fallbackMethod = "fallbackVerify")
    @Retry(name = "panVerification")
    public PanVerificationResult verify(PanVerificationRequest request) {
        // Format validation happens regardless of sandbox/live — it's free and instant
        if (request.panNumber() == null || !PAN_FORMAT.matcher(request.panNumber()).matches()) {
            return PanVerificationResult.invalid(request.panNumber(), "Invalid PAN format");
        }

        if (sandboxMode) {
            log.info("[SANDBOX] NSDL PAN simulated verification: pan={}", mask(request.panNumber()));
            // 4th character of PAN indicates holder type: P=Individual, C=Company, H=HUF, etc.
            char holderTypeChar = request.panNumber().charAt(3);
            String panType = switch (holderTypeChar) {
                case 'P' -> "INDIVIDUAL";
                case 'C' -> "COMPANY";
                case 'H' -> "HUF";
                case 'F' -> "FIRM";
                default -> "OTHER";
            };

            return new PanVerificationResult(
                    true, request.panNumber(), request.fullName(),
                    "ACTIVE", panType, true, "LINKED", null
            );
        }
        // TODO Phase 4: Real NSDL/Protean e-Gov API integration
        throw new UnsupportedOperationException("NSDL LIVE mode not yet configured");
    }

    @SuppressWarnings("unused")
    private PanVerificationResult fallbackVerify(PanVerificationRequest request, Throwable t) {
        log.warn("PAN verification unavailable: {}", t.getMessage());
        return PanVerificationResult.invalid(request.panNumber(), "Service temporarily unavailable. Please retry.");
    }

    private String mask(String pan) {
        if (pan == null || pan.length() < 4) return "****";
        return "*".repeat(pan.length() - 4) + pan.substring(pan.length() - 4);
    }
}
