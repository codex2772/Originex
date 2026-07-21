package com.originex.los.adapter.out.rest;

import com.originex.los.application.port.out.CustomerVerificationPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Customer Service adapter — calls Customer Service REST API.
 * Implements circuit breaker and retry for resilience.
 */
@Component
public class CustomerServiceAdapter implements CustomerVerificationPort {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceAdapter.class);

    private final RestClient restClient;

    public CustomerServiceAdapter(
            @Value("${originex.customer-service.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    @CircuitBreaker(name = "customerService", fallbackMethod = "fallbackVerify")
    @Retry(name = "customerService")
    public CustomerEligibility verifyCustomerEligibility(String tenantId, String customerId) {
        log.debug("Verifying customer eligibility: customerId={}", customerId);

        try {
            CustomerResponse response = restClient.get()
                    .uri("/v1/customers/{id}", customerId)
                    .header("X-Tenant-Id", tenantId)
                    .retrieve()
                    .body(CustomerResponse.class);

            if (response == null) {
                return new CustomerEligibility(false, false, null, "Customer not found");
            }

            boolean kycVerified = "VERIFIED".equals(response.kycStatus());
            String name = response.firstName() + " " + response.lastName();

            if (!kycVerified) {
                return new CustomerEligibility(true, false, name, "KYC not completed");
            }

            return new CustomerEligibility(true, true, name, null);

        } catch (Exception e) {
            log.error("Failed to verify customer: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Fallback when Customer Service is unavailable.
     * Rejects the application rather than allowing unverified customers.
     */
    @SuppressWarnings("unused")
    private CustomerEligibility fallbackVerify(String tenantId, String customerId, Throwable t) {
        log.warn("Customer service unavailable, rejecting eligibility check: {}", t.getMessage());
        return new CustomerEligibility(false, false, null,
                "Customer service temporarily unavailable. Please retry.");
    }

    @Override
    @CircuitBreaker(name = "customerService", fallbackMethod = "fallbackBankAccount")
    @Retry(name = "customerService")
    public BeneficiaryAccount getPrimaryBankAccount(String tenantId, String customerId) {
        try {
            BankAccountResponse resp = restClient.get()
                    .uri("/v1/customers/{id}/bank-accounts/primary", customerId)
                    .header("X-Tenant-Id", tenantId)
                    .retrieve()
                    .body(BankAccountResponse.class);
            if (resp == null) {
                return null;
            }
            return new BeneficiaryAccount(resp.accountNumber(), resp.ifscCode(),
                    resp.accountHolderName(), resp.bankName());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // No primary bank account on file — a normal case, not a failure.
            return null;
        }
    }

    @SuppressWarnings("unused")
    private BeneficiaryAccount fallbackBankAccount(String tenantId, String customerId, Throwable t) {
        log.warn("Customer service unavailable fetching bank account: {}", t.getMessage());
        return null;
    }

    private record CustomerResponse(
            String id,
            String firstName,
            String lastName,
            String status,
            String kycStatus
    ) {}

    private record BankAccountResponse(
            String accountNumber,
            String ifscCode,
            String accountHolderName,
            String bankName,
            boolean verified
    ) {}
}
