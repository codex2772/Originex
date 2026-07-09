package com.originex.notification.adapter.out.channel.push;

import com.originex.notification.application.port.out.NotificationChannelPort;
import com.originex.notification.domain.model.NotificationChannel;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Firebase Cloud Messaging (FCM) push notification adapter.
 *
 * <p><b>SANDBOX MODE.</b> To go live:
 * <ol>
 *   <li>Create Firebase project → download service account JSON</li>
 *   <li>Store service account JSON in Vault at: secret/notification/push/fcm</li>
 *   <li>Mobile app must register device tokens and send to notification-service</li>
 * </ol>
 */
@Component
public class FcmPushAdapter implements NotificationChannelPort {

    private static final Logger log = LoggerFactory.getLogger(FcmPushAdapter.class);
    private final boolean sandboxMode;

    public FcmPushAdapter(@Value("${originex.partner.mode:SANDBOX}") String mode) {
        this.sandboxMode = !"LIVE".equalsIgnoreCase(mode);
    }

    @Override
    public NotificationChannel channel() { return NotificationChannel.PUSH; }

    @Override
    @CircuitBreaker(name = "pushChannel", fallbackMethod = "fallbackSend")
    public DispatchResult send(DispatchRequest request) {
        if (sandboxMode) {
            String msgId = "FCM-SANDBOX-" + System.currentTimeMillis();
            log.info("[SANDBOX] Push notification sent: template={}, msgId={}", request.templateCode(), msgId);
            return new DispatchResult(true, msgId, null);
        }
        throw new UnsupportedOperationException("FCM LIVE mode not configured");
    }

    @SuppressWarnings("unused")
    private DispatchResult fallbackSend(DispatchRequest request, Throwable t) {
        log.warn("Push channel unavailable: {}", t.getMessage());
        return new DispatchResult(false, null, "Push service temporarily unavailable");
    }
}
