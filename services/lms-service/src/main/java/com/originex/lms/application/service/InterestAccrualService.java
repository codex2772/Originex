package com.originex.lms.application.service;

import com.originex.lms.application.port.out.LoanRepository;
import com.originex.lms.domain.model.Loan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Daily interest-accrual scheduler. Iterates ACTIVE loans that have not yet been
 * accrued for the current business day and delegates each to
 * {@link InterestAccrualProcessor} (one transaction per loan).
 *
 * <p><b>Idempotency &amp; restart-safety:</b> each processed loan's
 * {@code last_accrual_date} is advanced to the run date, so a loan is accrued at
 * most once per day and a crashed/restarted run resumes with the unprocessed
 * remainder (already-accrued loans drop out of the eligibility query). Iteration
 * uses a keyset cursor on {@code loanId}, so a genuinely failing loan is skipped
 * for the rest of the run (retried next day) rather than looping.
 *
 * <p><b>Concurrency:</b> safe under multiple instances via the Loan aggregate's
 * {@code @Version} optimistic lock — see {@link InterestAccrualProcessor}. The
 * loser of a concurrent race surfaces as {@link OptimisticLockingFailureException}
 * here and is skipped (the loan was already accrued by the winner).
 *
 * <p><b>Disable:</b> set {@code originex.lms.accrual.enabled=false} — this bean
 * is then not created and the job never registers.
 */
@Service
@ConditionalOnProperty(name = "originex.lms.accrual.enabled", havingValue = "true", matchIfMissing = true)
public class InterestAccrualService {

    private static final Logger log = LoggerFactory.getLogger(InterestAccrualService.class);

    private final LoanRepository loanRepository;
    private final InterestAccrualProcessor processor;
    private final int batchSize;
    private final ZoneId zone;

    public InterestAccrualService(LoanRepository loanRepository,
                                  InterestAccrualProcessor processor,
                                  @Value("${originex.lms.accrual.batch-size:500}") int batchSize,
                                  @Value("${originex.lms.accrual.zone:Asia/Kolkata}") String zone) {
        this.loanRepository = loanRepository;
        this.processor = processor;
        this.batchSize = batchSize;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(cron = "${originex.lms.accrual.cron:0 30 0 * * *}", zone = "${originex.lms.accrual.zone:Asia/Kolkata}")
    public void runDailyAccrual() {
        LocalDate asOf = LocalDate.now(zone);
        log.info("Interest accrual run starting: asOf={}", asOf);

        UUID cursor = null;
        int processed = 0;
        int skipped = 0;

        while (true) {
            List<Loan> batch = loanRepository.findAccruable(asOf, cursor, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            for (Loan loan : batch) {
                try {
                    processor.accrueOne(loan, asOf);
                    processed++;
                } catch (OptimisticLockingFailureException e) {
                    // Another instance accrued this loan concurrently — benign.
                    skipped++;
                    log.debug("Accrual skipped (concurrent update): loanId={}", loan.getLoanId());
                } catch (RuntimeException e) {
                    // Isolate one loan's failure; it stays eligible and is retried next run.
                    skipped++;
                    log.error("Accrual failed for loanId={} (will retry next run): {}",
                            loan.getLoanId(), e.getMessage());
                }
                cursor = loan.getLoanId();
            }
        }
        log.info("Interest accrual run complete: asOf={}, processed={}, skipped={}", asOf, processed, skipped);
    }
}
