package com.originex.partner.adapter.out.kyc;

import com.originex.partner.application.port.out.AadhaarVerificationPort;
import com.originex.partner.domain.model.AadhaarVerificationResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Aadhaar e-KYC adapter — via DigiLocker (preferred, UIDAI-endorsed) or a
 * licensed AUA/KUA (Authentication User Agency / KYC User Agency).
 *
 * <p><b>SANDBOX MODE.</b> To go live:
 * <ol>
 *   <li>Become a UIDAI-licensed AUA/KUA, OR integrate via DigiLocker Partner API
 *       (simpler — no UIDAI license needed, uses OAuth2 + consent-based document pull)</li>
 *   <li>DigiLocker flow: redirect customer → they approve → we pull e-Aadhaar XML (signed by UIDAI)</li>
 *   <li>Verify UIDAI digital signature on the returned XML before trusting the data</li>
 *   <li>NEVER persist the raw Aadhaar number — only a salted hash + masked last 4 digits</li>
 * </ol>
 */
@Component
public class DigiLockerAadhaarAdapter implements AadhaarVerificationPort {

    private static final Logger log = LoggerFactory.getLogger(DigiLockerAadhaarAdapter.class);
    private final boolean sandboxMode;

    public DigiLockerAadhaarAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    @CircuitBreaker(name = "aadhaarEkyc", fallbackMethod = "fallbackVerify")
    @Retry(name = "aadhaarEkyc")
    public AadhaarVerificationResult verify(AadhaarVerificationRequest request) {
        if (sandboxMode) {
            String last4 = request.aadhaarNumberOrVid() != null && request.aadhaarNumberOrVid().length() >= 4
                    ? request.aadhaarNumberOrVid().substring(request.aadhaarNumberOrVid().length() - 4)
                    : "0000";
            log.info("[SANDBOX] DigiLocker Aadhaar simulated verification: maskedLast4={}", last4);

            return new AadhaarVerificationResult(
                    true, "XXXXXXXX" + last4,
                    "Sandbox Test User", "1990-01-01", "M",
                    "123, Sandbox Street, Test City, Test State - 500001",
                    null, // photo not simulated
                    "DIGILOCKER-SANDBOX-" + last4,
                    null
            );
        }
        // TODO Phase 4: Real DigiLocker OAuth2 flow + UIDAI XML signature verification
        throw new UnsupportedOperationException("DigiLocker LIVE mode not yet configured — register at partners.digitallocker.gov.in");
    }

    @SuppressWarnings("unused")
    private AadhaarVerificationResult fallbackVerify(AadhaarVerificationRequest request, Throwable t) {
        log.warn("Aadhaar eKYC unavailable: {}", t.getMessage());
        return AadhaarVerificationResult.failed("Service temporarily unavailable. Please retry.");
    }
}
