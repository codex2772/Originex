package com.originex.customer.application.service;

import com.originex.customer.application.port.in.CustomerUseCase;
import com.originex.customer.application.port.out.AadhaarVerificationPort;
import com.originex.customer.application.port.out.BankAccountVerificationPort;
import com.originex.customer.application.port.out.CustomerRepository;
import com.originex.customer.application.port.out.PanVerificationPort;
import com.originex.customer.domain.exception.CustomerNotFoundException;
import com.originex.customer.domain.exception.DuplicateCustomerException;
import com.originex.customer.domain.model.*;
import com.originex.starter.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Customer application service — orchestrates use cases.
 */
@Service
@Transactional
public class CustomerApplicationService implements CustomerUseCase {

    private static final Logger log = LoggerFactory.getLogger(CustomerApplicationService.class);

    private final CustomerRepository customerRepository;
    private final OutboxPublisher outboxPublisher;
    private final PanVerificationPort panVerificationPort;
    private final AadhaarVerificationPort aadhaarVerificationPort;
    private final BankAccountVerificationPort bankAccountVerificationPort;

    public CustomerApplicationService(CustomerRepository customerRepository,
                                      OutboxPublisher outboxPublisher,
                                      PanVerificationPort panVerificationPort,
                                      AadhaarVerificationPort aadhaarVerificationPort,
                                      BankAccountVerificationPort bankAccountVerificationPort) {
        this.customerRepository = customerRepository;
        this.outboxPublisher = outboxPublisher;
        this.panVerificationPort = panVerificationPort;
        this.aadhaarVerificationPort = aadhaarVerificationPort;
        this.bankAccountVerificationPort = bankAccountVerificationPort;
    }

    @Override
    public Customer registerCustomer(RegisterCustomerCommand command) {
        log.info("Registering customer: phone={}, tenant={}", maskPhone(command.phone()), command.tenantId());

        // Duplicate check by phone
        if (customerRepository.existsByPhone(command.tenantId(), command.phone())) {
            throw new DuplicateCustomerException("Customer with this phone already exists");
        }

        // Duplicate check by PAN
        if (command.panNumber() != null) {
            String panHash = hashPan(command.panNumber());
            if (customerRepository.existsByPanHash(command.tenantId(), panHash)) {
                throw new DuplicateCustomerException("Customer with this PAN already exists");
            }
        }

        // Live PAN verification via Partner Integration Service (NSDL)
        if (command.panNumber() != null) {
            String fullName = command.firstName() + " " + command.lastName();
            PanVerificationPort.PanVerificationResult panResult = panVerificationPort.verify(
                    new PanVerificationPort.PanVerificationRequest(
                            command.tenantId().toString(), null, // referenceId assigned after customer creation
                            command.panNumber(), fullName,
                            command.dateOfBirth() != null ? command.dateOfBirth().toString() : null
                    ));

            if (!panResult.valid()) {
                throw new IllegalArgumentException("PAN verification failed: " +
                        (panResult.failureReason() != null ? panResult.failureReason() : "Invalid PAN"));
            }
            log.info("PAN verified successfully: status={}, nameMatch={}", panResult.panStatus(), panResult.nameMatch());
        }

        // Create domain aggregate
        Customer customer = Customer.register(
                command.tenantId(),
                command.firstName(),
                command.lastName(),
                command.email(),
                command.phone(),
                command.dateOfBirth()
        );

        // Set PAN (encrypted + hash)
        if (command.panNumber() != null) {
            String panEncrypted = encryptPan(command.panNumber());
            String panHash = hashPan(command.panNumber());
            customer.setPanDetails(panEncrypted, panHash);
        }

        // Set Aadhaar token (irreversible)
        if (command.aadhaarNumber() != null) {
            String aadhaarToken = tokenizeAadhaar(command.aadhaarNumber(), command.tenantId().toString());
            customer.setAadhaarToken(aadhaarToken);
        }

        // Persist
        Customer saved = customerRepository.save(customer);

        log.info("Customer registered: id={}", saved.getCustomerId());

        // Publish CustomerRegistered event via transactional outbox
        outboxPublisher.publish(
                "Customer", saved.getCustomerId(),
                "originex.customer.CustomerRegistered", command.tenantId(),
                buildCustomerRegisteredPayload(saved)
        );

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Customer getCustomer(UUID tenantId, UUID customerId) {
        return customerRepository.findById(tenantId, customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));
    }

    @Override
    public Customer updateProfile(UpdateProfileCommand command) {
        Customer customer = customerRepository.findById(command.tenantId(), command.customerId())
                .orElseThrow(() -> new CustomerNotFoundException(command.customerId()));

        if (customer.getVersion() != command.expectedVersion()) {
            throw new IllegalStateException(
                    "Version conflict: expected " + command.expectedVersion() + " but found " + customer.getVersion());
        }

        customer.updateProfile(command.firstName(), command.lastName(), command.email(), command.dateOfBirth());
        return customerRepository.save(customer);
    }

    @Override
    public Customer submitKyc(SubmitKycCommand command) {
        Customer customer = customerRepository.findById(command.tenantId(), command.customerId())
                .orElseThrow(() -> new CustomerNotFoundException(command.customerId()));

        KycRecord.KycType type = KycRecord.KycType.valueOf(command.kycType());
        KycRecord record = KycRecord.create(type, command.verificationReference());
        customer.submitKyc(record);

        return customerRepository.save(customer);
    }

    @Override
    public Customer completeKyc(UUID tenantId, UUID customerId, UUID kycRecordId) {
        Customer customer = customerRepository.findById(tenantId, customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));

