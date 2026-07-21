package com.originex.starter.kafka;

/**
 * Marks an event that can never succeed no matter how many times it is retried —
 * malformed JSON, a missing required field, an unparseable UUID, or any other
 * deterministic payload/header defect.
 *
 * <p>The shared Kafka error handler ({@code OriginexKafkaErrorHandlingAutoConfiguration})
 * classifies this exception as <b>not retryable</b>, so a record that throws it is routed
 * straight to the DLQ on the first delivery attempt rather than wasting the transient-failure
 * retry budget. Consumers should throw this (directly or via {@link KafkaEventEnvelope}) only
 * for defects that are inherent to the message; genuine transient failures (DB contention,
 * downstream timeouts) must propagate as their own exception types so they stay retryable.
 */
public class PoisonEventException extends RuntimeException {

    public PoisonEventException(String message) {
        super(message);
    }

    public PoisonEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
