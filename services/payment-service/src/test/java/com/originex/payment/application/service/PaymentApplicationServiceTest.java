package com.originex.payment.application.service;

import com.originex.common.money.Money;
import com.originex.payment.domain.model.PaymentOrder.PaymentRail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the payment rail auto-selection business rule documented on
 * {@link PaymentApplicationService#selectRail}: amount &gt; ₹5,00,000 → RTGS,
 * ₹2,00,000 ≤ amount ≤ ₹5,00,000 → IMPS, amount &lt; ₹2,00,000 → NEFT.
 *
 * <p>Before this fix, the threshold check order made the NEFT branch
 * unreachable — every amount fell into either the RTGS or IMPS branch, no
 * matter how small. These tests pin the corrected boundary behavior so that
 * regression can't silently reintroduce it.
 *
 * <p>{@code selectRail} doesn't touch any of {@link PaymentApplicationService}'s
 * injected dependencies, so the service is constructed with an empty rail-port
 * list and null repositories/publisher — sufficient for this pure
 * amount-to-rail decision, no mocking framework needed.
 */
@DisplayName("PaymentApplicationService — selectRail() business rule")
class PaymentApplicationServiceTest {

    private final PaymentApplicationService service =
            new PaymentApplicationService(null, null, List.of(), null);

    private PaymentRail selectRail(String preferred, String amount) {
        return service.selectRail(preferred, Money.of(amount, "INR"));
    }

    @Nested
    @DisplayName("Auto-selection by amount (no preferred rail)")
    class AutoSelection {

        @Test
        @DisplayName("₹1 → NEFT")
        void oneRupeeSelectsNeft() {
            assertThat(selectRail(null, "1")).isEqualTo(PaymentRail.NEFT);
        }

        @Test
        @DisplayName("₹1,99,999 → NEFT (just below the IMPS lower bound)")
        void justBelowImpsLowerBoundSelectsNeft() {
            assertThat(selectRail(null, "199999")).isEqualTo(PaymentRail.NEFT);
        }

        @Test
        @DisplayName("₹2,00,000 → IMPS (IMPS lower bound, inclusive)")
        void impsLowerBoundSelectsImps() {
            assertThat(selectRail(null, "200000")).isEqualTo(PaymentRail.IMPS);
        }

        @Test
        @DisplayName("₹5,00,000 → IMPS (IMPS upper bound, inclusive)")
        void impsUpperBoundSelectsImps() {
            assertThat(selectRail(null, "500000")).isEqualTo(PaymentRail.IMPS);
        }

        @Test
        @DisplayName("₹5,00,001 → RTGS (just above the IMPS upper bound)")
        void justAboveImpsUpperBoundSelectsRtgs() {
            assertThat(selectRail(null, "500001")).isEqualTo(PaymentRail.RTGS);
        }

        @Test
        @DisplayName("A large amount well above ₹5,00,000 still selects RTGS (no upper cap)")
        void largeAmountSelectsRtgs() {
            assertThat(selectRail(null, "50000000")).isEqualTo(PaymentRail.RTGS);
        }

        @Test
        @DisplayName("Blank preferred rail is treated as no preference — still auto-selects")
        void blankPreferredFallsThroughToAutoSelect() {
            assertThat(selectRail("   ", "1")).isEqualTo(PaymentRail.NEFT);
        }
    }

    @Nested
    @DisplayName("Explicit rail override")
    class ExplicitOverride {

        @Test
        @DisplayName("Explicit NEFT wins even for an amount that would auto-select RTGS")
        void explicitNeftOverridesLargeAmount() {
            assertThat(selectRail("NEFT", "5000000")).isEqualTo(PaymentRail.NEFT);
        }

        @Test
        @DisplayName("Explicit RTGS wins even for an amount that would auto-select NEFT")
        void explicitRtgsOverridesSmallAmount() {
            assertThat(selectRail("RTGS", "1")).isEqualTo(PaymentRail.RTGS);
        }

        @Test
        @DisplayName("Explicit IMPS wins even for an amount outside the IMPS auto-select band")
        void explicitImpsOverridesOutOfBandAmount() {
            assertThat(selectRail("IMPS", "5000000")).isEqualTo(PaymentRail.IMPS);
        }

        @Test
        @DisplayName("Preferred rail is case-insensitive")
        void preferredRailIsCaseInsensitive() {
            assertThat(selectRail("rtgs", "1")).isEqualTo(PaymentRail.RTGS);
        }

        @Test
        @DisplayName("A genuinely unrecognized preferred rail falls back to auto-selection")
        void unrecognizedPreferredFallsBackToAutoSelect() {
            assertThat(selectRail("BITCOIN", "1")).isEqualTo(PaymentRail.NEFT);
            assertThat(selectRail("BITCOIN", "5000000")).isEqualTo(PaymentRail.RTGS);
        }

        @Test
        @DisplayName("UPI is a valid rail and is honored as an explicit override")
        void explicitUpiOverrideIsHonored() {
            assertThat(selectRail("UPI", "1")).isEqualTo(PaymentRail.UPI);
        }
    }
}
