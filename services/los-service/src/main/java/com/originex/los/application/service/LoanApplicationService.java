package com.originex.los.application.service;

import com.originex.common.money.Money;
import com.originex.los.application.port.in.LoanApplicationUseCase;
import com.originex.los.application.port.out.BREPort;
import com.originex.los.application.port.out.CreditBureauPort;
import com.originex.los.application.port.out.CustomerVerificationPort;
import com.originex.los.application.port.out.CustomerVerificationPort.CustomerEligibility;
import com.originex.los.application.port.out.LoanApplicationRepository;
import com.originex.los.domain.exception.ApplicationNotFoundException;
import com.originex.los.domain.model.ApplicationDocument;
import com.originex.los.domain.model.LoanApplication;
import com.originex.starter.outbox.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * LOS Application Service — orchestrates the loan application lifecycle.
 */
@Service
@Transactional
public class LoanApplicationService implements LoanApplicationUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoanApplicationService.class);
    private static final int DUPLICATE_CHECK_DAYS = 30;
    private static final int OFFER_VALIDITY_DAYS = 7;

    private final LoanApplicationRepository applicationRepository;
    private final CustomerVerificationPort customerVerificationPort;
    private final CreditBureauPort creditBureauPort;
    private final BREPort brePort;
    private final OutboxPublisher outboxPublisher;

    public LoanApplicationService(LoanApplicationRepository applicationRepository,
                                  CustomerVerificationPort customerVerificationPort,
                                  CreditBureauPort creditBureauPort,
                                  BREPort brePort,
                                  OutboxPublisher outboxPublisher) {
        this.applicationRepository = applicationRepository;
        this.customerVerificationPort = customerVerificationPort;
        this.creditBureauPort = creditBureauPort;
        this.brePort = brePort;
        this.outboxPublisher = outboxPublisher;
    }

    @Override
    public LoanApplication submitApplication(SubmitApplicationCommand command) {
        log.info("Submitting loan application: customer={}, product={}, amount={}",
                command.customerId(), command.productCode(), command.amount());

        // 1. Verify customer eligibility (KYC must be complete)
        CustomerEligibility eligibility = customerVerificationPort.verifyCustomerEligibility(
                command.tenantId().toString(), command.customerId().toString());

        if (!eligibility.isEligible()) {
            throw new IllegalStateException(
                    "Customer not eligible for loan application: " + eligibility.reason());
        }

        // 2. Duplicate application check (same customer + product within 30 days)
        if (applicationRepository.existsByCustomerAndProduct(
                command.tenantId(), command.customerId(), command.productCode(), DUPLICATE_CHECK_DAYS)) {
            throw new IllegalArgumentException(
                    "Duplicate application: customer already has an active application for this product");
        }

        // 3. Create domain aggregate
        String currency = command.currency() != null ? command.currency() : "INR";
        Money requestedAmount = Money.of(command.amount(), currency);
        Money monthlyIncome = command.monthlyIncome() != null
                ? Money.of(command.monthlyIncome(), currency)
                : null;

        LoanApplication application = LoanApplication.submit(
                command.tenantId(),
                command.customerId(),
                command.productCode(),
                requestedAmount,
                command.tenureMonths(),
                command.purpose(),
                command.channel(),
                command.applicantName() != null ? command.applicantName() : eligibility.customerName(),
                command.applicantPan(),
                command.employmentType(),
                monthlyIncome
        );

        // 4. Persist
        LoanApplication saved = applicationRepository.save(application);

        log.info("Application submitted: id={}, status={}", saved.getApplicationId(), saved.getStatus());

        outboxPublisher.publish("LoanApplication", saved.getApplicationId(),
                "originex.los.ApplicationSubmitted", command.tenantId(),
                buildApplicationSubmittedPayload(saved));

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public LoanApplication getApplication(UUID tenantId, UUID applicationId) {
        return applicationRepository.findById(tenantId, applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }

    @Override
    public LoanApplication addDocument(AddDocumentCommand command) {
        LoanApplication app = applicationRepository.findById(command.tenantId(), command.applicationId())
                .orElseThrow(() -> new ApplicationNotFoundException(command.applicationId()));

        ApplicationDocument.DocumentType type = ApplicationDocument.DocumentType.valueOf(command.documentType());
        ApplicationDocument doc = ApplicationDocument.upload(type, command.fileName(), command.storageUrl());
        app.addDocument(doc);

        return applicationRepository.save(app);
    }

    @Override
    public LoanApplication recordCreditResult(RecordCreditResultCommand command) {
        LoanApplication app = applicationRepository.findById(command.tenantId(), command.applicationId())
                .orElseThrow(() -> new ApplicationNotFoundException(command.applicationId()));

        app.startProcessing();
        app.recordCreditCheck(command.creditScore(), command.bureau(), command.reportRef());

        LoanApplication saved = applicationRepository.save(app);
        log.info("Credit result recorded: appId={}, score={}", command.applicationId(), command.creditScore());

        outboxPublisher.publish("LoanApplication", command.applicationId(),
                "originex.los.CreditCheckCompleted", command.tenantId(),
                String.format("{\"application_id\":\"%s\",\"score\":%d}", command.applicationId(), command.creditScore())
                        .getBytes(StandardCharsets.UTF_8));

        return saved;
    }

    @Override
    public LoanApplication initiateCreditCheck(UUID tenantId, UUID applicationId, String consentArtifactId) {
        LoanApplication app = applicationRepository.findById(tenantId, applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        log.info("Initiating live credit bureau pull: appId={}, pan={}", applicationId,
                mask(app.getApplicantPan()));

        // ─── Step 1: Pull credit bureau report ───
        CreditBureauPort.BureauCheckResult bureauResult = creditBureauPort.pullCreditReport(
                new CreditBureauPort.CreditCheckRequest(
                        tenantId.toString(), applicationId.toString(),
                        app.getApplicantPan(), app.getApplicantName(),
                        null, null, consentArtifactId
                ));

        if (!bureauResult.success()) {
            log.warn("Credit bureau pull returned no result: appId={}, reason={}",
                    applicationId, bureauResult.failureReason());
        }

        app.startProcessing();
        app.recordCreditCheck(bureauResult.creditScore(), bureauResult.bureauName(),
                bureauResult.reportReference());

        outboxPublisher.publish("LoanApplication", applicationId,
                "originex.los.CreditCheckCompleted", tenantId,
                String.format("{\"application_id\":\"%s\",\"bureau\":\"%s\",\"score\":%d}",
                        applicationId, bureauResult.bureauName(), bureauResult.creditScore())
                        .getBytes(StandardCharsets.UTF_8));

        // ─── Step 2: BRE eligibility evaluation ───
        String currency = app.getRequestedAmount().getCurrencyCode();
        BigDecimal monthlyIncome = app.getMonthlyIncome() != null
                ? app.getMonthlyIncome().getAmount()
                : BigDecimal.ZERO;

        BREPort.BREResult breResult = brePort.evaluate(new BREPort.BRERequest(
                tenantId, applicationId.toString(), app.getCustomerId().toString(),
                app.getProductCode(),
                app.getEmploymentType() != null ? app.getEmploymentType() : "SALARIED",
                bureauResult.creditScore(),
                bureauResult.bureauName() != null ? bureauResult.bureauName() : "CIBIL",
                false, false, 0, 0,
                BigDecimal.ZERO,
                monthlyIncome,
                0,  // age — future: fetch from customer profile
                app.getRequestedAmount().getAmount(),
                app.getRequestedTenureMonths(),
                currency
        ));

        log.info("BRE decision: appId={}, decision={}, riskGrade={}",
                applicationId, breResult.decision(), breResult.riskGrade());

        // ─── Step 3: Auto-decision based on BRE result ───
        LoanApplication saved;
        if (breResult.isRejected()) {
            app.reject(breResult.summary() != null ? breResult.summary() : "Application does not meet eligibility criteria");
            saved = applicationRepository.save(app);
            outboxPublisher.publish("LoanApplication", applicationId,
                    "originex.los.ApplicationRejected", tenantId,
                    String.format("{\"application_id\":\"%s\",\"reason\":\"%s\"}",
                            applicationId, breResult.summary())
                            .getBytes(StandardCharsets.UTF_8));

        } else if (breResult.isApproved() && breResult.approvedAmount() != null) {
            // Auto-approve with BRE-calculated offer
            app.approve("Auto-approved by BRE. Risk grade: " + breResult.riskGrade());
            Instant expiresAt = Instant.now().plus(OFFER_VALIDITY_DAYS, ChronoUnit.DAYS);
            app.generateOffer(
                    Money.of(breResult.approvedAmount(), currency),
                    breResult.interestRate(),
                    breResult.approvedTenureMonths() > 0
                            ? breResult.approvedTenureMonths()
                            : app.getRequestedTenureMonths(),
                    Money.of(breResult.emi(), currency),
                    Money.of(breResult.processingFeeRate()
                            .multiply(breResult.approvedAmount())
                            .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_EVEN), currency),
                    breResult.apr(),
                    expiresAt
            );
            saved = applicationRepository.save(app);
            outboxPublisher.publish("LoanApplication", applicationId,
                    "originex.los.ApplicationApproved", tenantId,
                    String.format("{\"application_id\":\"%s\",\"sanctioned_amount\":\"%s\",\"currency\":\"%s\",\"risk_grade\":\"%s\"}",
                            applicationId, breResult.approvedAmount().toPlainString(),
                            currency, breResult.riskGrade())
                            .getBytes(StandardCharsets.UTF_8));
        } else {
            // REFER_TO_UNDERWRITER — move to REFERRED so the application becomes a
            // queryable manual-review case. Previously it was left in IN_PROGRESS,
            // which had no exit path (neither auto-decisioned nor actionable).
            // assignedTo is null at auto-referral time; assignment/queueing is out
            // of scope. No new event is published — CreditCheckCompleted was already
            // emitted earlier in this method.
            app.refer(null);
            saved = applicationRepository.save(app);
            log.info("Application referred for manual review: appId={}, reason={}", applicationId, breResult.summary());
        }

        return saved;
    }

    private String mask(String pan) {
        if (pan == null || pan.length() < 4) return "****";
        return "*".repeat(pan.length() - 4) + pan.substring(pan.length() - 4);
    }

    @Override
    public LoanApplication approveAndGenerateOffer(ApproveCommand command) {
        LoanApplication app = applicationRepository.findById(command.tenantId(), command.applicationId())
                .orElseThrow(() -> new ApplicationNotFoundException(command.applicationId()));

        // Approve
        app.approve(command.notes());

        // Generate offer
        String currency = app.getRequestedAmount().getCurrencyCode();
        Money sanctionedAmount = Money.of(command.sanctionedAmount(), currency);
        BigDecimal interestRate = new BigDecimal(command.interestRate());
        Money emi = Money.of(command.emi(), currency);
        Money processingFee = Money.of(command.processingFee(), currency);
        BigDecimal apr = new BigDecimal(command.apr());
        Instant expiresAt = Instant.now().plus(OFFER_VALIDITY_DAYS, ChronoUnit.DAYS);

        app.generateOffer(sanctionedAmount, interestRate, command.tenureMonths(),
                emi, processingFee, apr, expiresAt);

        LoanApplication saved = applicationRepository.save(app);
        log.info("Application approved with offer: appId={}, sanctioned={}",
                command.applicationId(), command.sanctionedAmount());

        outboxPublisher.publish("LoanApplication", command.applicationId(),
                "originex.los.ApplicationApproved", command.tenantId(),
                String.format("{\"application_id\":\"%s\",\"sanctioned_amount\":\"%s\",\"currency\":\"%s\"}",
                        command.applicationId(), command.sanctionedAmount(), currency)
                        .getBytes(StandardCharsets.UTF_8));

        return saved;
    }

    @Override
    public LoanApplication rejectApplication(RejectCommand command) {
        LoanApplication app = applicationRepository.findById(command.tenantId(), command.applicationId())
                .orElseThrow(() -> new ApplicationNotFoundException(command.applicationId()));

        // Domain owns the rule (transition guard + decision notes); this is
        // orchestration only. Mirrors the auto-reject publish in initiateCreditCheck.
        app.reject(command.reason());

        LoanApplication saved = applicationRepository.save(app);
        log.info("Application rejected: appId={}", command.applicationId());

        outboxPublisher.publish("LoanApplication", command.applicationId(),
                "originex.los.ApplicationRejected", command.tenantId(),
                String.format("{\"application_id\":\"%s\",\"reason\":\"%s\"}",
                        command.applicationId(), command.reason())
                        .getBytes(StandardCharsets.UTF_8));

        return saved;
    }

    @Override
    public LoanApplication acceptOffer(UUID tenantId, UUID applicationId) {
        LoanApplication app = applicationRepository.findById(tenantId, applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        // Resolve the disbursement beneficiary before transitioning, so a missing
        // bank account fails cleanly (app stays OFFER_PENDING, retryable) rather
        // than producing a loan that can never be disbursed.
        CustomerVerificationPort.BeneficiaryAccount beneficiary =
                customerVerificationPort.getPrimaryBankAccount(tenantId.toString(), app.getCustomerId().toString());
        if (beneficiary == null || beneficiary.accountNumber() == null || beneficiary.ifscCode() == null) {
            throw new IllegalStateException(
                    "No bank account on file for disbursement; add a bank account before accepting the offer");
        }

        app.acceptOffer();
        app.requestDisbursement();

        LoanApplication saved = applicationRepository.save(app);
        log.info("Offer accepted, disbursement requested: appId={}", applicationId);

        outboxPublisher.publish("LoanApplication", applicationId,
                "originex.los.DisbursementRequested", tenantId,
                buildDisbursementRequestedPayload(saved, beneficiary));

        return saved;
    }

    @Override
    public LoanApplication withdrawApplication(UUID tenantId, UUID applicationId) {
        LoanApplication app = applicationRepository.findById(tenantId, applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        app.withdraw();

        LoanApplication saved = applicationRepository.save(app);
        log.info("Application withdrawn: appId={}", applicationId);
        return saved;
    }

    // ─── Event Payload Builders ───

    private byte[] buildApplicationSubmittedPayload(LoanApplication app) {
        return String.format(
                "{\"application_id\":\"%s\",\"customer_id\":\"%s\",\"product_code\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\",\"tenure_months\":%d}",
                app.getApplicationId(), app.getCustomerId(), app.getProductCode(),
                app.getRequestedAmount().getAmount().toPlainString(),
                app.getRequestedAmount().getCurrencyCode(), app.getRequestedTenureMonths()
        ).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildDisbursementRequestedPayload(LoanApplication app,
                                                     CustomerVerificationPort.BeneficiaryAccount beneficiary) {
        var offer = app.getCurrentOffer();
        return String.format(
                "{\"application_id\":\"%s\",\"customer_id\":\"%s\",\"product_code\":\"%s\"," +
                        "\"sanctioned_amount\":\"%s\",\"interest_rate\":\"%s\",\"rate_type\":\"FIXED\"," +
                        "\"tenure_months\":%d,\"emi\":\"%s\",\"currency\":\"%s\"," +
                        "\"beneficiary_account\":\"%s\",\"beneficiary_ifsc\":\"%s\"," +
                        "\"beneficiary_name\":\"%s\",\"beneficiary_bank\":\"%s\"}",
                app.getApplicationId(), app.getCustomerId(), app.getProductCode(),
                offer.getSanctionedAmount().getAmount().toPlainString(),
                offer.getInterestRate().toPlainString(),
                offer.getTenureMonths(), offer.getEmi().getAmount().toPlainString(),
                offer.getSanctionedAmount().getCurrencyCode(),
                beneficiary.accountNumber(), beneficiary.ifscCode(),
                beneficiary.accountHolderName(), beneficiary.bankName()
        ).getBytes(StandardCharsets.UTF_8);
    }
}
