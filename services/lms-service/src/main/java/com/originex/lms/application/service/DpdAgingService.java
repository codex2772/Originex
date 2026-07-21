package com.originex.lms.application.service;

import com.originex.common.tenant.SystemContextHolder;
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
 * Daily DPD / NPA aging scheduler. Iterates ACTIVE/NPA loans whose delinquency
 * needs recomputing for the current business day and delegates each to
 * {@link DpdAgingProcessor} (one transaction per loan).
 *
 * <p><b>Classification (v1):</b> DPD is the calendar days since the oldest unpaid
 * installment's due date; at 90+ DPD an ACTIVE loan becomes NPA / SUB_STANDARD,
 * escalating to DOUBTFUL (365+) and LOSS (730+) per the {@code Loan} aggregate's
 * existing RBI thresholds. Classification is persisted only — no Kafka event, no
 * provisioning postings, and no NPA→ACTIVE auto-upgrade (all deferred to a later
 * increment).
 *
 * <p><b>Idempotency &amp; restart-safety:</b> DPD is recomputed absolutely from
 * the due date (not incremented), so any rerun converges to the same value.
 * Iteration uses a keyset cursor on {@code loanId}; a genuinely failing loan is
 * skipped for the rest of the run and retried next day.
 *
 * <p><b>Concurrency:</b> safe under multiple instances via the {@code Loan}
 * aggregate's {@code @Version} optimistic lock — the loser surfaces as
 * {@link OptimisticLockingFailureException} here and is skipped.
 *
 * <p><b>Disable:</b> set {@code originex.lms.dpd.enabled=false} — this bean is
 * then not created and the job never registers.
 */
@Service
@ConditionalOnProperty(name = "originex.lms.dpd.enabled", havingValue = "true", matchIfMissing = true)
public class DpdAgingService {

    private static final Logger log = LoggerFactory.getLogger(DpdAgingService.class);

    private final LoanRepository loanRepository;
    private final DpdAgingProcessor processor;
    private final int batchSize;
    private final ZoneId zone;

    public DpdAgingService(LoanRepository loanRepository,
                           DpdAgingProcessor processor,
                           @Value("${originex.lms.dpd.batch-size:500}") int batchSize,
                           @Value("${originex.lms.dpd.zone:Asia/Kolkata}") String zone) {
        this.loanRepository = loanRepository;
        this.processor = processor;
        this.batchSize = batchSize;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(cron = "${originex.lms.dpd.cron:0 0 1 * * *}", zone = "${originex.lms.dpd.zone:Asia/Kolkata}")
    public void runDailyAging() {
        // Cross-tenant sweep: run the whole eligibility scan + per-loan processing
        // in system context so — when RLS is enabled — the routing datasource
        // acquires the BYPASSRLS (system-route) connection and the scan sees loans
        // across all tenants. System context is entered here, *outside* the
        // per-loan @Transactional boundary (DpdAgingProcessor), so the route is
        // chosen when each transaction acquires its connection. runAsSystem
        // guarantees the context is cleared in a finally block, even if the scan or
        // a loan throws. See dev/RLS_DESIGN.md §5, §7.2.
        SystemContextHolder.runAsSystem(() -> {
            LocalDate asOf = LocalDate.now(zone);
            log.info("DPD aging run starting: asOf={}", asOf);

            UUID cursor = null;
            int processed = 0;
            int skipped = 0;

            while (true) {
                List<Loan> batch = loanRepository.findDelinquent(asOf, cursor, batchSize);
                if (batch.isEmpty()) {
                    break;
                }
                for (Loan loan : batch) {
                    try {
                        processor.ageOne(loan, asOf);
                        processed++;
                    } catch (OptimisticLockingFailureException e) {
                        // Another instance aged this loan concurrently — benign.
                        skipped++;
                        log.debug("DPD aging skipped (concurrent update): loanId={}", loan.getLoanId());
                    } catch (RuntimeException e) {
                        // Isolate one loan's failure; it stays eligible and is retried next run.
                        skipped++;
                        log.error("DPD aging failed for loanId={} (will retry next run): {}",
                                loan.getLoanId(), e.getMessage());
                    }
                    cursor = loan.getLoanId();
                }
            }
            log.info("DPD aging run complete: asOf={}, processed={}, skipped={}", asOf, processed, skipped);
        });
    }
}
