package com.originex.notification.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.originex.notification.domain.model.NotificationTrigger;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Domain Service — maps Kafka event types to notification triggers
 * and extracts template variables from event payloads.
 */
@Service
public class EventToNotificationMapper {

    /**
     * Maps a Kafka event type to the corresponding NotificationTrigger.
     */
    public Optional<NotificationTrigger> mapTrigger(String eventType) {
        return Optional.ofNullable(switch (eventType) {
            // Customer events
            case "originex.customer.CustomerRegistered"   -> NotificationTrigger.CUSTOMER_REGISTERED;
            case "originex.customer.KYCCompleted"         -> NotificationTrigger.KYC_COMPLETED;

            // LOS events
            case "originex.los.ApplicationSubmitted"      -> NotificationTrigger.APPLICATION_SUBMITTED;
            case "originex.los.CreditCheckCompleted"      -> NotificationTrigger.CREDIT_CHECK_COMPLETED;
            case "originex.los.ApplicationApproved"       -> NotificationTrigger.APPLICATION_APPROVED;
            case "originex.los.ApplicationRejected"       -> NotificationTrigger.APPLICATION_REJECTED;
            case "originex.los.DisbursementRequested"     -> NotificationTrigger.OFFER_ACCEPTED;

            // LMS events
            case "originex.lms.LoanDisbursed"             -> NotificationTrigger.LOAN_DISBURSED;
            case "originex.lms.RepaymentAllocated"        -> NotificationTrigger.REPAYMENT_RECEIVED;
            case "originex.lms.DisbursementConfirmed"     -> NotificationTrigger.DISBURSEMENT_COMPLETED;

            // Payment events
            case "originex.payments.DisbursementCompleted" -> NotificationTrigger.DISBURSEMENT_COMPLETED;
            case "originex.payments.DisbursementInitiated" -> NotificationTrigger.DISBURSEMENT_INITIATED;
            case "originex.payments.PaymentFailed"         -> NotificationTrigger.PAYMENT_FAILED;
            case "originex.payments.NachMandateRegistered" -> NotificationTrigger.NACH_MANDATE_REGISTERED;
            case "originex.payments.CollectionInitiated"   -> NotificationTrigger.NACH_DEBIT_SUCCESS;

            default -> null;
        });
    }

    /**
     * Extracts template variables from the event JSON payload.
     * All values are strings for safe template substitution.
     */
    public Map<String, String> extractVariables(String eventType, JsonNode payload) {
        Map<String, String> vars = new HashMap<>();

        // Common fields present in most events
        safeGet(payload, "loan_id").ifPresent(v -> vars.put("loan_id", v));
        safeGet(payload, "customer_id").ifPresent(v -> vars.put("customer_id", v));
        safeGet(payload, "application_id").ifPresent(v -> vars.put("application_id", v));
        safeGet(payload, "amount").ifPresent(v -> vars.put("amount", v));
        safeGet(payload, "currency").ifPresent(v -> vars.put("currency", v));

        // Event-specific fields
        switch (eventType) {
            case "originex.lms.LoanDisbursed",
                 "originex.payments.DisbursementCompleted" -> {
                safeGet(payload, "utr").ifPresent(v -> vars.put("utr", v));
                safeGet(payload, "payment_order_id").ifPresent(v -> vars.put("payment_reference", v));
            }
            case "originex.lms.RepaymentAllocated" -> {
                safeGet(payload, "principal").ifPresent(v -> vars.put("principal_paid", v));
                safeGet(payload, "interest").ifPresent(v -> vars.put("interest_paid", v));
            }
            case "originex.los.ApplicationApproved" -> {
                safeGet(payload, "sanctioned_amount").ifPresent(v -> vars.put("sanctioned_amount", v));
            }
            case "originex.customer.KYCCompleted" -> {
                safeGet(payload, "kyc_status").ifPresent(v -> vars.put("kyc_status", v));
            }
            case "originex.payments.PaymentFailed" -> {
                safeGet(payload, "failure_reason").ifPresent(v -> vars.put("failure_reason", v));
            }
        }

        return vars;
    }

    private Optional<String> safeGet(JsonNode node, String field) {
        if (node == null || !node.has(field)) return Optional.empty();
        JsonNode val = node.get(field);
        if (val.isNull()) return Optional.empty();
        return Optional.of(val.asText());
    }
}
