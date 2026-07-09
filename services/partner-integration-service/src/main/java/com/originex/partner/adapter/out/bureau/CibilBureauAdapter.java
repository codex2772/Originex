package com.originex.partner.adapter.out.bureau;

import com.originex.partner.application.port.out.CreditBureauPort;
import com.originex.partner.domain.model.BureauReport;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * TransUnion CIBIL adapter.
 *
 * <p><b>SANDBOX MODE:</b> This adapter currently simulates realistic CIBIL responses
 * deterministically from the PAN hash so tests are repeatable. To go live:
 * <ol>
 *   <li>Register with CIBIL as a Credit Institution / via an authorized reseller (e.g., CRIF Connect, Perfios)</li>
 *   <li>Obtain member credentials (Member ID, Password, FI Code) — store in Vault</li>
 *   <li>CIBIL API is XML-over-HTTPS (SOAP-like) — implement request/response XML mapping here</li>
 *   <li>Set {@code originex.partner.mode=LIVE} and {@code originex.partner.bureau.cibil.base-url}</li>
 * </ol>
 */
@Component
public class CibilBureauAdapter implements CreditBureauPort {

    private static final Logger log = LoggerFactory.getLogger(CibilBureauAdapter.class);

    private final boolean sandboxMode;

    public CibilBureauAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    public String bureauName() {
        return "CIBIL";
    }

    @Override
    @CircuitBreaker(name = "cibilBureau", fallbackMethod = "fallbackPullReport")
    @Retry(name = "cibilBureau")
    public BureauReport pullReport(BureauPullRequest request) {
        if (sandboxMode) {
            return simulateReport(request);
        }
        // TODO Phase 4: Real CIBIL XML API integration via authorized reseller
        throw new UnsupportedOperationException("CIBIL LIVE mode not yet configured — set credentials in Vault first");
    }

    @SuppressWarnings("unused")
    private BureauReport fallbackPullReport(BureauPullRequest request, Throwable t) {
        log.warn("CIBIL bureau unavailable, returning NO_HIT fallback: {}", t.getMessage());
        return BureauReport.notFound("CIBIL");
    }

    private BureauReport simulateReport(BureauPullRequest request) {
        int pseudoScore = 650 + Math.floorMod(request.panNumber().hashCode(), 250); // 650-900 range
        String risk = pseudoScore >= 750 ? "LOW" : pseudoScore >= 700 ? "MEDIUM" : "HIGH";

        log.info("[SANDBOX] CIBIL simulated pull: pan-hash-derived score={}", pseudoScore);

        return new BureauReport(
                "CIBIL", "CIBIL-SANDBOX-" + Math.abs(request.panNumber().hashCode()),
                pseudoScore, "CIBIL TransUnion Score 3.0", risk,
                Math.floorMod(request.panNumber().hashCode(), 4),
                Math.floorMod(request.panNumber().hashCode(), 3),
                "125000.0000", Math.floorMod(request.panNumber().hashCode(), 5),
                false, false, List.of(), Instant.now()
        );
    }
}
