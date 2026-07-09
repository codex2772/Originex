package com.originex.customer.adapter.out.rest;

import com.originex.customer.application.port.out.AadhaarVerificationPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Partner Integration Service adapter — Aadhaar e-KYC verification.
 */
@Component
public class PartnerIntegrationAadhaarAdapter implements AadhaarVerificationPort {

    private static final Logger log = LoggerFactory.getLogger(PartnerIntegrationAadhaarAdapter.class);

    private final RestClient restClient;

    public PartnerIntegrationAadhaarAdapter(
            @Value("${originex.partner-integration-service.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "partnerIntegrationAadhaar", fallbackMethod = "fallbackVerify")
    @Retry(name = "partnerIntegrationAadhaar")
    public AadhaarVerificationResult verify(AadhaarVerificationRequest request) {
        log.debug("Requesting Aadhaar eKYC: referenceId={}", request.referenceId());

        AadhaarResponse response = restClient.post()
                .uri("/v1/partner/aadhaar/verify")
                .header("X-Tenant-Id", request.tenantId())
                .body(new AadhaarRequestBody(request.referenceId(), request.aadhaarNumberOrVid(),
                        request.consentArtifactId(), request.otpReference()))
                .retrieve()
                .body(AadhaarResponse.class);

        if (response == null) {
            return new AadhaarVerificationResult(false, null, null, null, null, "Empty response from Partner Integration Service");
        }

        return new AadhaarVerificationResult(response.verified(), response.maskedAadhaar(),
                response.nameOnRecord(), response.dobOnRecord(),
                response.verificationReference(), response.failureReason());
    }

    @SuppressWarnings("unused")
    private AadhaarVerificationResult fallbackVerify(AadhaarVerificationRequest request, Throwable t) {
        log.warn("Partner Integration Service unavailable for Aadhaar eKYC: {}", t.getMessage());
        return new AadhaarVerificationResult(false, null, null, null, null,
                "Aadhaar eKYC service temporarily unavailable. Please retry.");
    }

    private record AadhaarRequestBody(String referenceId, String aadhaarNumberOrVid,
                                      String consentArtifactId, String otpReference) {}

    private record AadhaarResponse(boolean verified, String maskedAadhaar, String nameOnRecord,
                                   String dobOnRecord, String gender, String addressLine,
                                   String photoBase64, String verificationReference, String failureReason) {}
}
