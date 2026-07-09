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
 * Experian India adapter (REST/JSON API).
 *
 * <p><b>SANDBOX MODE.</b> To go live: register at experian.in for B2B credit
 * information report access, obtain API key, implement REST client here.
 */
@Component
public class ExperianBureauAdapter implements CreditBureauPort {

    private static final Logger log = LoggerFactory.getLogger(ExperianBureauAdapter.class);
    private final boolean sandboxMode;

    public ExperianBureauAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    public String bureauName() {
        return "EXPERIAN";
    }

    @Override
    @CircuitBreaker(name = "experianBureau", fallbackMethod = "fallbackPullReport")
    @Retry(name = "experianBureau")
    public BureauReport pullReport(BureauPullRequest request) {
        if (sandboxMode) {
            int pseudoScore = 600 + Math.floorMod(request.panNumber().hashCode(), 300);
            log.info("[SANDBOX] Experian simulated pull: score={}", pseudoScore);
            return new BureauReport("EXPERIAN", "EXP-SANDBOX-" + Math.abs(request.panNumber().hashCode()),
                    pseudoScore, "Experian Credit Score V3",
                    pseudoScore >= 750 ? "LOW" : pseudoScore >= 700 ? "MEDIUM" : "HIGH",
                    Math.floorMod(request.panNumber().hashCode(), 3),
                    Math.floorMod(request.panNumber().hashCode(), 2),
                    "80000.0000", Math.floorMod(request.panNumber().hashCode(), 4),
                    false, false, List.of(), Instant.now());
        }
        throw new UnsupportedOperationException("Experian LIVE mode not yet configured");
    }

    @SuppressWarnings("unused")
    private BureauReport fallbackPullReport(BureauPullRequest request, Throwable t) {
        log.warn("Experian bureau unavailable, returning NO_HIT fallback: {}", t.getMessage());
        return BureauReport.notFound("EXPERIAN");
    }
}
