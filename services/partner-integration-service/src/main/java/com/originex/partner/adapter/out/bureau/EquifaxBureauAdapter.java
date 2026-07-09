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
 * Equifax India adapter (SOAP/XML API).
 *
 * <p><b>SANDBOX MODE.</b> To go live: onboard via Equifax India member agreement,
 * implement SOAP client with WS-Security credentials.
 */
@Component
public class EquifaxBureauAdapter implements CreditBureauPort {

    private static final Logger log = LoggerFactory.getLogger(EquifaxBureauAdapter.class);
    private final boolean sandboxMode;

    public EquifaxBureauAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    public String bureauName() {
        return "EQUIFAX";
    }

    @Override
    @CircuitBreaker(name = "equifaxBureau", fallbackMethod = "fallbackPullReport")
    @Retry(name = "equifaxBureau")
    public BureauReport pullReport(BureauPullRequest request) {
        if (sandboxMode) {
            int pseudoScore = 620 + Math.floorMod(request.panNumber().hashCode(), 280);
            log.info("[SANDBOX] Equifax simulated pull: score={}", pseudoScore);
            return new BureauReport("EQUIFAX", "EQF-SANDBOX-" + Math.abs(request.panNumber().hashCode()),
                    pseudoScore, "Equifax Risk Score",
                    pseudoScore >= 750 ? "LOW" : pseudoScore >= 700 ? "MEDIUM" : "HIGH",
                    Math.floorMod(request.panNumber().hashCode(), 3),
                    Math.floorMod(request.panNumber().hashCode(), 2),
                    "60000.0000", Math.floorMod(request.panNumber().hashCode(), 3),
                    false, false, List.of(), Instant.now());
        }
        throw new UnsupportedOperationException("Equifax LIVE mode not yet configured");
    }

    @SuppressWarnings("unused")
    private BureauReport fallbackPullReport(BureauPullRequest request, Throwable t) {
        log.warn("Equifax bureau unavailable, returning NO_HIT fallback: {}", t.getMessage());
        return BureauReport.notFound("EQUIFAX");
    }
}
