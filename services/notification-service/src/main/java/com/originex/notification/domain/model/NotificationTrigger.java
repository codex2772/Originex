package com.originex.notification.domain.model;

/**
 * Every notification event type that can trigger a notification.
 * Maps to a template in the database.
 * RBI-mandated triggers are annotated with @RBI.
 */
public enum NotificationTrigger {

    // ─── Customer ───
    CUSTOMER_REGISTERED,
    KYC_SUBMITTED,
    KYC_COMPLETED,          // @RBI — identity verified
    KYC_REJECTED,

    // ─── Loan Origination ───
    APPLICATION_SUBMITTED,  // @RBI — acknowledgement within 24 hours
    APPLICATION_IN_PROGRESS,
    CREDIT_CHECK_COMPLETED,
    APPLICATION_APPROVED,   // @RBI — sanction letter
    APPLICATION_REJECTED,   // @RBI — rejection reason mandatory
    OFFER_GENERATED,        // @RBI — Key Fact Statement (KFS)
    OFFER_EXPIRING,
    OFFER_ACCEPTED,
    APPLICATION_WITHDRAWN,

    // ─── Loan Management ───
    LOAN_DISBURSED,         // @RBI — disbursement confirmation with UTR
    EMI_DUE_D5,             // @RBI — reminder 5 days before due
    EMI_DUE_D1,             // @RBI — reminder 1 day before due
    EMI_DUE_TODAY,          // @RBI — due date reminder
    EMI_OVERDUE,            // @RBI — overdue notice
    REPAYMENT_RECEIVED,     // @RBI — receipt with transaction ref
    LOAN_CLOSED,            // @RBI — closure confirmation
    NPA_CLASSIFIED,         // @RBI — NPA notice
    PREPAYMENT_PROCESSED,

    // ─── Payment ───
    DISBURSEMENT_INITIATED,
    DISBURSEMENT_COMPLETED, // @RBI — with UTR number
    PAYMENT_FAILED,
    NACH_MANDATE_REGISTERED,
    NACH_DEBIT_SUCCESS,
    NACH_DEBIT_FAILED,      // @RBI — bounce notification

    // ─── Collections ───
    COLLECTION_NOTICE_1,    // @RBI — first dunning notice
    COLLECTION_NOTICE_2,    // @RBI — second dunning notice
    SETTLEMENT_OFFER,

    // ─── System ───
    OTP_VERIFICATION,
    PASSWORD_RESET,
    CONSENT_REQUESTED,
    CONSENT_RECORDED
}
