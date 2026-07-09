package com.originex.customer.adapter.out.persistence;

import com.originex.customer.domain.model.*;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class CustomerJpaEntity {

    @Id
    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "email")
    private String email;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "phone_verified")
    private boolean phoneVerified;

    @Column(name = "pan_encrypted")
    private String panEncrypted;

    @Column(name = "pan_hash")
    private String panHash;

    @Column(name = "aadhaar_token")
    private String aadhaarToken;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CustomerStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false)
    private KycStatus kycStatus;

    @Version
    @Column(name = "version")
    private long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<KycRecordJpaEntity> kycRecords = new ArrayList<>();

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<BankAccountJpaEntity> bankAccounts = new ArrayList<>();

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<AddressJpaEntity> addresses = new ArrayList<>();

    // ─── Domain ↔ JPA Mapping ───

    public static CustomerJpaEntity fromDomain(Customer domain) {
        CustomerJpaEntity e = new CustomerJpaEntity();
        e.customerId = domain.getCustomerId();
        e.tenantId = domain.getTenantId();
        e.firstName = domain.getFirstName();
        e.lastName = domain.getLastName();
        e.dateOfBirth = domain.getDateOfBirth();
        e.email = domain.getEmail();
        e.phone = domain.getPhone();
        e.phoneVerified = domain.isPhoneVerified();
        e.panEncrypted = domain.getPanEncrypted();
        e.panHash = domain.getPanHash();
        e.aadhaarToken = domain.getAadhaarToken();
        e.status = domain.getStatus();
        e.kycStatus = domain.getKycStatus();
        e.version = domain.getVersion();
        e.createdAt = domain.getCreatedAt();
        e.updatedAt = domain.getUpdatedAt();

        domain.getKycRecords().forEach(k -> {
            KycRecordJpaEntity kr = KycRecordJpaEntity.fromDomain(k, e);
            e.kycRecords.add(kr);
        });
        domain.getBankAccounts().forEach(b -> {
            BankAccountJpaEntity ba = BankAccountJpaEntity.fromDomain(b, e);
            e.bankAccounts.add(ba);
        });
        domain.getAddresses().forEach(a -> {
            AddressJpaEntity addr = AddressJpaEntity.fromDomain(a, e);
            e.addresses.add(addr);
        });

        return e;
    }

    public Customer toDomain() {
        Customer c = new Customer();
        c.setCustomerId(customerId);
        c.setTenantId(tenantId);
        c.setFirstName(firstName);
        c.setLastName(lastName);
        c.setDateOfBirth(dateOfBirth);
        c.setEmail(email);
        c.setPhone(phone);
        c.setPhoneVerified(phoneVerified);
        c.setPanEncrypted(panEncrypted);
        c.setPanHash(panHash);
        c.setAadhaarTokenRaw(aadhaarToken);
        c.setStatus(status);
        c.setKycStatus(kycStatus);
        c.setVersion(version);
        c.setCreatedAt(createdAt);
        c.setUpdatedAt(updatedAt);

        List<KycRecord> kycList = kycRecords.stream().map(KycRecordJpaEntity::toDomain).toList();
        c.setKycRecords(new ArrayList<>(kycList));

        List<BankAccount> baList = bankAccounts.stream().map(BankAccountJpaEntity::toDomain).toList();
        c.setBankAccounts(new ArrayList<>(baList));

        List<Address> addrList = addresses.stream().map(AddressJpaEntity::toDomain).toList();
        c.setAddresses(new ArrayList<>(addrList));

        return c;
    }

    // JPA accessors
    public UUID getCustomerId() { return customerId; }
    public UUID getTenantId() { return tenantId; }

    protected CustomerJpaEntity() {}
}
