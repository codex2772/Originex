package com.originex.los.adapter.out.rest;

import com.originex.los.application.port.out.CreditBureauPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Partner Integration Service adapter — requests credit bureau pulls.
 * This is the Anti-Corruption Layer boundary: LOS's domain never sees
 * bureau-specific formats, only the normalized {@link CreditBureauPort.BureauCheckResult}.
 */
@Component
public class PartnerIntegrationCreditBureauAdapter implements CreditBureauPort {

    private static final Logger log = LoggerFactory.getLogger(PartnerIntegrationCreditBureauAdapter.class);

    private final RestClient restClient;

    public PartnerIntegrationCreditBureauAdapter(
            @Value("${originex.partner-integration-service.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    @CircuitBreaker(name = "partnerIntegrationBureau", fallbackMethod = "fallbackPullReport")
    @Retry(name = "partnerIntegrationBureau")
    public BureauCheckResult pullCreditReport(CreditCheckRequest request) {
        log.debug("Requesting credit bureau pull: applicationId={}", request.applicationId());

        BureauReportResponse response = restClient.post()
                .uri("/v1/partner/credit-bureau/pull")
                .header("X-Tenant-Id", request.tenantId())
                .body(new PullBureauRequestBody(
                        request.applicationId(), null, // null preferredBureau = use default routing
                        request.panNumber(), request.fullName(), request.dateOfBirth(),
                        request.phone(), request.consentArtifactId()
                ))
                .retrieve()
                .body(BureauReportResponse.class);

        if (response == null || response.creditScore() < 0) {
            return new BureauCheckResult(false, response != null ? response.bureauName() : "UNKNOWN",
                    null, 0, "NO_HIT", "No credit report found");
        }

        return new BureauCheckResult(true, response.bureauName(), response.reportReference(),
                response.creditScore(), response.riskGrade(), null);
    }

    @SuppressWarnings("unused")
    private BureauCheckResult fallbackPullReport(CreditCheckRequest request, Throwable t) {
        log.warn("Partner Integration Service unavailable, cannot pull credit report: {}", t.getMessage());
        return new BureauCheckResult(false, "UNKNOWN", null, 0, "UNKNOWN",
                "Credit bureau service temporarily unavailable. Please retry.");
    }

    private record PullBureauRequestBody(
            String referenceId, String preferredBureau, String panNumber,
            String fullName, String dateOfBirth, String phone, String consentArtifactId
    ) {}

    private record BureauReportResponse(
            String bureauName, String reportReference, int creditScore,
            String scoreVersion, String riskGrade, int activeLoanCount,
            int activeCreditCardCount, String totalOutstandingAmount,
            int enquiriesLast6Months, boolean hasWriteOff, boolean hasSettlement
    ) {}
}
