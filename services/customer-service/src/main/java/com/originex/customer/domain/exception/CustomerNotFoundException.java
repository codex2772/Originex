package com.originex.customer.domain.exception;

import java.util.UUID;

public class CustomerNotFoundException extends RuntimeException {
    private final UUID customerId;

    public CustomerNotFoundException(UUID customerId) {
        super("Customer not found: " + customerId);
        this.customerId = customerId;
    }

    public UUID getCustomerId() { return customerId; }
}
