package com.originex.template.domain.model;

import com.originex.common.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Sample Aggregate Root — demonstrates DDD aggregate pattern.
 * Replace with your actual domain aggregate (e.g., Loan, Account, Application).
 *
 * <p>Aggregate rules:
 * <ul>
 *   <li>All state changes go through methods that enforce invariants</li>
 *   <li>External access only via the root</li>
 *   <li>Domain events raised on state transitions</li>
 *   <li>Optimistic locking via version field</li>
 * </ul>
 */
public class Sample {

    private UUID sampleId;
    private UUID tenantId;
    private String name;
    private String description;
    private SampleStatus status;
    private Money amount;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    // ═══════════════════════════════════════════════════════════════════
    // Factory Method (creation)
    // ═══════════════════════════════════════════════════════════════════

    public static Sample create(UUID tenantId, String name, String description, Money amount) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(name, "name must not be null");

        if (name.isBlank()) {
            throw new IllegalArgumentException("Name must not be blank");
        }
        if (amount != null && amount.isNegative()) {
            throw new IllegalArgumentException("Amount must not be negative");
        }

        Sample sample = new Sample();
        sample.sampleId = UUID.randomUUID();
        sample.tenantId = tenantId;
        sample.name = name;
        sample.description = description;
        sample.amount = amount;
        sample.status = SampleStatus.ACTIVE;
        sample.version = 0;
        sample.createdAt = Instant.now();
        sample.updatedAt = Instant.now();
        return sample;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Domain Behavior (state transitions with invariant enforcement)
    // ═══════════════════════════════════════════════════════════════════

    public void updateDetails(String name, String description) {
        assertActive();
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public void updateAmount(Money newAmount) {
        assertActive();
        if (newAmount != null && newAmount.isNegative()) {
            throw new IllegalArgumentException("Amount must not be negative");
        }
        this.amount = newAmount;
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        assertActive();
        this.status = SampleStatus.INACTIVE;
        this.updatedAt = Instant.now();
    }

    public void activate() {
        if (this.status != SampleStatus.INACTIVE) {
            throw new IllegalStateException("Can only activate from INACTIVE state");
        }
        this.status = SampleStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Invariant Guards
    // ═══════════════════════════════════════════════════════════════════

    private void assertActive() {
        if (this.status != SampleStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Operation not allowed in status: " + this.status);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Accessors (no setters — state only changes via behavior methods)
    // ═══════════════════════════════════════════════════════════════════

    public UUID getSampleId() { return sampleId; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public SampleStatus getStatus() { return status; }
    public Money getAmount() { return amount; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // For JPA/persistence reconstruction only
    public Sample() {}

    public void setSampleId(UUID sampleId) { this.sampleId = sampleId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setStatus(SampleStatus status) { this.status = status; }
    public void setAmount(Money amount) { this.amount = amount; }
    public void setVersion(long version) { this.version = version; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
