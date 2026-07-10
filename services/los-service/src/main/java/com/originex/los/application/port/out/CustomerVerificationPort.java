package com.originex.los.application.port.out;

/**
 * Outbound port — verify customer eligibility via Customer Service.
 */
public interface CustomerVerificationPort {

    /**
     * Check if customer exists and has completed KYC.
     *
     * @return true if customer is eligible for loan application
     */
    CustomerEligibility verifyCustomerEligibility(String tenantId, String customerId);

    /**
     * Resolve the customer's primary bank account for disbursement.
     * @return the beneficiary details, or {@code null} if none is on file
     *         (or Customer Service is unavailable).
     */
    BeneficiaryAccount getPrimaryBankAccount(String tenantId, String customerId);

    record CustomerEligibility(
            boolean exists,
            boolean kycVerified,
            String customerName,
            String reason
    ) {
        public boolean isEligible() {
            return exists && kycVerified;
        }
    }

    record BeneficiaryAccount(
            String accountNumber,
            String ifscCode,
            String accountHolderName,
            String bankName
    ) {}
}
