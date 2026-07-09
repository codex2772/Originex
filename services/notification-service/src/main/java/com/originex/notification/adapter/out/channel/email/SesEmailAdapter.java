package com.originex.notification.adapter.out.channel.email;

import com.originex.notification.application.port.out.NotificationChannelPort;
import com.originex.notification.domain.model.NotificationChannel;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AWS SES (Simple Email Service) adapter.
 *
 * <p><b>SANDBOX MODE.</b> To go live:
 * <ol>
 *   <li>Verify sending domain in AWS SES (Mumbai region: ap-south-1)</li>
 *   <li>Request production access (exit sandbox mode in AWS SES console)</li>
 *   <li>Configure DKIM + DMARC for email deliverability</li>
 *   <li>Use IRSA (IAM Roles for Service Accounts) — no stored credentials needed</li>
 *   <li>Alternatively use SendGrid (simpler setup, good for transactional emails)</li>
 * </ol>
 */
@Component
public class SesEmailAdapter implements NotificationChannelPort {

    private static final Logger log = LoggerFactory.getLogger(SesEmailAdapter.class);
    private final boolean sandboxMode;
    private final String fromAddress;

    public SesEmailAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode,
                           @Value("${originex.notification.email.from:noreply@originex.in}") String from) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
        this.fromAddress = from;
    }

    @Override
    public NotificationChannel channel() { return NotificationChannel.EMAIL; }

    @Override
    @CircuitBreaker(name = "emailChannel", fallbackMethod = "fallbackSend")
    @Retry(name = "emailChannel")
    public DispatchResult send(DispatchRequest request) {
        if (request.recipientEmail() == null || request.recipientEmail().isBlank()) {
            return new DispatchResult(false, null, "Recipient email is blank");
        }

        if (sandboxMode) {
            String messageId = "SES-SANDBOX-" + System.currentTimeMillis();
            log.info("[SANDBOX] Email sent: to={}, subject='{}', from={}, messageId={}",
                    maskEmail(request.recipientEmail()), request.subject(), fromAddress, messageId);
            return new DispatchResult(true, messageId, null);
        }

        // TODO Phase 4: Real AWS SES v2 API
        // software.amazon.awssdk:sesv2 — SendEmailRequest via SesV2Client with IRSA
        throw new UnsupportedOperationException("AWS SES LIVE mode not configured — verify domain in SES console first");
    }

    @SuppressWarnings("unused")
    private DispatchResult fallbackSend(DispatchRequest request, Throwable t) {
        log.warn("Email channel unavailable: {}", t.getMessage());
        return new DispatchResult(false, null, "Email service temporarily unavailable: " + t.getMessage());
    }

    private String maskEmail(String email) {
        if (email == null) return "****";
        int atIdx = email.indexOf('@');
        if (atIdx < 2) return "***" + email.substring(atIdx);
        return email.substring(0, 2) + "***" + email.substring(atIdx);
    }
}
