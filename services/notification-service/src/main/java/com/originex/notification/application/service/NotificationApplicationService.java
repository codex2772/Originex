package com.originex.notification.application.service;

import com.originex.notification.application.port.out.NotificationChannelPort;
import com.originex.notification.application.port.out.NotificationRepository;
import com.originex.notification.application.port.out.NotificationTemplateRepository;
import com.originex.notification.domain.model.*;
import com.originex.notification.domain.service.EventToNotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Notification Application Service — orchestrates the full dispatch lifecycle.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Idempotent dispatch — skip if sourceEventId already processed</li>
 *   <li>Template lookup per channel + language (falls back to "en")</li>
 *   <li>Multi-channel dispatch in sequence (SMS → Email → WhatsApp)</li>
 *   <li>Retry failed dispatches every 10 minutes</li>
 *   <li>Full audit trail — every attempt recorded</li>
 * </ul>
 */
@Service
@Transactional
public class NotificationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationApplicationService.class);
    private static final int MAX_RETRIES = 3;

    private final NotificationRepository notificationRepository;
    private final NotificationTemplateRepository templateRepository;
    private final Map<NotificationChannel, NotificationChannelPort> channelAdapters;
    private final EventToNotificationMapper eventMapper;

    public NotificationApplicationService(NotificationRepository notificationRepository,
                                          NotificationTemplateRepository templateRepository,
                                          List<NotificationChannelPort> channelPorts,
                                          EventToNotificationMapper eventMapper) {
        this.notificationRepository = notificationRepository;
        this.templateRepository = templateRepository;
        this.channelAdapters = channelPorts.stream()
                .collect(Collectors.toMap(NotificationChannelPort::channel, p -> p));
        this.eventMapper = eventMapper;
    }

    /**
     * Dispatch a notification triggered by a domain event.
     * Idempotent — safe to call multiple times for the same event.
     */
    public void dispatch(DispatchCommand command) {
        // Idempotency check
        if (notificationRepository.existsBySourceEventId(command.sourceEventId())) {
            log.debug("Notification already processed for event: {}", command.sourceEventId());
            return;
        }

        // Map event → trigger
        var triggerOpt = eventMapper.mapTrigger(command.eventType());
        if (triggerOpt.isEmpty()) {
            log.debug("No notification trigger for event type: {}", command.eventType());
            return;
        }
        NotificationTrigger trigger = triggerOpt.get();

        // Create notification request
        NotificationRequest notification = NotificationRequest.create(
                command.tenantId(), trigger,
                command.customerId(), command.loanId(),
                command.recipientPhone(), command.recipientEmail(),
                command.recipientName(), command.language(),
                command.sourceEventId(), command.eventType()
        );

        // Determine which channels are configured for this trigger
        List<NotificationChannel> channels = templateRepository.getConfiguredChannels(
                command.tenantId(), trigger);

        if (channels.isEmpty()) {
            log.warn("No channels configured for trigger={}, tenant={}", trigger, command.tenantId());
            notification.markFailed();
            notificationRepository.save(notification);
            return;
        }

        boolean anySuccess = false;
        boolean allSuccess = true;

        for (NotificationChannel channel : channels) {
            // Skip channel if no recipient for it
            if (channel == NotificationChannel.SMS && isBlank(command.recipientPhone())) continue;
            if (channel == NotificationChannel.EMAIL && isBlank(command.recipientEmail())) continue;
            if (channel == NotificationChannel.WHATSAPP && isBlank(command.recipientPhone())) continue;

            // Template lookup with language fallback
            var templateOpt = templateRepository.findTemplate(command.tenantId(), trigger, channel, command.language());
            if (templateOpt.isEmpty()) {
                templateOpt = templateRepository.findTemplate(command.tenantId(), trigger, channel);
            }
            if (templateOpt.isEmpty()) {
                log.warn("No template found for trigger={}, channel={}, tenant={}", trigger, channel, command.tenantId());
                allSuccess = false;
                continue;
            }

            NotificationTemplate template = templateOpt.get();
            String body = template.renderBody(command.variables());
            String subject = template.renderSubject(command.variables());

            // Dispatch to channel
            ChannelDispatch dispatch = notification.addDispatch(channel);
            NotificationChannelPort adapter = channelAdapters.get(channel);

            if (adapter == null) {
                log.warn("No adapter found for channel: {}", channel);
                dispatch.markFailed("No adapter registered for channel " + channel);
                allSuccess = false;
                continue;
            }

            try {
                NotificationChannelPort.DispatchResult result = adapter.send(
                        new NotificationChannelPort.DispatchRequest(
                                command.recipientPhone(), command.recipientEmail(),
                                command.recipientName(), subject, body,
                                trigger.name(), notification.getNotificationId().toString()
                        ));

                if (result.success()) {
                    dispatch.markSent(result.providerReference());
                    anySuccess = true;
                    log.info("Notification dispatched: trigger={}, channel={}, notificationId={}, ref={}",
                            trigger, channel, notification.getNotificationId(), result.providerReference());
                } else {
                    dispatch.markFailed(result.failureReason());
                    allSuccess = false;
                    log.warn("Notification dispatch failed: trigger={}, channel={}, reason={}",
                            trigger, channel, result.failureReason());
                }
            } catch (Exception e) {
                dispatch.markFailed(e.getMessage());
                allSuccess = false;
                log.error("Notification dispatch exception: trigger={}, channel={}", trigger, channel, e);
            }
        }

        // Update overall status
        if (allSuccess) {
            notification.markDelivered();
        } else if (anySuccess) {
            notification.markPartiallyDelivered();
        } else {
            notification.markFailed();
        }

        notificationRepository.save(notification);
    }

    /**
     * Retry scheduler — retries failed notifications every 10 minutes.
     */
    @Scheduled(fixedDelayString = "${originex.notification.retry-interval-ms:600000}")
    @Transactional
    public void retryFailed() {
        List<NotificationRequest> pending = notificationRepository.findPendingRetries(50);
        if (pending.isEmpty()) return;

        log.info("Retrying {} failed notifications", pending.size());
        for (NotificationRequest notification : pending) {
            if (notification.getRetryCount() >= MAX_RETRIES) {
                notification.markFailed();
                notificationRepository.save(notification);
                continue;
            }

            notification.incrementRetry();
            // Re-dispatch through each failed channel
            for (ChannelDispatch dispatch : notification.getDispatches()) {
                if (dispatch.getStatus() == ChannelDispatch.DispatchStatus.FAILED) {
                    retryDispatch(notification, dispatch);
                }
            }
            notificationRepository.save(notification);
        }
    }

    private void retryDispatch(NotificationRequest notification, ChannelDispatch dispatch) {
        NotificationChannelPort adapter = channelAdapters.get(dispatch.getChannel());
        if (adapter == null) return;

        var templateOpt = templateRepository.findTemplate(
                notification.getTenantId(), notification.getTrigger(), dispatch.getChannel());
        if (templateOpt.isEmpty()) return;

        try {
            NotificationChannelPort.DispatchResult result = adapter.send(
                    new NotificationChannelPort.DispatchRequest(
                            notification.getRecipientPhone(), notification.getRecipientEmail(),
                            notification.getRecipientName(),
                            templateOpt.get().renderSubject(Map.of()),
                            templateOpt.get().renderBody(Map.of()),
                            notification.getTrigger().name(),
                            notification.getNotificationId().toString()
                    ));

            if (result.success()) {
                dispatch.markSent(result.providerReference());
                log.info("Retry succeeded: notificationId={}, channel={}", notification.getNotificationId(), dispatch.getChannel());
            } else {
                dispatch.markFailed(result.failureReason());
            }
        } catch (Exception e) {
            dispatch.markFailed(e.getMessage());
        }
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    public record DispatchCommand(
            UUID tenantId,
            String customerId,
            String loanId,
            String recipientPhone,
            String recipientEmail,
            String recipientName,
            String language,
            String eventType,
            String sourceEventId,
            Map<String, String> variables
    ) {}
}
