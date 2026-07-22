package com.originex.notification.integration;

import com.originex.testsupport.rls.RlsPostgresSupport;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * notification-service's Phase-2 canary proof. Its first boot-level test — before
 * this, nothing booted the service, which is how its missing {@code outbox_events}
 * and {@code inbox_events} tables ({@code efaccec}) survived: both its test classes
 * are plain unit tests that never load a Spring context.
 *
 * <p><b>This proves Kafka-header→RLS isolation, not JWT→RLS.</b> That is a
 * deliberately different claim from the customer/ledger/payment/los canaries, and
 * it is the only one available here: notification has <b>no REST controller</b> —
 * its sole inbound adapter is {@code DomainEventNotificationConsumer}. There is no
 * HTTP request to carry a bearer token, so the tenant arrives on the Kafka
 * {@code tenant_id} record header and is installed by the starter's
 * {@code TenantRecordInterceptor}, not by {@code TenantClaimResolutionFilter}.
 * Modelled on {@code LmsRlsConsumerAndSchedulerIntegrationTest}, the existing
 * Kafka-path precedent.
 *
 * <p><b>Runs under {@code originex.security.enabled=true}, but there is NO authz gate here.</b>
 * The Phase-2 RLS canary deliberately did not opt notification into the OAuth2 resource server
 * (a {@code JwtDecoder} with nothing to decode was dead config then). Phase-1 opts in for
 * <i>mechanism uniformity</i>: the consumer now runs under a ceremonial {@code system:machine}
 * principal carrying <b>zero scopes</b>, and this test flips security on to prove that turning
 * enforcement on does <b>not</b> break the sink — not to prove any capability check passes, because
 * notification has none (no use-case port, no {@code @PreAuthorize}). The {@code JwtDecoder} is
 * still never called ({@code webEnvironment=NONE}, no HTTP/JWT), so the {@code jwk-set-uri} below is
 * a placeholder. Read this as "unaffected by enforcement," not "authz enforced."
 *
 * <p><b>Why tenant 1 is not an arbitrary choice.</b> {@code V1__create_notification_schema.sql}
 * seeds {@code notification_templates} for {@link #TENANT_WITH_TEMPLATES} only, and
 * {@code getConfiguredChannels(tenantId, trigger)} is tenant-scoped — so an event for
 * any other tenant finds no channels, is marked {@code FAILED}, and is still
 * persisted under its own tenant. Both outcomes are useful here.
 *
 * <p><b>{@code DELIVERED} is a free probe of header propagation.</b>
 * {@code notification_templates} is itself RLS-protected
 * (<code>USING (tenant_id = current_setting('app.tenant_id', true)::uuid)</code>), so
 * the template lookup only succeeds if the interceptor already installed
 * {@code app.tenant_id} from the record header. If propagation regressed, tenant 1's
 * notification would come back {@code FAILED} rather than {@code DELIVERED} — so
 * asserting the status tests the wiring, not just the outcome.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
                // Redis is excluded and probes are inactive off-Kubernetes, so the health
                // groups reference contributors absent here. Don't fail context load on it.
                "management.endpoint.health.validate-group-membership=false"
        })
@ActiveProfiles("rls")
@Testcontainers
@Tag("rls")
@DisplayName("Notification consumer — Kafka-header→RLS tenant isolation (Testcontainers)")
class NotificationRlsKafkaIsolationIntegrationTest {

    /** The only tenant V1 seeds templates for — so the only one that can reach DELIVERED. */
    private static final String TENANT_WITH_TEMPLATES = "00000000-0000-0000-0000-000000000001";
    /** Any other tenant: no templates, so its notification is persisted as FAILED. */
    private static final String OTHER_TENANT = "00000000-0000-0000-0000-0000000000bb";

    private static final String TOPIC = "originex.los.applications.events";
    private static final String EVENT_TYPE = "originex.los.ApplicationSubmitted";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_notification");

