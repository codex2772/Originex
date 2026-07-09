package com.originex.payment.adapter.out.persistence;

import com.originex.payment.domain.model.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderJpaRepository extends JpaRepository<PaymentOrderJpaEntity, UUID> {

    @Query("SELECT p FROM PaymentOrderJpaEntity p WHERE p.tenantId = :tenantId AND p.paymentOrderId = :id")
    Optional<PaymentOrderJpaEntity> findByTenantAndId(UUID tenantId, UUID id);

    @Query("SELECT p FROM PaymentOrderJpaEntity p WHERE p.tenantId = :tenantId AND p.paymentReference = :ref")
    Optional<PaymentOrderJpaEntity> findByTenantAndReference(UUID tenantId, String ref);

    @Query("SELECT p FROM PaymentOrderJpaEntity p WHERE p.status = 'RETRY_PENDING' ORDER BY p.updatedAt ASC")
    List<PaymentOrderJpaEntity> findPendingRetries(org.springframework.data.domain.Pageable pageable);
}
