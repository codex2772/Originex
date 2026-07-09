package com.originex.customer.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface CustomerJpaRepository extends JpaRepository<CustomerJpaEntity, UUID> {

    @Query("SELECT c FROM CustomerJpaEntity c WHERE c.tenantId = :tenantId AND c.customerId = :customerId")
    Optional<CustomerJpaEntity> findByTenantAndId(UUID tenantId, UUID customerId);

    @Query("SELECT c FROM CustomerJpaEntity c WHERE c.tenantId = :tenantId AND c.phone = :phone")
    Optional<CustomerJpaEntity> findByTenantAndPhone(UUID tenantId, String phone);

    boolean existsByTenantIdAndPanHash(UUID tenantId, String panHash);

    boolean existsByTenantIdAndPhone(UUID tenantId, String phone);
}
