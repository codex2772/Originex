package com.originex.payment.adapter.out.persistence;

import com.originex.payment.application.port.out.PaymentOrderRepository;
import com.originex.payment.domain.model.PaymentOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentOrderPersistenceAdapter implements PaymentOrderRepository {

    private final PaymentOrderJpaRepository jpa;

    public PaymentOrderPersistenceAdapter(PaymentOrderJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public PaymentOrder save(PaymentOrder order) {
        return jpa.save(PaymentOrderJpaEntity.fromDomain(order)).toDomain();
    }

    @Override
    public Optional<PaymentOrder> findById(UUID tenantId, UUID id) {
        return jpa.findByTenantAndId(tenantId, id).map(PaymentOrderJpaEntity::toDomain);
    }

    @Override
    public Optional<PaymentOrder> findByReference(UUID tenantId, String ref) {
        return jpa.findByTenantAndReference(tenantId, ref).map(PaymentOrderJpaEntity::toDomain);
    }

    @Override
    public List<PaymentOrder> findPendingRetries(int maxResults) {
        return jpa.findPendingRetries(PageRequest.of(0, maxResults))
                .stream().map(PaymentOrderJpaEntity::toDomain).toList();
    }
}
