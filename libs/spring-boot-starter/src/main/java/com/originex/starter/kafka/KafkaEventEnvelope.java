package com.originex.starter.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Shared, intentional extraction of the standard Originex event envelope (headers + JSON payload)
 * from a consumed {@link ConsumerRecord}.
 *
 * <p>The whole point of this helper is <b>intentional retry-vs-DLQ classification</b>. Every
 * defect that is inherent to the message — a missing required header, malformed JSON, a missing
 * required field, an unparseable UUID — is surfaced as a {@link PoisonEventException}, which the
 * shared error handler routes straight to the DLQ (no wasted retries). Anything a consumer does
 * <i>after</i> extraction (DB writes, downstream calls) throws its own exception types and stays
 * retryable. Consumers must therefore stop wrapping failures in a bare {@code RuntimeException},
 * which would hide the cause type from the classifier.
 *
 * <p>Note: the standard envelope headers are written by the outbox poller as UTF-8 bytes
 * ({@code event_id}, {@code event_type}, {@code tenant_id}, {@code aggregate_type},
 * {@code aggregate_id}).
 */
public final class KafkaEventEnvelope {

    private KafkaEventEnvelope() {
    }

    /** Returns the header value as a UTF-8 string, or {@code null} if the header is absent. */
    public static String header(ConsumerRecord<?, ?> record, String key) {
        var h = record.headers().lastHeader(key);
        return h != null ? new String(h.value(), StandardCharsets.UTF_8) : null;
    }

    /** Returns a required header, or throws {@link PoisonEventException} if missing/blank. */
    public static String requireHeader(ConsumerRecord<?, ?> record, String key) {
        String v = header(record, key);
        if (v == null || v.isBlank()) {
            throw new PoisonEventException("Missing required header '" + key + "' at " + location(record));
        }
        return v;
    }

    /** Returns a required header parsed as a UUID, or throws {@link PoisonEventException}. */
    public static UUID requireUuidHeader(ConsumerRecord<?, ?> record, String key) {
        String raw = requireHeader(record, key);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new PoisonEventException("Header '" + key + "' is not a valid UUID: '" + raw
                    + "' at " + location(record), e);
        }
    }

    /** Parses the record value as JSON, or throws {@link PoisonEventException} on malformed input. */
    public static JsonNode readJson(ObjectMapper mapper, ConsumerRecord<String, byte[]> record) {
        byte[] payload = record.value();
        if (payload == null || payload.length == 0) {
            throw new PoisonEventException("Empty event payload at " + location(record));
        }
        try {
            return mapper.readTree(payload);
        } catch (Exception e) {
            throw new PoisonEventException("Malformed JSON payload at " + location(record), e);
        }
    }

    /** Returns a required text field, or throws {@link PoisonEventException} if missing/null. */
    public static String requiredText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new PoisonEventException("Missing required field '" + field + "' in event payload");
        }
        return v.asText();
    }

    /** Returns a required UUID field, or throws {@link PoisonEventException}. */
    public static UUID requiredUuid(JsonNode node, String field) {
        String raw = requiredText(node, field);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new PoisonEventException("Field '" + field + "' is not a valid UUID: '" + raw + "'", e);
        }
    }

    /** Returns an optional text field, or {@code defaultValue} if missing/null. */
    public static String optionalText(JsonNode node, String field, String defaultValue) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? defaultValue : v.asText();
    }

    private static String location(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
    }
}
