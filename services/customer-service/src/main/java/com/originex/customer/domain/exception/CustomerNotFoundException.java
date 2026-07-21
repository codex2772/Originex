package com.originex.customer.domain.exception;

import com.originex.common.exception.ResourceNotFoundException;

import java.util.UUID;

public class CustomerNotFoundException extends ResourceNotFoundException {
    private final UUID customerId;

    public CustomerNotFoundException(UUID customerId) {
        super("Customer not found: " + customerId);
        this.customerId = customerId;
    }

    public UUID getCustomerId() { return customerId; }
}
