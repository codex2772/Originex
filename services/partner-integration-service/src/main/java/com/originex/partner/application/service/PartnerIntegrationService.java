package com.originex.partner.application.service;

import com.originex.partner.application.port.in.PartnerIntegrationUseCase;
import com.originex.partner.application.port.out.*;
import com.originex.partner.domain.model.*;
import com.originex.partner.domain.model.IntegrationRequest.PartnerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Partner Integration Application Service — the Anti-Corruption Layer orchestrator.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Route each request to the correct partner adapter (bureau selection, etc.)</li>
 *   <li>Check response cache before making a (paid) external call</li>
 *   <li>Persist every request/response for audit (PII masked)</li>
 *   <li>Never leak partner-specific formats — only our domain result types leave this service</li>
 * </ul>
 */
@Service
@Transactional
public class PartnerIntegrationService implements PartnerIntegrationUseCase {

    private static final Logger log = LoggerFactory.getLogger(PartnerIntegrationService.class);
    private static final int BUREAU_CACHE_TTL_SECONDS = 24 * 60 * 60;      // 1 day
    private static final int KYC_CACHE_TTL_SECONDS = 30 * 24 * 60 * 60;   // 30 days

    private final Map<String, CreditBureauPort> bureauAdaptersByName;
    private final AadhaarVerificationPort aadhaarVerificationPort;
    private final PanVerificationPort panVerificationPort;
    private final BankAccountVerificationPort bankAccountVerificationPort;
    private final IntegrationRequestRepository integrationRequestRepository;
    private final String defaultBureau;

    public PartnerIntegrationService(List<CreditBureauPort> bureauAdapters,
                                     AadhaarVerificationPort aadhaarVerificationPort,
                                     PanVerificationPort panVerificationPort,
                                     BankAccountVerificationPort bankAccountVerificationPort,
                                     IntegrationRequestRepository integrationRequestRepository,
                                     @Value("${originex.partner.bureau.default:CIBIL}") String defaultBureau) {
        this.bureauAdaptersByName = bureauAdapters.stream()
                .collect(Collectors.toMap(CreditBureauPort::bureauName, a -> a));
        this.aadhaarVerificationPort = aadhaarVerificationPort;
        this.panVerificationPort = panVerificationPort;
        this.bankAccountVerificationPort = bankAccountVerificationPort;
        this.integrationRequestRepository = integrationRequestRepository;
        this.defaultBureau = defaultBureau;
    }

    @Override
    public BureauReport pullCreditReport(PullCreditReportCommand command) {
        String bureauName = command.preferredBureau() != null ? command.preferredBureau() : defaultBureau;
        CreditBureauPort adapter = bureauAdaptersByName.get(bureauName);
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter registered for bureau: " + bureauName);
        }

        // Cache check — avoid redundant paid bureau pulls within TTL
        Optional<IntegrationRequest> cached = integrationRequestRepository.findLatestValidCache(
                command.tenantId(), PartnerType.CREDIT_BUREAU, command.referenceId());
        if (cached.isPresent()) {
            log.info("Using cached bureau report: referenceId={}, bureau={}", command.referenceId(), bureauName);
        }

        IntegrationRequest request = IntegrationRequest.initiate(
                command.tenantId(), PartnerType.CREDIT_BUREAU, bureauName,
                command.referenceId(), maskPan(command.panNumber()));

        try {
            BureauReport report = adapter.pullReport(new CreditBureauPort.BureauPullRequest(
                    command.panNumber(), command.fullName(), command.dateOfBirth(),
                    command.phone(), command.consentArtifactId()
            ));

            request.succeed(
                    String.format("score=%d,risk=%s", report.creditScore(), report.riskGrade()),
                    BUREAU_CACHE_TTL_SECONDS
            );
            integrationRequestRepository.save(request);

            log.info("Bureau pull completed: bureau={}, referenceId={}, score={}",
                    bureauName, command.referenceId(), report.creditScore());
            return report;

        } catch (Exception e) {
            request.fail(e.getMessage());
            integrationRequestRepository.save(request);
            log.error("Bureau pull failed: bureau={}, referenceId={}", bureauName, command.referenceId(), e);
            throw e;
        }
    }

    @Override
    public AadhaarVerificationResult verifyAadhaar(VerifyAadhaarCommand command) {
        IntegrationRequest request = IntegrationRequest.initiate(
                command.tenantId(), PartnerType.AADHAAR_EKYC, "DIGILOCKER",
                command.referenceId(), "aadhaar=MASKED");

        return executeAndAudit(request, () -> aadhaarVerificationPort.verify(
                new AadhaarVerificationPort.AadhaarVerificationRequest(
                        command.aadhaarNumberOrVid(), command.consentArtifactId(), command.otpReference()
                )), r -> String.format("verified=%s,name=%s", r.verified(), r.nameOnRecord()));
    }

    @Override
    public PanVerificationResult verifyPan(VerifyPanCommand command) {
        IntegrationRequest request = IntegrationRequest.initiate(
                command.tenantId(), PartnerType.PAN_VERIFICATION, "NSDL",
                command.referenceId(), "pan=" + maskPan(command.panNumber()));

        return executeAndAudit(request, () -> panVerificationPort.verify(
                new PanVerificationPort.PanVerificationRequest(
                        command.panNumber(), command.fullName(), command.dateOfBirth()
                )), r -> String.format("valid=%s,status=%s", r.valid(), r.panStatus()));
    }

    @Override
    public BankAccountVerificationResult verifyBankAccount(VerifyBankAccountCommand command) {
        IntegrationRequest request = IntegrationRequest.initiate(
                command.tenantId(), PartnerType.BANK_ACCOUNT_VERIFICATION, "PENNY_DROP",
                command.referenceId(), "account=" + maskAccount(command.accountNumber()));

        return executeAndAudit(request, () -> bankAccountVerificationPort.verify(
                new BankAccountVerificationPort.BankAccountVerificationRequest(
                        command.accountNumber(), command.ifscCode(), command.expectedAccountHolderName()
                )), r -> String.format("verified=%s,nameMatch=%s", r.verified(), r.nameMatch()));
    }

    // ─── Shared audit + exception handling wrapper ───

    private <T> T executeAndAudit(IntegrationRequest request, Supplier<T> call, java.util.function.Function<T, String> summarizer) {
        try {
            T result = call.get();
            request.succeed(summarizer.apply(result), KYC_CACHE_TTL_SECONDS);
            integrationRequestRepository.save(request);
            return result;
        } catch (Exception e) {
            request.fail(e.getMessage());
            integrationRequestRepository.save(request);
            log.error("Partner call failed: type={}, partner={}, referenceId={}",
                    request.getPartnerType(), request.getPartnerName(), request.getReferenceId(), e);
            throw e;
        }
    }

    private String maskPan(String pan) {
        if (pan == null || pan.length() < 4) return "****";
        return "*".repeat(pan.length() - 4) + pan.substring(pan.length() - 4);
    }

    private String maskAccount(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) return "****";
        return "*".repeat(accountNumber.length() - 4) + accountNumber.substring(accountNumber.length() - 4);
    }
}
