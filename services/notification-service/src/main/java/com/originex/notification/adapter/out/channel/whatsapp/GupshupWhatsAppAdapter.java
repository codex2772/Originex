package com.originex.notification.adapter.out.channel.whatsapp;

import com.originex.notification.application.port.out.NotificationChannelPort;
import com.originex.notification.domain.model.NotificationChannel;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gupshup WhatsApp Business API adapter.
 *
 * <p><b>SANDBOX MODE.</b> To go live:
 * <ol>
 *   <li>Register at app.gupshup.io — create a WhatsApp Business App</li>
 *   <li>Get WhatsApp Business Account (WABA) approved by Meta</li>
 *   <li>Register all message templates with Meta (HSM templates)</li>
 *   <li>Obtain API key + source phone number</li>
 *   <li>Store in Vault at: secret/notification/whatsapp/gupshup</li>
 * </ol>
 *
 * <p>Alternatives: Interakt, Kaleyra, Twilio WhatsApp — all use same Meta API underneath.
 */
@Component
public class GupshupWhatsAppAdapter implements NotificationChannelPort {

    private static final Logger log = LoggerFactory.getLogger(GupshupWhatsAppAdapter.class);
    private final boolean sandboxMode;

    public GupshupWhatsAppAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    public NotificationChannel channel() { return NotificationChannel.WHATSAPP; }

    @Override
    @CircuitBreaker(name = "whatsappChannel", fallbackMethod = "fallbackSend")
    @Retry(name = "whatsappChannel")
    public DispatchResult send(DispatchRequest request) {
        if (request.recipientPhone() == null || request.recipientPhone().isBlank()) {
            return new DispatchResult(false, null, "Recipient phone is blank");
        }

        if (sandboxMode) {
            String msgId = "GUPSHUP-SANDBOX-" + System.currentTimeMillis();
            log.info("[SANDBOX] WhatsApp sent: to={}, template={}, msgId={}",
                    maskPhone(request.recipientPhone()), request.templateCode(), msgId);
            return new DispatchResult(true, msgId, null);
        }

        // TODO Phase 4: Real Gupshup API
        // POST https://api.gupshup.io/wa/api/v1/msg
        // Headers: apikey, Content-Type: application/x-www-form-urlencoded
        // Body: channel=whatsapp, source=<waba_number>, destination=<phone>,
        //       src.name=Originex, message={"type":"template","template":{"id":templateId}}
        throw new UnsupportedOperationException("Gupshup WhatsApp LIVE mode not configured");
    }

    @SuppressWarnings("unused")
    private DispatchResult fallbackSend(DispatchRequest request, Throwable t) {
        log.warn("WhatsApp channel unavailable: {}", t.getMessage());
        return new DispatchResult(false, null, "WhatsApp service temporarily unavailable: " + t.getMessage());
    }

    private String maskPhone(String phone) {
        if (phone.length() <= 4) return "****";
        return "****" + phone.substring(phone.length() - 4);
    }
}
