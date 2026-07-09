package com.originex.partner.adapter.out.bank;

import com.originex.partner.application.port.out.BankAccountVerificationPort;
import com.originex.partner.domain.model.BankAccountVerificationResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bank account (penny-drop) verification adapter.
 *
 * <p>Providers to choose from: Karza, Decentro, Cashfree Verification Suite,
 * Signzy, or direct NPCI "Vayu" (Account Validation) API.
 *
 * <p><b>SANDBOX MODE.</b> To go live:
 * <ol>
 *   <li>Sign up with a verification-as-a-service provider (fastest: Decentro/Cashfree)</li>
 *   <li>They deposit ₹1 to the account and read back the registered account holder name via NPCI</li>
 *   <li>Compare returned name against KYC name using fuzzy match (e.g., Jaro-Winkler > 0.85)</li>
 * </ol>
 */
@Component
public class PennyDropBankVerificationAdapter implements BankAccountVerificationPort {

    private static final Logger log = LoggerFactory.getLogger(PennyDropBankVerificationAdapter.class);
    private static final Pattern IFSC_FORMAT = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    private final boolean sandboxMode;

    public PennyDropBankVerificationAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    @CircuitBreaker(name = "bankVerification", fallbackMethod = "fallbackVerify")
    @Retry(name = "bankVerification")
    public BankAccountVerificationResult verify(BankAccountVerificationRequest request) {
        if (request.ifscCode() == null || !IFSC_FORMAT.matcher(request.ifscCode()).matches()) {
            return BankAccountVerificationResult.failed("Invalid IFSC format");
        }
        if (request.accountNumber() == null || request.accountNumber().length() < 9) {
            return BankAccountVerificationResult.failed("Invalid account number");
        }

        if (sandboxMode) {
            String bankCode = request.ifscCode().substring(0, 4);
            String bankName = resolveBankName(bankCode);
            log.info("[SANDBOX] Penny-drop simulated verification: ifsc={}, bank={}", request.ifscCode(), bankName);

            return new BankAccountVerificationResult(
                    true,
                    maskAccount(request.accountNumber()),
                    request.ifscCode(), bankName, "Sandbox Branch",
                    request.expectedAccountHolderName(), // Echo back — sandbox assumes match
                    true, "ACTIVE",
                    "UTR" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(),
                    null
            );
        }
        // TODO Phase 4: Real penny-drop provider integration (Decentro/Cashfree/Karza)
        throw new UnsupportedOperationException("Penny-drop LIVE mode not yet configured");
    }

    @SuppressWarnings("unused")
    private BankAccountVerificationResult fallbackVerify(BankAccountVerificationRequest request, Throwable t) {
        log.warn("Bank account verification unavailable: {}", t.getMessage());
        return BankAccountVerificationResult.failed("Service temporarily unavailable. Please retry.");
    }

    private String resolveBankName(String bankCode) {
        return switch (bankCode) {
            case "SBIN" -> "State Bank of India";
            case "HDFC" -> "HDFC Bank";
            case "ICIC" -> "ICICI Bank";
            case "UTIB" -> "Axis Bank";
            case "PUNB" -> "Punjab National Bank";
            case "KKBK" -> "Kotak Mahindra Bank";
            default -> "Bank (" + bankCode + ")";
        };
    }

    private String maskAccount(String accountNumber) {
        if (accountNumber.length() < 4) return "****";
        return "*".repeat(accountNumber.length() - 4) + accountNumber.substring(accountNumber.length() - 4);
    }
}
