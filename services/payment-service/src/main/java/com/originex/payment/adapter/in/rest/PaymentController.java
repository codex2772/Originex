package com.originex.payment.adapter.in.rest;

import com.originex.common.tenant.TenantContextHolder;
import com.originex.payment.application.port.in.PaymentUseCase;
import com.originex.payment.application.port.in.PaymentUseCase.*;
import com.originex.payment.domain.model.NachMandate;
import com.originex.payment.domain.model.PaymentOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentUseCase paymentUseCase;

    public PaymentController(PaymentUseCase paymentUseCase) {
        this.paymentUseCase = paymentUseCase;
    }

    // ─── Disbursement ───

    @PostMapping("/disbursements")
    public ResponseEntity<PaymentOrderResponse> initiateDisbursement(
            @Valid @RequestBody DisbursementRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        PaymentOrder order = paymentUseCase.initiateDisbursement(new InitiateDisbursementCommand(
                tenantId, UUID.fromString(request.loanId()), UUID.fromString(request.customerId()),
                request.amount(), request.currency(),
                request.beneficiaryAccountNumber(), request.beneficiaryIfsc(),
                request.beneficiaryName(), request.beneficiaryBankName(), request.preferredRail()
        ));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(order.getPaymentOrderId()).toUri();
        return ResponseEntity.created(location).body(PaymentOrderResponse.from(order));
    }

    @GetMapping("/{paymentOrderId}")
    public ResponseEntity<PaymentOrderResponse> get(@PathVariable UUID paymentOrderId) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());
        return ResponseEntity.ok(PaymentOrderResponse.from(
                paymentUseCase.getPaymentOrder(tenantId, paymentOrderId)));
    }

    // ─── Inbound Payment (Manual / UPI) ───

    @PostMapping("/inbound")
    public ResponseEntity<PaymentOrderResponse> recordInbound(
            @Valid @RequestBody InboundPaymentRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        PaymentOrder order = paymentUseCase.recordInboundPayment(new RecordInboundPaymentCommand(
                tenantId, UUID.fromString(request.loanId()), UUID.fromString(request.customerId()),
                request.amount(), request.currency(), request.paymentRail(),
                request.externalTransactionId(), request.narration()
        ));

        return ResponseEntity.ok(PaymentOrderResponse.from(order));
    }

    // ─── Webhook Callback from Payment Rail ───

    @PostMapping("/callbacks")
    public ResponseEntity<PaymentOrderResponse> handleCallback(
            @Valid @RequestBody PaymentCallbackRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        PaymentOrder order = paymentUseCase.handlePaymentCallback(new PaymentCallbackCommand(
                tenantId, UUID.fromString(request.paymentOrderId()),
                request.status(), request.externalTransactionId(),
                request.bankReferenceNumber(), request.failureReason()
        ));

        return ResponseEntity.ok(PaymentOrderResponse.from(order));
    }

    // ─── NACH Mandates ───

    @PostMapping("/mandates")
    public ResponseEntity<MandateResponse> registerMandate(
            @Valid @RequestBody RegisterMandateRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        NachMandate mandate = paymentUseCase.registerNachMandate(new RegisterMandateCommand(
                tenantId, UUID.fromString(request.loanId()), UUID.fromString(request.customerId()),
                request.bankAccountNumber(), request.ifscCode(), request.bankName(),
                request.accountHolderName(), request.maxDebitAmount(),
                request.currency(), request.startDate(), request.endDate()
        ));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(mandate.getMandateId()).toUri();
        return ResponseEntity.created(location).body(MandateResponse.from(mandate));
    }

    @PostMapping("/mandates/{mandateId}/collect")
    public ResponseEntity<PaymentOrderResponse> triggerCollection(
            @PathVariable UUID mandateId,
            @Valid @RequestBody CollectionRequest request) {
        UUID tenantId = UUID.fromString(TenantContextHolder.requireTenantId());

        PaymentOrder order = paymentUseCase.triggerNachCollection(new TriggerCollectionCommand(
                tenantId, UUID.fromString(request.loanId()), mandateId,
                request.amount(), request.currency(), request.installmentReference()
        ));

        return ResponseEntity.ok(PaymentOrderResponse.from(order));
    }

    // ─── Request DTOs ───

    record DisbursementRequest(
            @NotBlank String loanId,
            @NotBlank String customerId,
            @NotBlank String amount,
            String currency,
            @NotBlank String beneficiaryAccountNumber,
            @NotBlank String beneficiaryIfsc,
            @NotBlank String beneficiaryName,
            String beneficiaryBankName,
            String preferredRail
    ) {}

    record InboundPaymentRequest(
            @NotBlank String loanId,
            @NotBlank String customerId,
            @NotBlank String amount,
            String currency,
            @NotBlank String paymentRail,
            @NotBlank String externalTransactionId,
            String narration
    ) {}

    record PaymentCallbackRequest(
            @NotBlank String paymentOrderId,
            @NotBlank String status,
            String externalTransactionId,
            String bankReferenceNumber,
            String failureReason
    ) {}

    record RegisterMandateRequest(
            @NotBlank String loanId,
            @NotBlank String customerId,
            @NotBlank String bankAccountNumber,
            @NotBlank String ifscCode,
            @NotBlank String bankName,
            @NotBlank String accountHolderName,
            @NotBlank String maxDebitAmount,
            String currency,
            @NotBlank String startDate,
            @NotBlank String endDate
    ) {}

    record CollectionRequest(
            @NotBlank String loanId,
            @NotBlank String amount,
            String currency,
            String installmentReference
    ) {}

    // ─── Response DTOs ───

    record PaymentOrderResponse(
            UUID paymentOrderId, UUID loanId, String paymentType, String paymentRail,
            String status, String amount, String currency, String paymentReference,
            String externalTransactionId, String failureReason,
            int retryCount, Instant createdAt, Instant completedAt
    ) {
        static PaymentOrderResponse from(PaymentOrder o) {
            return new PaymentOrderResponse(
                    o.getPaymentOrderId(), o.getLoanId(), o.getPaymentType().name(),
                    o.getPaymentRail().name(), o.getStatus().name(),
                    o.getAmount().getAmount().toPlainString(), o.getAmount().getCurrencyCode(),
                    o.getPaymentReference(), o.getExternalTransactionId(), o.getFailureReason(),
                    o.getRetryCount(), o.getCreatedAt(), o.getCompletedAt()
            );
        }
    }

    record MandateResponse(
            UUID mandateId, UUID loanId, String status, String umrn,
            String maxDebitAmount, String currency, Instant startDate
    ) {
        static MandateResponse from(NachMandate m) {
            return new MandateResponse(
                    m.getMandateId(), m.getLoanId(), m.getStatus().name(), m.getUmrn(),
                    m.getMaxDebitAmount().getAmount().toPlainString(),
                    m.getMaxDebitAmount().getCurrencyCode(), m.getStartDate()
            );
        }
    }
}
