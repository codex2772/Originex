package com.originex.customer.adapter.out.rest;

import com.originex.customer.application.port.out.PanVerificationPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Partner Integration Service adapter — PAN verification.
 * Anti-Corruption Layer boundary: Customer Service domain never sees NSDL's format.
 */
@Component
public class PartnerIntegrationPanAdapter implements PanVerificationPort {

    private static final Logger log = LoggerFactory.getLogger(PartnerIntegrationPanAdapter.class);

    private final RestClient restClient;

    public PartnerIntegrationPanAdapter(
            @Value("${originex.partner-integration-service.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "partnerIntegrationPan", fallbackMethod = "fallbackVerify")
    @Retry(name = "partnerIntegrationPan")
    public PanVerificationResult verify(PanVerificationRequest request) {
        log.debug("Requesting PAN verification: referenceId={}", request.referenceId());

        PanResponse response = restClient.post()
                .uri("/v1/partner/pan/verify")
                .header("X-Tenant-Id", request.tenantId())
                .body(new PanRequestBody(request.referenceId(), request.panNumber(),
                        request.fullName(), request.dateOfBirth()))
                .retrieve()
                .body(PanResponse.class);

        if (response == null) {
            return new PanVerificationResult(false, null, "UNKNOWN", false, "Empty response from Partner Integration Service");
        }

        return new PanVerificationResult(response.valid(), response.nameOnRecord(),
                response.panStatus(), response.nameMatch(), response.failureReason());
    }

    @SuppressWarnings("unused")
    private PanVerificationResult fallbackVerify(PanVerificationRequest request, Throwable t) {
        log.warn("Partner Integration Service unavailable for PAN verification: {}", t.getMessage());
        return new PanVerificationResult(false, null, "UNKNOWN", false,
                "PAN verification service temporarily unavailable. Please retry.");
    }

    private record PanRequestBody(String referenceId, String panNumber, String fullName, String dateOfBirth) {}

    private record PanResponse(boolean valid, String panNumber, String nameOnRecord, String panStatus,
                               String panType, boolean nameMatch, String aadhaarSeedingStatus, String failureReason) {}
}
