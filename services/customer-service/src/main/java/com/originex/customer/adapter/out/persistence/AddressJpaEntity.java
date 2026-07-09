package com.originex.customer.adapter.out.persistence;

import com.originex.customer.domain.model.Address;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "addresses")
public class AddressJpaEntity {

    @Id
    @Column(name = "address_id")
    private UUID addressId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private CustomerJpaEntity customer;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "address_type", nullable = false)
    private String addressType;

    @Column(name = "line1", nullable = false)
    private String line1;

    @Column(name = "line2")
    private String line2;

    @Column(name = "city", nullable = false)
    private String city;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "pincode", nullable = false)
    private String pincode;

    @Column(name = "country", nullable = false)
    private String country;

    @Column(name = "created_at")
    private Instant createdAt;

    public static AddressJpaEntity fromDomain(Address domain, CustomerJpaEntity customer) {
        AddressJpaEntity e = new AddressJpaEntity();
        e.addressId = domain.getAddressId();
        e.customer = customer;
        e.tenantId = customer.getTenantId();
        e.addressType = domain.getType().name();
        e.line1 = domain.getLine1();
        e.line2 = domain.getLine2();
        e.city = domain.getCity();
        e.state = domain.getState();
        e.pincode = domain.getPincode();
        e.country = domain.getCountry();
        e.createdAt = Instant.now();
        return e;
    }

    public Address toDomain() {
        return Address.create(
                Address.AddressType.valueOf(addressType),
                line1, line2, city, state, pincode
        );
    }

    protected AddressJpaEntity() {}
}
