package com.originex.customer.application.port.in;

import com.originex.customer.domain.model.Customer;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Inbound port — Customer management use cases.
 */
public interface CustomerUseCase {

    Customer registerCustomer(RegisterCustomerCommand command);

    Customer getCustomer(UUID tenantId, UUID customerId);

    Customer updateProfile(UpdateProfileCommand command);

    Customer submitKyc(SubmitKycCommand command);

    Customer completeKyc(UUID tenantId, UUID customerId, UUID kycRecordId);

    /**
     * Triggers a live Aadhaar e-KYC verification via the Partner Integration
     * Service (DigiLocker/UIDAI). On success, automatically completes KYC.
     */
    Customer initiateAadhaarEkyc(InitiateAadhaarEkycCommand command);

    Customer addBankAccount(AddBankAccountCommand command);

    Customer verifyBankAccount(UUID tenantId, UUID customerId, UUID bankAccountId);

    // ─── Commands ───

    record RegisterCustomerCommand(
            UUID tenantId,
            String firstName,
            String lastName,
            String email,
            String phone,
            LocalDate dateOfBirth,
            String panNumber,       // Will be encrypted before storage
            String aadhaarNumber    // Will be tokenized (hashed) before storage
    ) {}

    record UpdateProfileCommand(
            UUID tenantId,
            UUID customerId,
            String firstName,
            String lastName,
            String email,
            LocalDate dateOfBirth,
            long expectedVersion
    ) {}

    record SubmitKycCommand(
            UUID tenantId,
            UUID customerId,
            String kycType,
            String verificationReference
    ) {}

    record InitiateAadhaarEkycCommand(
            UUID tenantId,
            UUID customerId,
            String aadhaarNumberOrVid,
            String consentArtifactId,
            String otpReference
    ) {}

    record AddBankAccountCommand(
            UUID tenantId,
            UUID customerId,
            String accountNumber,
            String ifscCode,
            String bankName,
            String accountHolderName,
            String accountType,
            boolean setPrimary
    ) {}
}
