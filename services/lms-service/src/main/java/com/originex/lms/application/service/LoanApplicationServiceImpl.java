package com.originex.lms.application.service;

import com.originex.common.money.Money;
import com.originex.lms.application.port.in.LoanUseCase;
import com.originex.lms.application.port.out.LoanRepository;
import com.originex.lms.domain.exception.LoanNotFoundException;
import com.originex.lms.domain.model.Installment;
import com.originex.lms.domain.model.Loan;
import com.originex.lms.domain.service.ScheduleGenerator;
import com.originex.starter.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class LoanApplicationServiceImpl implements LoanUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoanApplicationServiceImpl.class);

    private final LoanRepository loanRepository;
    private final OutboxPublisher outboxPublisher;

    public LoanApplicationServiceImpl(LoanRepository loanRepository,
                                      OutboxPublisher outboxPublisher) {
        this.loanRepository = loanRepository;
        this.outboxPublisher = outboxPublisher;
    }

    @Override
    public Loan createLoan(CreateLoanCommand command) {
        log.info("Creating loan: applicationId={}, amount={}", command.applicationId(), command.sanctionedAmount());

        String currency = command.currency() != null ? command.currency() : "INR";
        Money sanctioned = Money.of(command.sanctionedAmount(), currency);
        BigDecimal rate = new BigDecimal(command.interestRate());
        Money emi = Money.of(command.emiAmount(), currency);

        Loan loan = Loan.createFromApplication(
                command.tenantId(), command.customerId(), command.applicationId(),
                command.productCode(), sanctioned, rate, command.rateType(),
                command.tenureMonths(), emi
        );

        // Generate amortization schedule
        LocalDate firstDueDate = LocalDate.now().plusMonths(1).withDayOfMonth(5);
        BigDecimal annualRate = rate.movePointLeft(2); // e.g., "12.5" → 0.125
        List<Installment> schedule = ScheduleGenerator.generate(sanctioned, annualRate, command.tenureMonths(), firstDueDate);
        loan.setSchedule(schedule);

        // The accepted offer is a disbursement instruction: initiate disbursement to the
        // resolved beneficiary (CREATED → PENDING_DISBURSAL, creates an INITIATED
        // disbursement) and ask the payment rail to execute the transfer via LoanDisbursed.
        // On payments.DisbursementCompleted the loan is confirmed ACTIVE (PaymentEventConsumer).
        loan.initiateDisbursement(sanctioned, command.beneficiaryAccount());

        Loan saved = loanRepository.save(loan);
        log.info("Loan created and disbursement initiated: loanId={}, accountNumber={}",
                saved.getLoanId(), saved.getLoanAccountNumber());

        outboxPublisher.publish("Loan", saved.getLoanId(),
                "originex.lms.LoanDisbursed", command.tenantId(),
                String.format("{\"loan_id\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\"," +
                                "\"beneficiary_account\":\"%s\",\"beneficiary_ifsc\":\"%s\"," +
                                "\"beneficiary_name\":\"%s\",\"customer_id\":\"%s\"}",
                                saved.getLoanId(), sanctioned.getAmount().toPlainString(), currency,
                                command.beneficiaryAccount(), command.beneficiaryIfsc(),
                                command.beneficiaryName(), command.customerId())
                        .getBytes(StandardCharsets.UTF_8));

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Loan getLoan(UUID tenantId, UUID loanId) {
        return loanRepository.findById(tenantId, loanId)
                .orElseThrow(() -> new LoanNotFoundException(loanId));
    }

    @Override
    public Loan disburseLoan(UUID tenantId, UUID loanId, String beneficiaryAccount) {
        Loan loan = getLoan(tenantId, loanId);
        loan.initiateDisbursement(loan.getSanctionedAmount(), beneficiaryAccount);
        return loanRepository.save(loan);
    }

    @Override
    public Loan confirmDisbursement(UUID tenantId, UUID loanId, UUID disbursementId, String paymentRef) {
        Loan loan = getLoan(tenantId, loanId);
        loan.confirmDisbursement(disbursementId, paymentRef);

        Loan saved = loanRepository.save(loan);
        log.info("Loan disbursed: loanId={}, amount={}", loanId, saved.getDisbursedAmount());

        outboxPublisher.publish("Loan", saved.getLoanId(),
                "originex.lms.LoanDisbursed", tenantId,
                String.format("{\"loan_id\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\"}",
                        saved.getLoanId(), saved.getDisbursedAmount().getAmount().toPlainString(), saved.getCurrency())
                        .getBytes(StandardCharsets.UTF_8));

        return saved;
    }

    @Override
    public Loan.RepaymentAllocation recordRepayment(RecordRepaymentCommand command) {
        Loan loan = getLoan(command.tenantId(), command.loanId());

        String currency = command.currency() != null ? command.currency() : loan.getCurrency();
        Money paymentAmount = Money.of(command.amount(), currency);

        Loan.RepaymentAllocation allocation = loan.allocateRepayment(paymentAmount);
        loanRepository.save(loan);

        log.info("Repayment allocated: loanId={}, principal={}, interest={}, charges={}",
                command.loanId(),
                allocation.principalAllocated().getAmount(),
                allocation.interestAllocated().getAmount(),
                allocation.chargesAllocated().getAmount());

        outboxPublisher.publish("Loan", command.loanId(),
                "originex.lms.RepaymentAllocated", command.tenantId(),
                String.format("{\"loan_id\":\"%s\",\"principal\":\"%s\",\"interest\":\"%s\",\"charges\":\"%s\"}",
                        command.loanId(),
                        allocation.principalAllocated().getAmount().toPlainString(),
                        allocation.interestAllocated().getAmount().toPlainString(),
                        allocation.chargesAllocated().getAmount().toPlainString())
                        .getBytes(StandardCharsets.UTF_8));

        return allocation;
    }

    @Override
    public Loan confirmDisbursementByPayment(UUID tenantId, UUID loanId, UUID paymentOrderId, String utr) {
        Loan loan = getLoan(tenantId, loanId);

        // Find the most recent INITIATED disbursement and confirm it
        loan.getDisbursements().stream()
                .filter(d -> d.getStatus() == com.originex.lms.domain.model.Disbursement.DisbursementStatus.INITIATED)
                .findFirst()
                .ifPresent(d -> loan.confirmDisbursement(d.getDisbursementId(), utr));

        Loan saved = loanRepository.save(loan);
        log.info("Disbursement confirmed by payment: loanId={}, utr={}", loanId, utr);

        outboxPublisher.publish("Loan", loanId,
                "originex.lms.DisbursementConfirmed", tenantId,
                String.format("{\"loan_id\":\"%s\",\"utr\":\"%s\",\"payment_order_id\":\"%s\"}",
                        loanId, utr, paymentOrderId).getBytes(StandardCharsets.UTF_8));

        return saved;
    }

    @Override
    public Loan.RepaymentAllocation allocateRepaymentFromPayment(UUID tenantId, UUID loanId,
                                                                  String amount, String currency) {
        return recordRepayment(new RecordRepaymentCommand(tenantId, loanId, amount, currency, null));
    }
}
