package com.originex.customer.application.port.out;

import com.originex.customer.domain.model.Customer;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — Customer persistence operations.
 */
public interface CustomerRepository {

    Customer save(Customer customer);

    Optional<Customer> findById(UUID tenantId, UUID customerId);

    Optional<Customer> findByPhone(UUID tenantId, String phone);

    boolean existsByPanHash(UUID tenantId, String panHash);

    boolean existsByPhone(UUID tenantId, String phone);
}
