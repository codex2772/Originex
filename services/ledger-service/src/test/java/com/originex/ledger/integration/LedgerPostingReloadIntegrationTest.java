package com.originex.ledger.integration;

import com.originex.common.tenant.TenantContext;
import com.originex.common.tenant.TenantContextHolder;
import com.originex.ledger.application.port.in.LedgerUseCase;
import com.originex.ledger.application.port.in.LedgerUseCase.OpenAccountCommand;
import com.originex.ledger.application.port.in.LedgerUseCase.PostJournalEntryCommand;
import com.originex.ledger.application.port.in.LedgerUseCase.PostingLine;
import com.originex.testsupport.rls.RlsPostgresSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration proof for the fix to #5 — ledger posting across a real reload.
 *
 * <p><b>The bug.</b> {@code AccountJpaEntity.toDomain()} rebuilt accounts via
 * {@code Account.open()}, minting a new random {@code accountId} and zeroing the
 * balance on every read. {@code LedgerApplicationService.postJournalEntry} loads each
 * account ({@code findById} → {@code toDomain}) and saves it back — so the reloaded
 * object carried a fresh id, and the save INSERTed a second row with the existing
 * {@code account_number}, violating {@code idx_account_number}. Every journal-entry
 * posting to an existing account failed with a duplicate-key {@code 500}, and the
 * cached balance would have been wrong even without the constraint.
 *
 * <p>Originally #5 was reproduced only by a hand-made HTTP call; it is now known to
 * block the real lms→ledger disbursement path. These tests exercise the service
 * directly against a real RLS Postgres so the failure — and the fix — are unambiguous.
 *
 * <p>The DB is read as the owner (BYPASSRLS); the use-case runs as {@code originex_app}
 * under a bound {@link TenantContextHolder}, exactly as the Kafka consumer does.
 * Redis and Kafka auto-config are excluded — {@code postJournalEntry} writes the outbox
 * through JPA and never touches a broker on the write side.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "management.endpoint.health.validate-group-membership=false"
})
@ActiveProfiles("rls")
@Testcontainers
@Tag("rls")
@DisplayName("Ledger posting — journal entries succeed and balances are correct across reload (#5)")
class LedgerPostingReloadIntegrationTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000001";
    private static final UUID TENANT_ID = UUID.fromString(TENANT);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_ledger");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Owner (BYPASSRLS) — the app datasource would see nothing without a tenant bound. */
    private static JdbcTemplate jdbc;

    @Autowired
    LedgerUseCase ledger;

    @BeforeAll
    static void ownerJdbc() {
        jdbc = new JdbcTemplate(RlsPostgresSupport.ownerDataSource(POSTGRES));
    }

    @Test
    @DisplayName("posting over two pre-existing accounts succeeds and creates no phantom account rows")
    void postingOverExistingAccountsDoesNotDuplicate() {
        UUID assetId = openAccount("PST-A1", "ASSET", "1100");
        UUID revenueId = openAccount("PST-B1", "REVENUE", "4100");

        long accountsBefore = accountRowCount();

        assertThatCode(() -> post("DISBURSEMENT", "e2e-1",
                new PostingLine(assetId, "DEBIT", "1000.00", "INR", "debit asset"),
                new PostingLine(revenueId, "CREDIT", "1000.00", "INR", "credit revenue")))
                .as("before the fix this threw DataIntegrityViolationException: duplicate key idx_account_number")
                .doesNotThrowAnyException();

        assertThat(accountRowCount())
                .as("save() must UPDATE the two accounts, not INSERT duplicates under a new random id")
                .isEqualTo(accountsBefore);
        assertThat(balanceOf(assetId)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(revenueId)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("balances are the arithmetic sum across two postings and an intervening reload")
    void balanceArithmeticHoldsAcrossReloadAndMultiplePostings() {
        UUID assetId = openAccount("PST-A2", "ASSET", "1100");
        UUID revenueId = openAccount("PST-B2", "REVENUE", "4100");

        // Posting 1 sets a non-zero balance.
        post("DISBURSEMENT", "e2e-2a",
                new PostingLine(assetId, "DEBIT", "100.00", "INR", "d1"),
                new PostingLine(revenueId, "CREDIT", "100.00", "INR", "c1"));

        // Posting 2 must reload each account (findById → toDomain) and accumulate onto the
        // persisted balance. The original bug reloaded a zeroed balance under a new id, so
        // this second posting is where "balance reads back as zero" would surface as wrong
        // arithmetic even if the duplicate-key were somehow avoided.
        post("REPAYMENT", "e2e-2b",
                new PostingLine(assetId, "DEBIT", "50.00", "INR", "d2"),
                new PostingLine(revenueId, "CREDIT", "50.00", "INR", "c2"));

        assertThat(balanceOf(assetId))
                .as("100 + 50 applied on the normal (debit) side = 150, not 50 (reloaded-zero) or 0")
                .isEqualByComparingTo("150.00");
        assertThat(balanceOf(revenueId))
                .as("100 + 50 applied on the normal (credit) side = 150")
                .isEqualByComparingTo("150.00");
    }

    // ─── helpers ───

    private UUID openAccount(String number, String type, String glCode) {
        return asTenant(() -> ledger.openAccount(new OpenAccountCommand(
                TENANT_ID, number, "Test " + number, type, glCode, "INR", null, null))
                .getAccountId());
    }

    private void post(String entryType, String sourceId, PostingLine... lines) {
        asTenant(() -> {
            ledger.postJournalEntry(new PostJournalEntryCommand(
                    TENANT_ID, entryType, LocalDate.now().toString(), null,
                    entryType + " " + sourceId, "TEST", sourceId, null,
                    List.of(lines), "TEST"));
            return null;
        });
    }

    private static <T> T asTenant(Supplier<T> action) {
        TenantContextHolder.set(TenantContext.of(TENANT, TENANT));
        try {
            return action.get();
        } finally {
            TenantContextHolder.clear();
        }
    }

    private long accountRowCount() {
        return jdbc.queryForObject(
                "select count(*) from account_snapshots where tenant_id = ?::uuid", Long.class, TENANT);
    }

    private BigDecimal balanceOf(UUID accountId) {
        return jdbc.queryForObject(
                "select balance from account_snapshots where account_id = ?", BigDecimal.class, accountId);
    }
}
