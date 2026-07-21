package com.originex.starter.tenant;

import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * Establishes tenant context from the {@code tenant_id} Kafka header <b>before</b>
 * a {@code @Transactional} listener runs, so the RLS transaction-begin hook
 * ({@code RlsTenantTransactionManager}) sees the tenant when the transaction
 * starts. This is the consumer-side counterpart to {@code TenantResolutionFilter}
 * (HTTP) — the audit found consumers set tenant context <i>inside</i> their
 * transaction, which is too late once RLS is enforced.
 *
 * <p><b>Exception handling / safety:</b>
 * <ul>
 *   <li>{@link #intercept} never throws — a header parse issue simply leaves the
 *       tenant unset (fail-closed: the listener's own validation rejects it and
 *       any RLS query returns no rows).</li>
 *   <li>Context is cleared <i>defensively at the start of every record</i> and
 *       again on {@link #success}/{@link #failure}, so a reused consumer thread
 *       can never leak one record's tenant into the next.</li>
 * </ul>
 *
 * <p>Only wired when {@code originex.rls.enabled=true} (see
 * {@code RlsKafkaAutoConfiguration}); a no-op otherwise.
 */
public class TenantRecordInterceptor implements RecordInterceptor<Object, Object> {

    /** Header carrying the tenant id, set by OutboxPoller and all producers. */
    public static final String TENANT_HEADER = "tenant_id";

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        // Never inherit a previous record's context on a reused consumer thread.
        clear();
        String tenantId = header(record, TENANT_HEADER);
        if (tenantId != null && !tenantId.isBlank()) {
            TenantContextHolder.set(TenantContext.of(tenantId, tenantId));
            MDC.put("tenantId", tenantId);
        }
        return record;
    }

    @Override
    public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        clear();
    }

    @Override
    public void failure(ConsumerRecord<Object, Object> record, Exception exception,
                        Consumer<Object, Object> consumer) {
        clear();
    }

    private static void clear() {
        TenantContextHolder.clear();
        MDC.remove("tenantId");
    }

    private static String header(ConsumerRecord<Object, Object> record, String key) {
        Header h = record.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
