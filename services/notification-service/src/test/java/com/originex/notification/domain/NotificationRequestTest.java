package com.originex.notification.domain;

import com.originex.notification.domain.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationRequest Domain — State Machine & Template Rendering")
class NotificationRequestTest {

    private static final UUID TENANT = UUID.randomUUID();

    private NotificationRequest createRequest() {
        return NotificationRequest.create(
                TENANT, NotificationTrigger.LOAN_DISBURSED,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "+919876543210", "borrower@example.com", "Rahul Sharma",
                "en", UUID.randomUUID().toString(), "originex.lms.LoanDisbursed"
        );
    }

    @Nested
    @DisplayName("Creation")
    class Creation {
        @Test
        void shouldCreateWithPendingStatus() {
            NotificationRequest n = createRequest();
            assertThat(n.getStatus()).isEqualTo(NotificationRequest.NotificationStatus.PENDING);
            assertThat(n.getNotificationId()).isNotNull();
            assertThat(n.getRetryCount()).isZero();
            assertThat(n.getDispatches()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Channel Dispatch")
    class ChannelDispatch {
        @Test
        void shouldAddDispatchPerChannel() {
            NotificationRequest n = createRequest();
            n.addDispatch(NotificationChannel.SMS);
            n.addDispatch(NotificationChannel.EMAIL);

            assertThat(n.getDispatches()).hasSize(2);
            assertThat(n.getDispatches().get(0).getChannel()).isEqualTo(NotificationChannel.SMS);
            assertThat(n.getDispatches().get(1).getChannel()).isEqualTo(NotificationChannel.EMAIL);
        }

        @Test
        void shouldMarkDispatchSentWithProviderRef() {
            NotificationRequest n = createRequest();
            var dispatch = n.addDispatch(NotificationChannel.SMS);

            dispatch.markSent("MSG91-REF-001");
            assertThat(dispatch.getStatus()).isEqualTo(com.originex.notification.domain.model.ChannelDispatch.DispatchStatus.SENT);
            assertThat(dispatch.getProviderReference()).isEqualTo("MSG91-REF-001");
            assertThat(dispatch.getAttemptCount()).isEqualTo(1);
            assertThat(dispatch.getSentAt()).isNotNull();
        }

        @Test
        void shouldMarkDispatchFailed() {
            NotificationRequest n = createRequest();
            var dispatch = n.addDispatch(NotificationChannel.WHATSAPP);

            dispatch.markFailed("Recipient opted out");
            assertThat(dispatch.getStatus()).isEqualTo(com.originex.notification.domain.model.ChannelDispatch.DispatchStatus.FAILED);
            assertThat(dispatch.getFailureReason()).isEqualTo("Recipient opted out");
        }
    }

    @Nested
    @DisplayName("Overall Status Transitions")
    class StatusTransitions {
        @Test
        void shouldMarkDelivered() {
            NotificationRequest n = createRequest();
            n.markDelivered();
            assertThat(n.getStatus()).isEqualTo(NotificationRequest.NotificationStatus.DELIVERED);
        }

        @Test
        void shouldMarkPartiallyDelivered() {
            NotificationRequest n = createRequest();
            n.markPartiallyDelivered();
            assertThat(n.getStatus()).isEqualTo(NotificationRequest.NotificationStatus.PARTIALLY_DELIVERED);
        }

        @Test
        void shouldIncrementRetryAndResetToPending() {
            NotificationRequest n = createRequest();
            n.markFailed();
            n.incrementRetry();

            assertThat(n.getRetryCount()).isEqualTo(1);
            assertThat(n.getStatus()).isEqualTo(NotificationRequest.NotificationStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("Template Rendering")
    class TemplateRendering {
        @Test
        void shouldRenderBodyWithVariables() {
            var template = new com.originex.notification.domain.model.NotificationTemplate();
            template.setBody("Dear {{recipient_name}}, INR {{amount}} disbursed. UTR: {{utr}}. Loan: {{loan_id}}.");

            String rendered = template.renderBody(Map.of(
                    "recipient_name", "Priya Sharma",
                    "amount", "500000",
                    "utr", "NEFT20260708123456",
                    "loan_id", "LN-2026-0001"
            ));

            assertThat(rendered).isEqualTo(
                    "Dear Priya Sharma, INR 500000 disbursed. UTR: NEFT20260708123456. Loan: LN-2026-0001.");
        }

        @Test
        void shouldRenderSubjectWithVariables() {
            var template = new com.originex.notification.domain.model.NotificationTemplate();
            template.setSubject("Loan Disbursement Confirmation — UTR {{utr}}");

            String rendered = template.renderSubject(Map.of("utr", "RTGS20260708999"));
            assertThat(rendered).isEqualTo("Loan Disbursement Confirmation — UTR RTGS20260708999");
        }

        @Test
        void shouldHandleMissingVariableGracefully() {
            var template = new com.originex.notification.domain.model.NotificationTemplate();
            template.setBody("Dear {{recipient_name}}, your amount is {{amount}}.");

            // amount not provided — should substitute empty string
            String rendered = template.renderBody(Map.of("recipient_name", "Ankit"));
            assertThat(rendered).isEqualTo("Dear Ankit, your amount is .");
        }
    }
}
