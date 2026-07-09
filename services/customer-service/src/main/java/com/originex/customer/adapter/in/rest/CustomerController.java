package com.originex.customer.adapter.in.rest;

import com.originex.common.tenant.TenantContextHolder;
import com.originex.customer.application.port.in.CustomerUseCase;
import com.originex.customer.application.port.in.CustomerUseCase.*;
import com.originex.customer.domain.model.Customer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/v1/customers")
public class CustomerController {

    private final CustomerUseCase customerUseCase;

    public CustomerController(CustomerUseCase customerUseCase) {
        this.customerUseCase = customerUseCase;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> register(@Valid @RequestBody RegisterRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        RegisterCustomerCommand command = new RegisterCustomerCommand(
                tenantId,
                request.firstName(),
                request.lastName(),
                request.email(),
                request.phone(),
                request.dateOfBirth(),
                request.panNumber(),
                request.aadhaarNumber()
        );

        Customer customer = customerUseCase.registerCustomer(command);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(customer.getCustomerId())
                .toUri();

        return ResponseEntity.created(location).body(CustomerResponse.from(customer));
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<CustomerResponse> get(@PathVariable UUID customerId) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        Customer customer = customerUseCase.getCustomer(tenantId, customerId);
        return ResponseEntity.ok(CustomerResponse.from(customer));
    }

    @PutMapping("/{customerId}")
    public ResponseEntity<CustomerResponse> updateProfile(
            @PathVariable UUID customerId,
            @RequestHeader("If-Match") long expectedVersion,
            @Valid @RequestBody UpdateProfileRequest request) {

        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        UpdateProfileCommand command = new UpdateProfileCommand(
                tenantId, customerId,
                request.firstName(), request.lastName(),
                request.email(), request.dateOfBirth(),
                expectedVersion
        );

        Customer customer = customerUseCase.updateProfile(command);
        return ResponseEntity.ok()
                .header("ETag", String.valueOf(customer.getVersion()))
                .body(CustomerResponse.from(customer));
    }

    @PostMapping("/{customerId}/kyc")
    public ResponseEntity<CustomerResponse> submitKyc(
            @PathVariable UUID customerId,
            @Valid @RequestBody SubmitKycRequest request) {

        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        SubmitKycCommand command = new SubmitKycCommand(
                tenantId, customerId, request.kycType(), request.verificationReference()
        );

        Customer customer = customerUseCase.submitKyc(command);
        return ResponseEntity.ok(CustomerResponse.from(customer));
    }

    @PostMapping("/{customerId}/kyc/{kycRecordId}/complete")
    public ResponseEntity<CustomerResponse> completeKyc(
            @PathVariable UUID customerId,
            @PathVariable UUID kycRecordId) {

        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        Customer customer = customerUseCase.completeKyc(tenantId, customerId, kycRecordId);
        return ResponseEntity.ok(CustomerResponse.from(customer));
    }

    @PostMapping("/{customerId}/kyc/aadhaar-ekyc")
    public ResponseEntity<CustomerResponse> initiateAadhaarEkyc(
            @PathVariable UUID customerId,
            @Valid @RequestBody AadhaarEkycRequest request) {

        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        InitiateAadhaarEkycCommand command = new InitiateAadhaarEkycCommand(
                tenantId, customerId, request.aadhaarNumberOrVid(),
                request.consentArtifactId(), request.otpReference()
        );

        Customer customer = customerUseCase.initiateAadhaarEkyc(command);
        return ResponseEntity.ok(CustomerResponse.from(customer));
    }

    @PostMapping("/{customerId}/bank-accounts")
    public ResponseEntity<CustomerResponse> addBankAccount(
            @PathVariable UUID customerId,
            @Valid @RequestBody AddBankAccountRequest request) {

        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        AddBankAccountCommand command = new AddBankAccountCommand(
                tenantId, customerId,
                request.accountNumber(), request.ifscCode(),
                request.bankName(), request.accountHolderName(),
                request.accountType(), request.primary()
        );

        Customer customer = customerUseCase.addBankAccount(command);
        return ResponseEntity.ok(CustomerResponse.from(customer));
    }

    // ─── Request DTOs ───

    record RegisterRequest(
            @NotBlank String firstName,
            @NotBlank String lastName,
            String email,
            @NotBlank String phone,
            LocalDate dateOfBirth,
            String panNumber,
            String aadhaarNumber
    ) {}

    record UpdateProfileRequest(
            String firstName,
            String lastName,
            String email,
            LocalDate dateOfBirth
    ) {}

    record SubmitKycRequest(
            @NotBlank String kycType,
            @NotBlank String verificationReference
    ) {}

    record AadhaarEkycRequest(
            @NotBlank String aadhaarNumberOrVid,
            @NotBlank String consentArtifactId,
            String otpReference
    ) {}

    record AddBankAccountRequest(
            @NotBlank String accountNumber,
            @NotBlank String ifscCode,
            @NotBlank String bankName,
            @NotBlank String accountHolderName,
            @NotBlank String accountType,
            boolean primary
    ) {}

    // ─── Response DTO ───

    record CustomerResponse(
            UUID id,
            String firstName,
            String lastName,
            String email,
            String phone,
            String status,
            String kycStatus,
            long version,
            String createdAt
    ) {
        static CustomerResponse from(Customer c) {
            return new CustomerResponse(
                    c.getCustomerId(),
                    c.getFirstName(),
                    c.getLastName(),
                    c.getEmail(),
                    c.getPhone(),
                    c.getStatus().name(),
                    c.getKycStatus().name(),
                    c.getVersion(),
                    c.getCreatedAt().toString()
            );
        }
    }
}