        customer.completeKyc(kycRecordId);
        Customer saved = customerRepository.save(customer);

        log.info("KYC completed: customerId={}", customerId);

        // Publish KYCCompleted event via transactional outbox
        outboxPublisher.publish(
                "Customer", customerId,
                "originex.customer.KYCCompleted", tenantId,
                buildKycCompletedPayload(saved, kycRecordId)
        );

        return saved;
    }

    @Override
    public Customer initiateAadhaarEkyc(InitiateAadhaarEkycCommand command) {
        Customer customer = customerRepository.findById(command.tenantId(), command.customerId())
                .orElseThrow(() -> new CustomerNotFoundException(command.customerId()));

        // Create the KYC record first so we have an ID to track
        KycRecord record = KycRecord.create(KycRecord.KycType.EKYC_AADHAAR, null);
        customer.submitKyc(record);

        // Live Aadhaar e-KYC verification via Partner Integration Service (DigiLocker/UIDAI)
        AadhaarVerificationPort.AadhaarVerificationResult result = aadhaarVerificationPort.verify(
                new AadhaarVerificationPort.AadhaarVerificationRequest(
                        command.tenantId().toString(), command.customerId().toString(),
                        command.aadhaarNumberOrVid(), command.consentArtifactId(), command.otpReference()
                ));

        if (!result.verified()) {
            customer.rejectKyc(record.getKycRecordId(),
                    result.failureReason() != null ? result.failureReason() : "Aadhaar verification failed");
            customerRepository.save(customer);
            throw new IllegalStateException("Aadhaar e-KYC failed: " +
                    (result.failureReason() != null ? result.failureReason() : "Verification unsuccessful"));
        }

        // Persist only the irreversible token — never the raw Aadhaar number
        String aadhaarToken = tokenizeAadhaar(command.aadhaarNumberOrVid(), command.tenantId().toString());
        customer.setAadhaarToken(aadhaarToken);
        customer.completeKyc(record.getKycRecordId());

        Customer saved = customerRepository.save(customer);
        log.info("Aadhaar e-KYC completed: customerId={}, maskedAadhaar={}, nameOnRecord={}",
                command.customerId(), result.maskedAadhaar(), result.nameOnRecord());

        outboxPublisher.publish(
                "Customer", command.customerId(),
                "originex.customer.KYCCompleted", command.tenantId(),
                buildKycCompletedPayload(saved, record.getKycRecordId())
        );

        return saved;
    }

    @Override
    public Customer addBankAccount(AddBankAccountCommand command) {
        Customer customer = customerRepository.findById(command.tenantId(), command.customerId())
                .orElseThrow(() -> new CustomerNotFoundException(command.customerId()));

        BankAccount account = BankAccount.create(
                command.accountNumber(),
                command.ifscCode(),
                command.bankName(),
                command.accountHolderName(),
                BankAccount.BankAccountType.valueOf(command.accountType())
        );

        if (command.setPrimary()) {
            account.markPrimary();
        }

        customer.addBankAccount(account);
        return customerRepository.save(customer);
    }

    @Override
    public Customer verifyBankAccount(UUID tenantId, UUID customerId, UUID bankAccountId) {
        Customer customer = customerRepository.findById(tenantId, customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));

        BankAccount account = customer.getBankAccounts().stream()
                .filter(ba -> ba.getBankAccountId().equals(bankAccountId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Bank account not found: " + bankAccountId));

        // Live penny-drop verification via Partner Integration Service
        BankAccountVerificationPort.BankAccountVerificationResult result = bankAccountVerificationPort.verify(
                new BankAccountVerificationPort.BankAccountVerificationRequest(
                        tenantId.toString(), bankAccountId.toString(),
                        account.getAccountNumber(), account.getIfscCode(),
                        account.getAccountHolderName()
                ));

        if (!result.verified()) {
            throw new IllegalStateException("Bank account verification failed: " +
                    (result.failureReason() != null ? result.failureReason() : "Could not verify account"));
        }

        log.info("Bank account verified via penny-drop: bankAccountId={}, bank={}, nameMatch={}",
                bankAccountId, result.bankName(), result.nameMatch());

        customer.verifyBankAccount(bankAccountId);
        return customerRepository.save(customer);
    }

    // ─── Security Helpers ───

    private String encryptPan(String pan) {
        // TODO Phase 4: Use AWS KMS envelope encryption
        // For now, simple placeholder (NOT production-safe)
        return "ENC:" + pan.substring(0, 5) + "XXXXX";
    }

    private String hashPan(String pan) {
        return sha256(pan.toUpperCase().trim());
    }

    private String tokenizeAadhaar(String aadhaar, String tenantSalt) {
        // Irreversible: SHA-256(aadhaar + tenant-specific salt)
        return sha256(aadhaar + "::" + tenantSalt);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return "X".repeat(phone.length() - 3) + phone.substring(phone.length() - 3);
    }

    // ─── Event Payload Builders (JSON until full Protobuf wiring) ───

    private byte[] buildCustomerRegisteredPayload(Customer customer) {
        String json = String.format(
                "{\"customer_id\":\"%s\",\"first_name\":\"%s\",\"last_name\":\"%s\",\"phone\":\"%s\"}",
                customer.getCustomerId(), customer.getFirstName(),
                customer.getLastName(), customer.getPhone()
        );
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildKycCompletedPayload(Customer customer, UUID kycRecordId) {
        String json = String.format(
                "{\"customer_id\":\"%s\",\"kyc_record_id\":\"%s\",\"kyc_status\":\"%s\"}",
                customer.getCustomerId(), kycRecordId, customer.getKycStatus()
        );
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
