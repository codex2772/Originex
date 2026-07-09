package com.originex.payment.domain;

import com.originex.common.money.Money;
import com.originex.payment.domain.model.PaymentOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PaymentOrder Aggregate — State Machine")
class PaymentOrderTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID LOAN = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();

    private PaymentOrder createDisbursement() {
        return PaymentOrder.createDisbursement(TENANT, LOAN, CUSTOMER,
                Money.of("500000", "INR"),
                "1234567890", "SBIN0001234", "Rahul Sharma", "SBI",
                PaymentOrder.PaymentRail.NEFT);
    }

    @Nested
    @DisplayName("Creation")
    class Creation {
        @Test
        void shouldCreateWithCorrectInitialState() {
            PaymentOrder o = createDisbursement();
            assertThat(o.getStatus()).isEqualTo(PaymentOrder.PaymentOrderStatus.CREATED);
            assertThat(o.getPaymentType()).isEqualTo(PaymentOrder.PaymentType.DISBURSEMENT);
            assertThat(o.getPaymentRail()).isEqualTo(PaymentOrder.PaymentRail.NEFT);
            assertThat(o.getAmount().getAmount()).isEqualByComparingTo("500000.0000");
            assertThat(o.getPaymentReference()).startsWith("DISB-");
            assertThat(o.getRetryCount()).isZero();
        }

        @Test
        void shouldRejectZeroAmount() {
            assertThatThrownBy(() -> PaymentOrder.createDisbursement(
                    TENANT, LOAN, CUSTOMER, Money.of("0", "INR"),
                    "12345", "SBIN001", "Name", "Bank", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }
    }

    @Nested
    @DisplayName("Happy Path: CREATED → INITIATED → PROCESSING → COMPLETED")
    class HappyPath {
        @Test
        void shouldTransitionToCompleted() {
            PaymentOrder o = createDisbursement();

            o.initiate();
            assertThat(o.getStatus()).isEqualTo(PaymentOrder.PaymentOrderStatus.INITIATED);
            assertThat(o.getInitiatedAt()).isNotNull();

            o.markProcessing();
            assertThat(o.getStatus()).isEqualTo(PaymentOrder.PaymentOrderStatus.PROCESSING);

            o.complete("NEFT20260708123456", "SBIN-REF-001");
            assertThat(o.getStatus()).isEqualTo(PaymentOrder.PaymentOrderStatus.COMPLETED);
            assertThat(o.getExternalTransactionId()).isEqualTo("NEFT20260708123456");
            assertThat(o.getBankReferenceNumber()).isEqualTo("SBIN-REF-001");
            assertThat(o.getCompletedAt()).isNotNull();
            assertThat(o.isTerminal()).isTrue();
        }
    }

    @Nested
    @DisplayName("Failure and Retry")
    class FailureAndRetry {
        @Test
        void shouldScheduleRetryOnFailure() {
            PaymentOrder o = createDisbursement();
            o.initiate();
            o.markProcessing();
            o.fail("Insufficient funds in virtual account");

            assertThat(o.getStatus()).isEqualTo(PaymentOrder.PaymentOrderStatus.FAILED);
            assertThat(o.getFailureReason()).contains("Insufficient funds");

            o.scheduleRetry();
            assertThat(o.getStatus()).isEqualTo(PaymentOrder.PaymentOrderStatus.RETRY_PENDING);
            assertThat(o.getRetryCount()).isEqualTo(1);
        }

        @Test
        void shouldPermanentlyFailAfterMaxRetries() {
            PaymentOrder o = createDisbursement();

            for (int i = 0; i < o.getMaxRetries(); i++) {
                o.initiate();
                o.fail("Bank timeout attempt " + (i + 1));
                o.scheduleRetry();
                // last scheduleRetry() will set PERMANENTLY_FAILED
            }

            // After maxRetries exhausted
            o.initiate();
            o.fail("Final failure");
            o.scheduleRetry(); // This should set PERMANENTLY_FAILED

            assertThat(o.getStatus()).isEqualTo(PaymentOrder.PaymentOrderStatus.PERMANENTLY_FAILED);
            assertThat(o.isTerminal()).isTrue();
        }

        @Test
        void shouldNotRetryCompletedOrder() {
            PaymentOrder o = createDisbursement();
            o.initiate();
            o.complete("UTR001", "BANK001");

            assertThatThrownBy(() -> o.fail("Late failure"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completed");
        }
    }

    @Nested
    @DisplayName("Cancellation")
    class Cancellation {
        @Test
        void shouldCancelCreatedOrder() {
            PaymentOrder o = createDisbursement();
            o.cancel("Loan cancelled by borrower");
            assertThat(o.getStatus()).isEqualTo(PaymentOrder.PaymentOrderStatus.CANCELLED);
            assertThat(o.isTerminal()).isTrue();
        }

        @Test
        void shouldNotCancelProcessingOrder() {
            PaymentOrder o = createDisbursement();
            o.initiate();
            o.markProcessing();

            assertThatThrownBy(() -> o.cancel("Too late"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("processing");
        }

        @Test
        void shouldNotCancelCompletedOrder() {
            PaymentOrder o = createDisbursement();
            o.initiate();
            o.complete("UTR001", "BANK001");

            assertThatThrownBy(() -> o.cancel("Too late"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completed");
        }
    }

    @Nested
    @DisplayName("NACH Collection")
    class NachCollection {
        @Test
        void shouldCreateNachCollectionOrder() {
            PaymentOrder o = PaymentOrder.createNachCollection(
                    TENANT, LOAN, CUSTOMER,
                    Money.of("23536", "INR"),
                    UUID.randomUUID().toString(), "NACH-UMRN-001234567890");

            assertThat(o.getPaymentType()).isEqualTo(PaymentOrder.PaymentType.REPAYMENT_COLLECTION);
            assertThat(o.getPaymentRail()).isEqualTo(PaymentOrder.PaymentRail.NACH);
            assertThat(o.getPaymentReference()).startsWith("COLL-");
            assertThat(o.getMaxRetries()).isEqualTo(2);
        }
    }
}
