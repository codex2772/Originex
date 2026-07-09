package com.originex.lms.domain.model;

public enum LoanStatus {
    CREATED,
    PENDING_DISBURSAL,
    DISBURSEMENT_FAILED,
    ACTIVE,
    NPA,
    RESTRUCTURED,
    FORECLOSED,
    MATURED,
    WRITTEN_OFF,
    SETTLED,
    CANCELLED;

    public boolean isTerminal() {
        return this == FORECLOSED || this == MATURED || this == WRITTEN_OFF
                || this == SETTLED || this == CANCELLED;
    }

    public boolean canTransitionTo(LoanStatus target) {
        return switch (this) {
            case CREATED -> target == PENDING_DISBURSAL || target == CANCELLED;
            case PENDING_DISBURSAL -> target == ACTIVE || target == DISBURSEMENT_FAILED;
            case DISBURSEMENT_FAILED -> target == PENDING_DISBURSAL || target == CANCELLED;
            case ACTIVE -> target == NPA || target == FORECLOSED || target == MATURED
                    || target == RESTRUCTURED || target == SETTLED;
            case NPA -> target == ACTIVE || target == WRITTEN_OFF || target == SETTLED || target == FORECLOSED;
            case RESTRUCTURED -> target == ACTIVE;
            default -> false;
        };
    }
}
