package com.originex.lms.application.service;

import com.originex.lms.application.port.out.LoanRepository;
import com.originex.lms.domain.model.Loan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Recomputes days-past-due and asset classification for a single loan in its own
 * transaction. Kept separate from {@link DpdAgingService} so the per-loan
 * {@code @Transactional} boundary is applied through the Spring proxy (a
 * self-invoked method on the scheduler bean would bypass it).
 *
 * <p><b>Persist-only (v1):</b> DPD/NPA classification is written to the loan; no
 * Kafka event is emitted. Downstream provisioning, collections, and NPA
 * interest-suspense treatment are deferred (see {@link Loan#accrueInterest}).
 *
 * <p><b>Concurrency:</b> relies on the {@code Loan} aggregate's {@code @Version}
 * optimistic lock. A loser of a concurrent update fails and is skipped by
 * {@link DpdAgingService}. The recompute is absolute (DPD is derived from the
 * due date, not incremented), so the job is idempotent across reruns.
 */
@Component
public class DpdAgingProcessor {

    private static final Logger log = LoggerFactory.getLogger(DpdAgingProcessor.class);

    private final LoanRepository loanRepository;

    public DpdAgingProcessor(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    @Transactional
    public void ageOne(Loan candidate, LocalDate asOf) {
        // Re-load inside this transaction so the aggregate carries its child
        // collections; saving a childless loan would orphan-delete them. The
        // extra findById per loan is intentional: it preserves the shared
        // detached-merge save() and its @Version optimistic-lock behavior
        // unchanged, instead of adding a separate update path — one bounded read
        // per loan in a daily batch is an acceptable cost.
        Loan loan = loanRepository.findById(candidate.getTenantId(), candidate.getLoanId()).orElse(null);
        if (loan == null) {
            return;
        }
        int previousDpd = loan.getDpd();
        int dpd = loan.calculateDpd(asOf);
        loan.updateDpd(dpd);
        loanRepository.save(loan);

        if (dpd != previousDpd) {
            log.debug("DPD updated: loanId={}, dpd {}->{}, status={}, classification={}",
                    loan.getLoanId(), previousDpd, dpd, loan.getStatus(), loan.getAssetClassification());
        }
    }
}
