package com.originex.lms.application.service;

import com.originex.common.money.Money;
import com.originex.lms.application.port.out.LoanRepository;
import com.originex.lms.domain.model.Loan;
import com.originex.lms.domain.service.InterestAccrualCalculator;
import com.originex.starter.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Applies interest accrual for a single loan in its own transaction, so the
 * loan update and the {@code InterestAccrued} outbox write commit atomically
 * (the transactional-outbox invariant) and one loan's failure never rolls back
 * another's. Kept separate from {@link InterestAccrualService} so the per-loan
 * {@code @Transactional} boundary is applied through the Spring proxy — a
 * self-invoked method on the scheduler bean would bypass it.
 *
 * <p><b>Concurrency:</b> relies on the {@code Loan} aggregate's existing
 * {@code @Version} optimistic lock. If two service instances accrue the same
 * loan concurrently, the second commit fails with an optimistic-lock exception
 * and its whole transaction — including the outbox row — rolls back, so there is
 * no double accrual and no duplicate event. The winner's advanced
 * {@code last_accrual_date} then excludes the loan from further runs.
 */
@Component
public class InterestAccrualProcessor {

    private static final Logger log = LoggerFactory.getLogger(InterestAccrualProcessor.class);

    private final LoanRepository loanRepository;
    private final OutboxPublisher outboxPublisher;

    public InterestAccrualProcessor(LoanRepository loanRepository, OutboxPublisher outboxPublisher) {
        this.loanRepository = loanRepository;
        this.outboxPublisher = outboxPublisher;
    }

    /**
     * Accrues interest for one loan up to {@code asOf} and advances its
     * last-accrual marker, in a single transaction. Publishes
     * {@code originex.lms.InterestAccrued} (via the outbox) only when a positive
     * amount was accrued — a zero result (baseline / fully-repaid loan) just
     * advances the marker so the loan isn't re-selected for the same day.
     */
    @Transactional
    public void accrueOne(Loan loan, LocalDate asOf) {
        Money accrued = InterestAccrualCalculator.accrualFor(loan, asOf);

        if (accrued.isPositive()) {
            loan.accrueInterest(accrued);
        }
        loan.setLastAccrualDate(asOf);
        Loan saved = loanRepository.save(loan);

        if (accrued.isPositive()) {
            outboxPublisher.publish("Loan", saved.getLoanId(),
                    "originex.lms.InterestAccrued", saved.getTenantId(),
                    String.format("{\"loan_id\":\"%s\",\"accrued_amount\":\"%s\",\"currency\":\"%s\"}",
                                    saved.getLoanId(), accrued.getAmount().toPlainString(), saved.getCurrency())
                            .getBytes(StandardCharsets.UTF_8));
            log.debug("Interest accrued: loanId={}, amount={}", saved.getLoanId(), accrued.getAmount());
        }
    }
}
