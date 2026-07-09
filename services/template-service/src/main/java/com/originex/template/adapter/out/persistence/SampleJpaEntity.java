package com.originex.template.adapter.out.persistence;

import com.originex.common.money.Money;
import com.originex.template.domain.model.Sample;
import com.originex.template.domain.model.SampleStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity — maps domain aggregate to database table.
 * This is an INFRASTRUCTURE concern (adapter layer), NOT part of the domain model.
 * Conversion between JPA entity and domain model happens in the repository adapter.
 */
@Entity
@Table(name = "samples")
public class SampleJpaEntity {

    @Id
    @Column(name = "sample_id")
    private UUID sampleId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private SampleStatus status;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", length = 3)
    private String currency;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ─── Domain ↔ JPA Mapping ───

    public static SampleJpaEntity fromDomain(Sample domain) {
        SampleJpaEntity entity = new SampleJpaEntity();
        entity.sampleId = domain.getSampleId();
        entity.tenantId = domain.getTenantId();
        entity.name = domain.getName();
        entity.description = domain.getDescription();
        entity.status = domain.getStatus();
        if (domain.getAmount() != null) {
            entity.amount = domain.getAmount().getAmount();
            entity.currency = domain.getAmount().getCurrencyCode();
        }
        entity.version = domain.getVersion();
        entity.createdAt = domain.getCreatedAt();
        entity.updatedAt = domain.getUpdatedAt();
        return entity;
    }

    public Sample toDomain() {
        Money money = null;
        if (amount != null && currency != null) {
            money = Money.of(amount, currency);
        }

        Sample sample = new Sample();
        sample.setSampleId(sampleId);
        sample.setTenantId(tenantId);
        sample.setName(name);
        sample.setDescription(description);
        sample.setStatus(status);
        sample.setAmount(money);
        sample.setVersion(version);
        sample.setCreatedAt(createdAt);
        sample.setUpdatedAt(updatedAt);
        return sample;
    }

    // JPA requires default constructor
    protected SampleJpaEntity() {}
}
