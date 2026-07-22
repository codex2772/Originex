package com.originex.bre.application.port.in;

import com.originex.bre.domain.model.EvaluationResult;
import com.originex.starter.security.OriginexScopes;
import org.springframework.security.access.prepost.PreAuthorize;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Inbound port — BRE decisioning use case.
 *
 * <p><b>Authorization boundary</b> (inert until {@code originex.security.enabled=true}). This port has a
 * single operation, {@code evaluate}, and it is a <b>read of the decisioning engine</b>: rules in, a
 * decision out, no state change. It is gated on {@code decisioning:evaluate}.
 *
 * <p><b>What is deliberately NOT here.</b> There is no rule-<i>authoring</i>/management operation on this
 * port — and that is the point, not an oversight. Mutating the lending rule sets ("who can change the
 * decision rules") is a far higher privilege than running an evaluation; the out-port
 * {@code RuleRepository} declares {@code saveRule}/{@code saveRuleSet}, but <b>nothing in the service calls
 * them</b> (rule authoring is out-of-band today, via the owner/DBA/seed). So there is no in-app boundary to
 * gate for rule management. When such a surface is built it belongs on this port with its own elevated
 * scope(s) at the loan-approval tier — never folded into {@code decisioning:evaluate}.
 *
 * <p><b>Why the gate stays dormant.</b> {@code evaluate}'s only caller is los, service-to-service, and that
 * call is currently token-less (forwards {@code X-Tenant-Id}, no bearer — threat T4 in
 * {@code dev/AUTH_DESIGN.md}). With method security on, a token-less call fails this guard and los's
 * circuit-breaker silently degrades every application to REFER_TO_UNDERWRITER. So the gate is added and
 * proven under enforcement in tests, but bre must NOT flip to {@code security.enabled=true} in production
 * until los adopts a client-credentials token bearing {@code decisioning:evaluate}. Same "armed but
 * dormant" posture as the ledger/payment/los/lms canaries.
 */
public interface EvaluationUseCase {

    String REQUIRES_DECISIONING_EVALUATE =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.DECISIONING_EVALUATE + "')";

    /**
     * Evaluate a loan application against BRE rules.
     * Called synchronously by LOS before accepting an application.
     */
    @PreAuthorize(REQUIRES_DECISIONING_EVALUATE)
    EvaluationResult evaluate(EvaluateCommand command);

    record EvaluateCommand(
            UUID tenantId,
            String applicationId,
            String customerId,
            String productCode,
            String employmentType,

            // Bureau facts
            int creditScore,
            String bureauName,
            boolean hasWriteOff,
            boolean hasSettlement,
            int enquiriesLast6Months,
            int activeLoansCount,
            BigDecimal existingEmiObligations,

            // Income facts
            BigDecimal monthlyIncome,
            int applicantAgeYears,

            // Loan request
            BigDecimal requestedAmount,
            int requestedTenureMonths,
            String currency
    ) {}
}
