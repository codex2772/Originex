package com.originex.starter.kafka;

import com.fasterxml.jackson.core.JacksonException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.Map;

/**
 * Shared Kafka consumer error handling for every Originex service.
 *
 * <p>Step 0 established that today's consumers run on Spring Boot's default container factory with
 * <b>no</b> error handler: a poison message is retried ~10× and then <b>silently dropped</b> (no
 * DLQ, no trace). This auto-configuration replaces that with a single {@link CommonErrorHandler}
 * bean that Spring Boot's container-factory configurer applies to <b>all</b> consumers on the
 * default factory — no per-service change required.
 *
 * <p>Policy:
 * <ul>
 *   <li><b>Transient failures</b> (DB contention, downstream timeouts) — retried with exponential
 *       backoff (1s → 2s → 4s, 3 retries) then routed to {@code <sourceTopic>.dlq}.</li>
 *   <li><b>Non-retryable failures</b> ({@link PoisonEventException}, {@link IllegalArgumentException},
 *       {@link JacksonException}) — routed to the DLQ on the <b>first</b> attempt, no wasted retries.</li>
 * </ul>
 *
 * <p>Durability: the recoverer waits for the DLQ send to be acknowledged and fails if it is not
 * ({@code failIfSendResultIsError(true)} + {@code acks=all} + DLQ {@code min.insync.replicas=2}),
 * so a record can never leave the source topic before it is durably in the DLQ. If the DLQ itself
 * is unwritable the record stays retryable and the source partition holds (bounded, self-healing) —
 * a deliberate block-over-loss trade; see the observation tests in ledger-service.
 *
 * <p>The lenient policy for the notification (side-effect) consumer is configured separately.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnBean(ProducerFactory.class)
public class OriginexKafkaErrorHandlingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OriginexKafkaErrorHandlingAutoConfiguration.class);

    /** How long the recoverer blocks waiting for the DLQ send ack before treating it as failed. */
    private static final Duration DLQ_SEND_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Dedicated DLQ producer: String key / byte[] value passthrough, acks=all, idempotent, with a
     * bounded delivery timeout. Marked {@code defaultCandidate=false} so it never competes with the
     * outbox's {@code KafkaTemplate<String,byte[]>} for by-type autowiring.
     */
    @Bean(defaultCandidate = false)
    @ConditionalOnMissingBean(name = "dlqProducerFactory")
    public ProducerFactory<Object, Object> dlqProducerFactory(KafkaProperties properties,
                                                              ObjectProvider<SslBundles> sslBundles) {
        Map<String, Object> config = properties.buildProducerProperties(sslBundles.getIfAvailable());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean(defaultCandidate = false)
    @ConditionalOnMissingBean(name = "dlqKafkaTemplate")
    public KafkaTemplate<Object, Object> dlqKafkaTemplate(
            @Qualifier("dlqProducerFactory") ProducerFactory<Object, Object> dlqProducerFactory) {
        return new KafkaTemplate<>(dlqProducerFactory);
    }

    /**
     * Routes failed records to {@code <sourceTopic>.dlq}. Partition is {@code -1} (producer-chosen):
     * source topics have up to 128 partitions but DLQ topics have 8, so the default
     * partition-preserving resolver would target a non-existent DLQ partition.
     */
    @Bean
    @ConditionalOnMissingBean(name = "originexDlqRecoverer")
    public DeadLetterPublishingRecoverer originexDlqRecoverer(
            @Qualifier("dlqKafkaTemplate") KafkaTemplate<Object, Object> dlqKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                dlqKafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".dlq", -1));
        // Do not advance the source offset until the DLQ record is durably acknowledged.
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(DLQ_SEND_TIMEOUT);
        // Original headers (event_id, tenant_id, ...) and DLT diagnostic headers (original topic,
        // partition, offset, consumer group, exception) are preserved/added by default.
        return recoverer;
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public CommonErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer originexDlqRecoverer,
                                                ObjectProvider<MeterRegistry> meterRegistry) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(originexDlqRecoverer, backOff);
        // Deterministic payload/header defects go straight to the DLQ — no wasted retry budget.
        handler.addNotRetryableExceptions(
                PoisonEventException.class,
                IllegalArgumentException.class,
                JacksonException.class);

        MeterRegistry meters = meterRegistry.getIfAvailable();
        handler.setRetryListeners(new MetricsRetryListener(meters));

        log.info("Originex shared Kafka error handler installed: 3 retries (1s→2s→4s) then DLQ; "
                + "PoisonEventException/IllegalArgumentException/JacksonException -> DLQ immediately");
        return handler;
    }

    /**
     * Lenient container factory for side-effect (non-business-critical) consumers — currently the
     * notification consumer. A failure gets <b>zero</b> Kafka retries and goes straight to a single
     * cross-topic DLQ ({@code originex.notifications.deadletter.dlq} by default). Opt in per listener
     * via {@code containerFactory = "sideEffectKafkaListenerContainerFactory"}.
     *
     * <p>Rationale (see KI-11): the notification service already handles transient gateway failures
     * at the application layer (markFailed + its own retry poller) inside the transaction boundary
     * that guards against double-send. A Kafka-level retry would be a cruder, redundant layer that
     * retries only the failure class where retrying does not help and <i>does</i> risk re-sending an
     * already-sent notification. So the correct budget is 0: turn silent loss into a visible DLQ
     * record without an automatic double-send loop. Side-effect consumers run in their own consumer
     * groups, so this policy never affects business consumers on the default factory.
     */
    @Bean
    @ConditionalOnMissingBean(name = "sideEffectKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> sideEffectKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> kafkaConsumerFactory,
            @Qualifier("dlqKafkaTemplate") KafkaTemplate<Object, Object> dlqKafkaTemplate,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${originex.kafka.side-effect-dlq:originex.notifications.deadletter.dlq}") String sideEffectDlq) {

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        // Applies the default settings (incl. the strict CommonErrorHandler) first...
        configurer.configure(factory, kafkaConsumerFactory);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                dlqKafkaTemplate,
                (record, ex) -> new TopicPartition(sideEffectDlq, -1));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(DLQ_SEND_TIMEOUT);

        // ...then override: 0 retries -> DLQ on the first attempt (no redelivery, no auto double-send).
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0));
        handler.setRetryListeners(new MetricsRetryListener(meterRegistry.getIfAvailable()));
        factory.setCommonErrorHandler(handler);

        log.info("Originex side-effect Kafka factory installed: 0 retries -> single DLQ '{}'", sideEffectDlq);
        return factory;
    }

    /** Emits retry / DLQ / DLQ-send-failure counters so the now-visible failures are alertable. */
    private static final class MetricsRetryListener implements RetryListener {
        private final MeterRegistry meters;

        private MetricsRetryListener(MeterRegistry meters) {
            this.meters = meters;
        }

        @Override
        public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
            if (meters != null) {
                meters.counter("originex_kafka_retries_total", "topic", record.topic()).increment();
            }
        }

        @Override
        public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
            if (meters != null) {
                meters.counter("originex_kafka_dlq_total",
                        "topic", record.topic(),
                        "exception", ex.getClass().getSimpleName()).increment();
            }
            log.warn("Event routed to DLQ from topic={} partition={} offset={}: {}",
                    record.topic(), record.partition(), record.offset(), ex.toString());
        }

        @Override
        public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
            if (meters != null) {
                meters.counter("originex_kafka_dlq_send_failures_total", "topic", record.topic()).increment();
            }
            log.error("DLQ send FAILED for topic={} offset={}; record stays retryable (source partition "
                    + "will hold until DLQ is writable)", record.topic(), record.offset(), failure);
        }
    }
}
