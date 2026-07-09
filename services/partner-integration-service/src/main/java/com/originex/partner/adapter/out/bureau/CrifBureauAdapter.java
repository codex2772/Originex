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
 * CRIF High Mark adapter (REST/JSON API).
 *
 * <p><b>SANDBOX MODE.</b> To go live: onboard via CRIF High Mark member agreement,
 * implement REST client with API key + member code.
 */
@Component
public class CrifBureauAdapter implements CreditBureauPort {

    private static final Logger log = LoggerFactory.getLogger(CrifBureauAdapter.class);
    private final boolean sandboxMode;

    public CrifBureauAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    public String bureauName() {
        return "CRIF";
    }

    @Override
    @CircuitBreaker(name = "crifBureau", fallbackMethod = "fallbackPullReport")
    @Retry(name = "crifBureau")
    public BureauReport pullReport(BureauPullRequest request) {
        if (sandboxMode) {
            int pseudoScore = 640 + Math.floorMod(request.panNumber().hashCode(), 260);
            log.info("[SANDBOX] CRIF simulated pull: score={}", pseudoScore);
            return new BureauReport("CRIF", "CRIF-SANDBOX-" + Math.abs(request.panNumber().hashCode()),
                    pseudoScore, "CRIF Score V2",
                    pseudoScore >= 750 ? "LOW" : pseudoScore >= 700 ? "MEDIUM" : "HIGH",
                    Math.floorMod(request.panNumber().hashCode(), 3),
                    Math.floorMod(request.panNumber().hashCode(), 2),
                    "45000.0000", Math.floorMod(request.panNumber().hashCode(), 3),
                    false, false, List.of(), Instant.now());
        }
        throw new UnsupportedOperationException("CRIF LIVE mode not yet configured");
    }

    @SuppressWarnings("unused")
    private BureauReport fallbackPullReport(BureauPullRequest request, Throwable t) {
        log.warn("CRIF bureau unavailable, returning NO_HIT fallback: {}", t.getMessage());
        return BureauReport.notFound("CRIF");
    }
}
