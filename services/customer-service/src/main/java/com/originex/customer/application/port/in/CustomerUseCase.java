package com.originex.customer.application.port.in;

import com.originex.customer.domain.model.Customer;
import com.originex.starter.security.OriginexScopes;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Inbound port — Customer management use cases.
 *
 * <p><b>Authorization boundary</b> (see {@code dev/AUTH_DESIGN.md} §4.2, §4.5).
 * Capability checks live here, on the use-case port, rather than on controllers,
 * so every inbound adapter (REST today, others later) passes through the same
 * gate. The required scopes come from the platform catalog
 * ({@link OriginexScopes}), keeping the IdP's client scopes, the {@code SCOPE_}
 * authorities minted from the token, and these expressions in sync:
 * reads require {@code customers:read}, writes {@code customers:write}.
 *
 * <p>Enforced only when {@code originex.security.enabled=true} — method security
 * is contributed by the gated starter configuration, so with security disabled
 * (the default) these annotations are inert and behaviour is unchanged.
 *
 * <p><b>Not covered here:</b> ownership (a {@code CUSTOMER} principal may act only
 * on its own record) is a separate layer, deliberately not applied yet — see
 * {@code dev/AUTH_DESIGN.md} §4.2 (decision D2).
 */
public interface CustomerUseCase {

    /** Capability guard for reads. A compile-time constant, so it is usable in annotations. */
    String REQUIRES_CUSTOMERS_READ =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.CUSTOMERS_READ + "')";

    /** Capability guard for writes. */
    String REQUIRES_CUSTOMERS_WRITE =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.CUSTOMERS_WRITE + "')";

    @PreAuthorize(REQUIRES_CUSTOMERS_WRITE)
    Customer registerCustomer(RegisterCustomerCommand command);

    @PreAuthorize(REQUIRES_CUSTOMERS_READ)
    Customer getCustomer(UUID tenantId, UUID customerId);

    @PreAuthorize(REQUIRES_CUSTOMERS_WRITE)
    Customer updateProfile(UpdateProfileCommand command);

    @PreAuthorize(REQUIRES_CUSTOMERS_WRITE)
    Customer submitKyc(SubmitKycCommand command);

    @PreAuthorize(REQUIRES_CUSTOMERS_WRITE)
    Customer completeKyc(UUID tenantId, UUID customerId, UUID kycRecordId);

    /**
     * Triggers a live Aadhaar e-KYC verification via the Partner Integration
     * Service (DigiLocker/UIDAI). On success, automatically completes KYC.
     */
    @PreAuthorize(REQUIRES_CUSTOMERS_WRITE)
    Customer initiateAadhaarEkyc(InitiateAadhaarEkycCommand command);

    @PreAuthorize(REQUIRES_CUSTOMERS_WRITE)
    Customer addBankAccount(AddBankAccountCommand command);

    @PreAuthorize(REQUIRES_CUSTOMERS_WRITE)
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
