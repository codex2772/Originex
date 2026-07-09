package com.originex.customer.adapter.out.rest;

import com.originex.customer.application.port.out.BankAccountVerificationPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Partner Integration Service adapter — bank account (penny-drop) verification.
 */
@Component
public class PartnerIntegrationBankAdapter implements BankAccountVerificationPort {

    private static final Logger log = LoggerFactory.getLogger(PartnerIntegrationBankAdapter.class);

    private final RestClient restClient;

    public PartnerIntegrationBankAdapter(
            @Value("${originex.partner-integration-service.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "partnerIntegrationBank", fallbackMethod = "fallbackVerify")
    @Retry(name = "partnerIntegrationBank")
    public BankAccountVerificationResult verify(BankAccountVerificationRequest request) {
        log.debug("Requesting bank account verification: referenceId={}", request.referenceId());

        BankResponse response = restClient.post()
                .uri("/v1/partner/bank-account/verify")
                .header("X-Tenant-Id", request.tenantId())
                .body(new BankRequestBody(request.referenceId(), request.accountNumber(),
                        request.ifscCode(), request.expectedAccountHolderName()))
                .retrieve()
                .body(BankResponse.class);

        if (response == null) {
            return new BankAccountVerificationResult(false, null, false, "Empty response from Partner Integration Service");
        }

        return new BankAccountVerificationResult(response.verified(), response.bankName(),
                response.nameMatch(), response.failureReason());
    }

    @SuppressWarnings("unused")
    private BankAccountVerificationResult fallbackVerify(BankAccountVerificationRequest request, Throwable t) {
        log.warn("Partner Integration Service unavailable for bank verification: {}", t.getMessage());
        return new BankAccountVerificationResult(false, null, false,
                "Bank account verification service temporarily unavailable. Please retry.");
    }

    private record BankRequestBody(String referenceId, String accountNumber, String ifscCode,
                                   String expectedAccountHolderName) {}

    private record BankResponse(boolean verified, String accountNumberMasked, String ifscCode,
                                String bankName, String branchName, String nameOnAccount,
                                boolean nameMatch, String accountStatus, String utrReference,
                                String failureReason) {}
}
