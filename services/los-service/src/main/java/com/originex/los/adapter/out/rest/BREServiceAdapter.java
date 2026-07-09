package com.originex.los.adapter.out.rest;

import com.originex.los.application.port.out.BREPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

/**
 * BRE Service REST adapter.
 * Called synchronously by LOS during application processing.
 * Falls back to REFER_TO_UNDERWRITER if BRE is unavailable.
 */
@Component
public class BREServiceAdapter implements BREPort {

    private static final Logger log = LoggerFactory.getLogger(BREServiceAdapter.class);

    private final RestClient restClient;

    public BREServiceAdapter(@Value("${originex.bre-service.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    @CircuitBreaker(name = "breService", fallbackMethod = "fallbackEvaluate")
    @Retry(name = "breService")
    public BREResult evaluate(BRERequest request) {
        log.debug("Calling BRE: applicationId={}, product={}", request.applicationId(), request.productCode());

        BREResponseDto response = restClient.post()
                .uri("/v1/bre/evaluate")
                .header("X-Tenant-Id", request.tenantId().toString())
                .body(new BRERequestDto(
                        request.applicationId(), request.customerId(),
                        request.productCode(), request.employmentType(),
                        request.creditScore(), request.bureauName(),
                        request.hasWriteOff(), request.hasSettlement(),
                        request.enquiriesLast6Months(), request.activeLoansCount(),
                        request.existingEmiObligations().toPlainString(),
                        request.monthlyIncome().toPlainString(),
                        request.applicantAgeYears(),
                        request.requestedAmount().toPlainString(),
                        request.requestedTenureMonths(),
                        request.currency()
                ))
                .retrieve()
                .body(BREResponseDto.class);

        if (response == null) {
            return fallbackResult(request.applicationId());
        }

        return new BREResult(
                response.evaluationId(), response.decision(), response.riskGrade(), response.summary(),
                response.approvedAmount() != null ? new BigDecimal(response.approvedAmount()) : null,
                response.interestRate() != null ? new BigDecimal(response.interestRate()) : null,
                response.approvedTenureMonths(),
                response.emi() != null ? new BigDecimal(response.emi()) : null,
                response.processingFeeRate() != null ? new BigDecimal(response.processingFeeRate()) : null,
                response.apr() != null ? new BigDecimal(response.apr()) : null
        );
    }

    @SuppressWarnings("unused")
    private BREResult fallbackEvaluate(BRERequest request, Throwable t) {
        log.warn("BRE Service unavailable, referring to underwriter: applicationId={}, reason={}",
                request.applicationId(), t.getMessage());
        return fallbackResult(request.applicationId());
    }

    private BREResult fallbackResult(String applicationId) {
        return new BREResult(null, "REFER_TO_UNDERWRITER", "MEDIUM",
                "BRE service unavailable — referred for manual underwriting review",
                null, null, 0, null, null, null);
    }

    private record BRERequestDto(String applicationId, String customerId, String productCode,
                                  String employmentType, int creditScore, String bureauName,
                                  boolean hasWriteOff, boolean hasSettlement,
                                  int enquiriesLast6Months, int activeLoansCount,
                                  String existingEmiObligations, String monthlyIncome,
                                  int applicantAgeYears, String requestedAmount,
                                  int requestedTenureMonths, String currency) {}

    private record BREResponseDto(String evaluationId, String applicationId, String decision,
                                   String riskGrade, String summary, String approvedAmount,
                                   String interestRate, int approvedTenureMonths, String emi,
                                   String processingFeeRate, String apr) {}
}
