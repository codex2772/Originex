package com.originex.los.domain.model;

/**
 * Loan Application Status — finite state machine.
 *
 * Transitions:
 * DRAFT → SUBMITTED → IN_PROGRESS → APPROVED → OFFER_PENDING → OFFER_ACCEPTED → DISBURSEMENT_REQUESTED
 *                                  → REFERRED → APPROVED / REJECTED
 *                                  → REJECTED (terminal)
 * OFFER_PENDING → EXPIRED (terminal)
 * Any non-terminal → WITHDRAWN (terminal)
 */
public enum ApplicationStatus {

    DRAFT,
    SUBMITTED,
    IN_PROGRESS,
    REFERRED,
    APPROVED,
    REJECTED,
    OFFER_PENDING,
    OFFER_ACCEPTED,
    OFFER_EXPIRED,
    DISBURSEMENT_REQUESTED,
    WITHDRAWN;

    public boolean isTerminal() {
        return this == REJECTED || this == OFFER_EXPIRED
                || this == WITHDRAWN || this == DISBURSEMENT_REQUESTED;
    }

    public boolean canTransitionTo(ApplicationStatus target) {
        return switch (this) {
            case DRAFT -> target == SUBMITTED || target == WITHDRAWN;
            case SUBMITTED -> target == IN_PROGRESS || target == WITHDRAWN;
            case IN_PROGRESS -> target == APPROVED || target == REJECTED
                    || target == REFERRED || target == WITHDRAWN;
            case REFERRED -> target == APPROVED || target == REJECTED || target == WITHDRAWN;
            case APPROVED -> target == OFFER_PENDING || target == WITHDRAWN;
            case OFFER_PENDING -> target == OFFER_ACCEPTED || target == OFFER_EXPIRED || target == WITHDRAWN;
            case OFFER_ACCEPTED -> target == DISBURSEMENT_REQUESTED || target == WITHDRAWN;
            default -> false; // Terminal states cannot transition
        };
    }
}
