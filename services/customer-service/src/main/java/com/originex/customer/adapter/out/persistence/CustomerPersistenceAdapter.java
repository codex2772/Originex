package com.originex.customer.adapter.out.persistence;

import com.originex.customer.application.port.out.CustomerRepository;
import com.originex.customer.domain.model.Customer;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter — bridges domain CustomerRepository port to Spring Data JPA.
 */
@Component
public class CustomerPersistenceAdapter implements CustomerRepository {

    private final CustomerJpaRepository jpaRepository;

    public CustomerPersistenceAdapter(CustomerJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Customer save(Customer customer) {
        CustomerJpaEntity entity = CustomerJpaEntity.fromDomain(customer);
        CustomerJpaEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public Optional<Customer> findById(UUID tenantId, UUID customerId) {
        return jpaRepository.findByTenantAndId(tenantId, customerId)
                .map(CustomerJpaEntity::toDomain);
    }

    @Override
    public Optional<Customer> findByPhone(UUID tenantId, String phone) {
        return jpaRepository.findByTenantAndPhone(tenantId, phone)
                .map(CustomerJpaEntity::toDomain);
    }

    @Override
    public boolean existsByPanHash(UUID tenantId, String panHash) {
        return jpaRepository.existsByTenantIdAndPanHash(tenantId, panHash);
    }

    @Override
    public boolean existsByPhone(UUID tenantId, String phone) {
        return jpaRepository.existsByTenantIdAndPhone(tenantId, phone);
    }
}
