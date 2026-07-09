package com.originex.payment.application.port.out;

import com.originex.payment.domain.model.NachMandate;
import com.originex.payment.domain.model.PaymentOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository {
    PaymentOrder save(PaymentOrder order);
    Optional<PaymentOrder> findById(UUID tenantId, UUID paymentOrderId);
    Optional<PaymentOrder> findByReference(UUID tenantId, String paymentReference);
    List<PaymentOrder> findPendingRetries(int maxResults);
}
