package com.originex.notification.adapter.out.channel.sms;

import com.originex.notification.application.port.out.NotificationChannelPort;
import com.originex.notification.domain.model.NotificationChannel;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MSG91 SMS adapter.
 *
 * <p><b>SANDBOX MODE.</b> To go live:
 * <ol>
 *   <li>Create account at msg91.com → get API key + sender ID (DLT registered)</li>
 *   <li>Register all SMS templates on DLT (TRAI mandate for transactional SMS in India)</li>
 *   <li>Each template gets a DLT Template ID — store in DB against notification_templates</li>
 *   <li>Set credentials in Vault at: secret/notification/sms/msg91</li>
 *   <li>Set {@code originex.partner.mode=LIVE}</li>
 * </ol>
 *
 * <p><b>DLT Compliance:</b> All transactional SMS to Indian numbers MUST be pre-registered
 * on the Distributed Ledger Technology (DLT) portal. Non-registered templates will be blocked.
 */
@Component
public class Msg91SmsAdapter implements NotificationChannelPort {

    private static final Logger log = LoggerFactory.getLogger(Msg91SmsAdapter.class);
    private final boolean sandboxMode;

    public Msg91SmsAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    public NotificationChannel channel() { return NotificationChannel.SMS; }

    @Override
    @CircuitBreaker(name = "smsChannel", fallbackMethod = "fallbackSend")
    @Retry(name = "smsChannel")
    public DispatchResult send(DispatchRequest request) {
        if (request.recipientPhone() == null || request.recipientPhone().isBlank()) {
            return new DispatchResult(false, null, "Recipient phone is blank");
        }

        if (sandboxMode) {
            String ref = "MSG91-SANDBOX-" + System.currentTimeMillis();
            log.info("[SANDBOX] SMS sent: to={}, template={}, ref={}",
                    maskPhone(request.recipientPhone()), request.templateCode(), ref);
            log.info("[SANDBOX] SMS body preview: {}", truncate(request.body(), 80));
            return new DispatchResult(true, ref, null);
        }

        // TODO Phase 4: Real MSG91 API
        // POST https://api.msg91.com/api/v5/flow/
        // Headers: authkey, Content-Type: application/json
        // Body: { "flow_id": dltTemplateId, "sender": "ORIGNX", "mobiles": phone, variables... }
        throw new UnsupportedOperationException("MSG91 LIVE mode not configured — add DLT-registered templates and API key to Vault");
    }

    @SuppressWarnings("unused")
    private DispatchResult fallbackSend(DispatchRequest request, Throwable t) {
        log.warn("SMS channel unavailable: {}", t.getMessage());
        return new DispatchResult(false, null, "SMS service temporarily unavailable: " + t.getMessage());
    }

    private String maskPhone(String phone) {
        if (phone.length() <= 4) return "****";
        return "****" + phone.substring(phone.length() - 4);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