    @Container
    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    private static KafkaProducer<String, byte[]> producer;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // The rls profile derives the app/system/owner URLs from spring.datasource.url;
        // the superuser credentials here only resolve that fallback.
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Turn enforcement on so this is a real under-enforcement proof: @EnableMethodSecurity is now
        // active, yet the consumer still processes events because its path crosses no @PreAuthorize'd
        // method (notification is a pure side-effect sink; its machine principal carries no scopes).
        // No HTTP path is exercised (webEnvironment=NONE), so the decoder is built but never called and
        // the jwk-set-uri is a placeholder — mirroring LmsRlsConsumerAndSchedulerIntegrationTest.
        r.add("originex.security.enabled", () -> "true");
        r.add("originex.security.jwk-set-uri",
                () -> "https://idp.invalid/realms/originex/protocol/openid-connect/certs");
    }

    @BeforeAll
    static void startProducer() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName()));
    }

    @AfterAll
    static void closeProducer() {
        if (producer != null) {
            producer.close();
        }
    }

    @Test
    @DisplayName("a notification created from tenant A's event is invisible to tenant B")
    void kafkaHeaderScopesTheRowToItsTenant() {
        String eventA = UUID.randomUUID().toString();
        String eventB = UUID.randomUUID().toString();

        publish(TENANT_WITH_TEMPLATES, eventA);
        publish(OTHER_TENANT, eventB);

        // Both rows land (the owner bypasses RLS), each under the tenant from its header.
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(countAsOwner(eventA) + countAsOwner(eventB))
                        .as("both events are consumed and persisted")
                        .isEqualTo(2));

        assertThat(tenantOfAsOwner(eventA))
                .as("tenant comes from the Kafka header, not a default")
                .isEqualTo(TENANT_WITH_TEMPLATES);
        assertThat(tenantOfAsOwner(eventB)).isEqualTo(OTHER_TENANT);

        // The isolation claim: originex_app scoped to one tenant sees only that tenant's row.
        assertThat(visibleTenantsAsApp(TENANT_WITH_TEMPLATES))
                .as("app role under tenant A sees exactly its own row")
                .containsExactly(TENANT_WITH_TEMPLATES);
        assertThat(visibleTenantsAsApp(OTHER_TENANT))
                .as("app role under tenant B sees exactly its own row — A's is hidden by RLS")
                .containsExactly(OTHER_TENANT);
    }

    @Test
    @DisplayName("the RLS-protected template lookup succeeds, proving the header reached app.tenant_id")
    void deliveredStatusProvesHeaderPropagation() {
        String eventId = UUID.randomUUID().toString();
        publish(TENANT_WITH_TEMPLATES, eventId);

        // notification_templates is RLS-protected, so this only resolves if the
        // TenantRecordInterceptor installed app.tenant_id from the record header before
        // the consumer transaction. Without it the lookup returns no channels and the
        // notification is marked FAILED instead.
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(statusOfAsOwner(eventId))
                        .as("tenant A's notification reached DELIVERED — the tenant-scoped template "
                                + "read succeeded, so the Kafka header propagated to app.tenant_id")
                        .isEqualTo("DELIVERED"));
    }

    @Test
    @DisplayName("with no tenant context the app role sees nothing — RLS is fail-closed")
    void appRoleWithoutTenantContextSeesNothing() {
        String eventId = UUID.randomUUID().toString();
        publish(TENANT_WITH_TEMPLATES, eventId);
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(countAsOwner(eventId)).isEqualTo(1));

        Integer visible = appJdbc().queryForObject(
                "select count(*) from notification_requests", Integer.class);
        assertThat(visible)
                .as("app.tenant_id unset ⇒ the policy matches nothing; rows are hidden, not exposed")
                .isZero();
    }

    // ── helpers ──

    private void publish(String tenantId, String eventId) {
        String payload = """
                {"application_id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",\
                "customer_id":"cccccccc-cccc-cccc-cccc-cccccccccccc",\
                "customer_name":"Alice","phone":"9990000001","email":"alice@example.com"}""";
        ProducerRecord<String, byte[]> record =
                new ProducerRecord<>(TOPIC, eventId, payload.getBytes(StandardCharsets.UTF_8));
        record.headers().add(new RecordHeader("event_id", eventId.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("event_type", EVENT_TYPE.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("tenant_id", tenantId.getBytes(StandardCharsets.UTF_8)));
        try {
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException("failed to publish test event", e);
        }
    }

    /** Owner (BYPASSRLS): asks whether the row reached the database at all. */
    private static JdbcTemplate ownerJdbc() {
        return new JdbcTemplate(RlsPostgresSupport.ownerDataSource(POSTGRES));
    }

    /** App role (NOBYPASSRLS): the role tenant isolation actually depends on. */
    private static JdbcTemplate appJdbc() {
        return new JdbcTemplate(RlsPostgresSupport.appDataSource(POSTGRES));
    }

    private static int countAsOwner(String eventId) {
        Integer n = ownerJdbc().queryForObject(
                "select count(*) from notification_requests where source_event_id = ?", Integer.class, eventId);
        return n == null ? 0 : n;
    }

    private static String tenantOfAsOwner(String eventId) {
        return ownerJdbc().queryForObject(
                "select tenant_id::text from notification_requests where source_event_id = ?",
                String.class, eventId);
    }

    private static String statusOfAsOwner(String eventId) {
        return ownerJdbc().queryForObject(
                "select status from notification_requests where source_event_id = ?", String.class, eventId);
    }

    /**
     * Distinct tenants visible to {@code originex_app} with {@code app.tenant_id} set to
     * {@code tenantId}. Uses one connection so the SET and the SELECT share a session.
     */
    private static java.util.List<String> visibleTenantsAsApp(String tenantId) {
        DataSource app = RlsPostgresSupport.appDataSource(POSTGRES);
        JdbcTemplate jdbc = new JdbcTemplate(app);
        return jdbc.execute((java.sql.Connection c) -> {
            try (java.sql.Statement s = c.createStatement()) {
                s.execute("set app.tenant_id = '" + tenantId + "'");
                try (java.sql.ResultSet rs = s.executeQuery(
                        "select distinct tenant_id::text from notification_requests")) {
                    java.util.List<String> tenants = new java.util.ArrayList<>();
                    while (rs.next()) {
                        tenants.add(rs.getString(1));
                    }
                    return tenants;
                }
            }
        });
    }
}
