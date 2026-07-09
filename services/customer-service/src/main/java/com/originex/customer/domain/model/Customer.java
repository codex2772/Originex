package com.originex.customer.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Customer Aggregate Root — single source of truth for customer identity.
 *
 * <p>Invariants:
 * <ul>
 *   <li>PAN is unique across the system (per tenant)</li>
 *   <li>Phone must be verified before registration completes</li>
 *   <li>KYC must be VERIFIED before any loan application</li>
 *   <li>Aadhaar is stored only as irreversible token (DPDPA compliance)</li>
 *   <li>At most one primary bank account at a time</li>
 * </ul>
 */
public class Customer {

    private UUID customerId;
    private UUID tenantId;
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
    private String email;
    private String phone;
    private boolean phoneVerified;
    private String panEncrypted;        // AES-256 encrypted PAN
    private String panHash;             // SHA-256 hash for uniqueness check
    private String aadhaarToken;        // Irreversible token (cannot reconstruct original)
    private CustomerStatus status;
    private KycStatus kycStatus;
    private List<Address> addresses;
    private List<BankAccount> bankAccounts;
    private List<KycRecord> kycRecords;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    // ═══════════════════════════════════════════════════════════════════
    // Factory Method
    // ═══════════════════════════════════════════════════════════════════

    public static Customer register(UUID tenantId, String firstName, String lastName,
                                    String email, String phone, LocalDate dateOfBirth) {
        Objects.requireNonNull(tenantId, "tenantId required");
        Objects.requireNonNull(firstName, "firstName required");
        Objects.requireNonNull(lastName, "lastName required");
        Objects.requireNonNull(phone, "phone required");

        if (firstName.isBlank() || lastName.isBlank()) {
            throw new IllegalArgumentException("Name must not be blank");
        }

        Customer customer = new Customer();
        customer.customerId = UUID.randomUUID();
        customer.tenantId = tenantId;
        customer.firstName = firstName.trim();
        customer.lastName = lastName.trim();
        customer.email = email;
        customer.phone = phone;
        customer.phoneVerified = false;
        customer.dateOfBirth = dateOfBirth;
        customer.status = CustomerStatus.REGISTERED;
        customer.kycStatus = KycStatus.NOT_INITIATED;
        customer.addresses = new ArrayList<>();
        customer.bankAccounts = new ArrayList<>();
        customer.kycRecords = new ArrayList<>();
        customer.version = 0;
        customer.createdAt = Instant.now();
        customer.updatedAt = Instant.now();
        return customer;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Domain Behavior
    // ═══════════════════════════════════════════════════════════════════

    public void verifyPhone() {
        this.phoneVerified = true;
        this.updatedAt = Instant.now();
    }

    public void updateProfile(String firstName, String lastName, String email, LocalDate dateOfBirth) {
        assertNotBlocked();
        if (firstName != null && !firstName.isBlank()) this.firstName = firstName.trim();
        if (lastName != null && !lastName.isBlank()) this.lastName = lastName.trim();
        if (email != null) this.email = email;
        if (dateOfBirth != null) this.dateOfBirth = dateOfBirth;
        this.updatedAt = Instant.now();
    }

    public void setPanDetails(String panEncrypted, String panHash) {
        Objects.requireNonNull(panEncrypted, "Encrypted PAN required");
        Objects.requireNonNull(panHash, "PAN hash required");
        this.panEncrypted = panEncrypted;
        this.panHash = panHash;
        this.updatedAt = Instant.now();
    }

    public void setAadhaarToken(String aadhaarToken) {
        Objects.requireNonNull(aadhaarToken, "Aadhaar token required");
        this.aadhaarToken = aadhaarToken;
        this.updatedAt = Instant.now();
    }

    public void submitKyc(KycRecord kycRecord) {
        assertNotBlocked();
        this.kycRecords.add(kycRecord);
        this.kycStatus = KycStatus.PENDING;
        this.updatedAt = Instant.now();
    }

    public void completeKyc(UUID kycRecordId) {
        KycRecord record = findKycRecord(kycRecordId);
        record.markVerified();
        this.kycStatus = KycStatus.VERIFIED;
        this.status = CustomerStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void rejectKyc(UUID kycRecordId, String reason) {
        KycRecord record = findKycRecord(kycRecordId);
        record.markRejected(reason);
        this.kycStatus = KycStatus.REJECTED;
        this.updatedAt = Instant.now();
    }

    public void addBankAccount(BankAccount account) {
        assertNotBlocked();
        Objects.requireNonNull(account, "Bank account required");

        // If this is the first account or marked primary, set as primary
        if (account.isPrimary()) {
            bankAccounts.forEach(ba -> ba.unsetPrimary());
        }
        if (bankAccounts.isEmpty()) {
            account.markPrimary();
        }

        this.bankAccounts.add(account);
        this.updatedAt = Instant.now();
    }

    public void verifyBankAccount(UUID bankAccountId) {
        BankAccount account = findBankAccount(bankAccountId);
        account.markVerified();
        this.updatedAt = Instant.now();
    }

    public void addAddress(Address address) {
        assertNotBlocked();
        this.addresses.add(address);
        this.updatedAt = Instant.now();
    }

    public void block(String reason) {
        this.status = CustomerStatus.BLOCKED;
        this.updatedAt = Instant.now();
    }

    public boolean isKycVerified() {
        return this.kycStatus == KycStatus.VERIFIED;
    }

    public boolean isEligibleForLoan() {
        return this.status == CustomerStatus.ACTIVE && isKycVerified();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Invariant Guards
    // ═══════════════════════════════════════════════════════════════════

    private void assertNotBlocked() {
        if (this.status == CustomerStatus.BLOCKED) {
            throw new IllegalStateException("Customer is blocked, operations not permitted");
        }
    }

    private KycRecord findKycRecord(UUID kycRecordId) {
        return kycRecords.stream()
                .filter(k -> k.getKycRecordId().equals(kycRecordId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("KYC record not found: " + kycRecordId));
    }

    private BankAccount findBankAccount(UUID bankAccountId) {
        return bankAccounts.stream()
                .filter(ba -> ba.getBankAccountId().equals(bankAccountId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Bank account not found: " + bankAccountId));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Accessors
    // ═══════════════════════════════════════════════════════════════════

    public UUID getCustomerId() { return customerId; }
    public UUID getTenantId() { return tenantId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getFullName() { return firstName + " " + lastName; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public boolean isPhoneVerified() { return phoneVerified; }
    public String getPanEncrypted() { return panEncrypted; }
    public String getPanHash() { return panHash; }
    public String getAadhaarToken() { return aadhaarToken; }
    public CustomerStatus getStatus() { return status; }
    public KycStatus getKycStatus() { return kycStatus; }
    public List<Address> getAddresses() { return Collections.unmodifiableList(addresses); }
    public List<BankAccount> getBankAccounts() { return Collections.unmodifiableList(bankAccounts); }
    public List<KycRecord> getKycRecords() { return Collections.unmodifiableList(kycRecords); }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // ═══════════════════════════════════════════════════════════════════
    // Reconstruction setters (used by persistence adapters only)
    // ═══════════════════════════════════════════════════════════════════

    public Customer() {}
    public void setCustomerId(UUID id) { this.customerId = id; }
    public void setTenantId(UUID id) { this.tenantId = id; }
    public void setFirstName(String s) { this.firstName = s; }
    public void setLastName(String s) { this.lastName = s; }
    public void setDateOfBirth(LocalDate d) { this.dateOfBirth = d; }
    public void setEmail(String s) { this.email = s; }
    public void setPhone(String s) { this.phone = s; }
    public void setPhoneVerified(boolean b) { this.phoneVerified = b; }
    public void setPanEncrypted(String s) { this.panEncrypted = s; }
    public void setPanHash(String s) { this.panHash = s; }
    public void setAadhaarTokenRaw(String s) { this.aadhaarToken = s; }
    public void setStatus(CustomerStatus s) { this.status = s; }
    public void setKycStatus(KycStatus s) { this.kycStatus = s; }
    public void setAddresses(List<Address> l) { this.addresses = l; }
    public void setBankAccounts(List<BankAccount> l) { this.bankAccounts = l; }
    public void setKycRecords(List<KycRecord> l) { this.kycRecords = l; }
    public void setVersion(long v) { this.version = v; }
    public void setCreatedAt(Instant i) { this.createdAt = i; }
    public void setUpdatedAt(Instant i) { this.updatedAt = i; }
}
