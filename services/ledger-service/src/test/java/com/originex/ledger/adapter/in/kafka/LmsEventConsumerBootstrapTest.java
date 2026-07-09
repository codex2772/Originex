package com.originex.ledger.adapter.in.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the fix for CLAUDE_ANALYSIS.md §6 item 1: before
 * V2__seed_chart_of_accounts_and_inbox_table.sql, every LoanDisbursed /
 * RepaymentAllocated / InterestAccrued event failed with
 * "IllegalArgumentException: Account not found" because
 * LmsEventConsumer's hardcoded GL account UUIDs had no matching rows in
 * account_snapshots, and the service had no inbox_events table at all.
 *
 * <p>This is a plain file-content check, not a database integration test —
 * the repository has no Testcontainers/integration-test infrastructure yet
 * (tracked separately as a Phase 4 item), so this test stays hermetic while
 * still catching the specific failure mode: if someone changes one of
 * LmsEventConsumer's UUID constants without updating the seed migration (or
 * vice versa), this test fails immediately instead of the drift only
 * surfacing at runtime against a real Kafka event.
 */
@DisplayName("LmsEventConsumer bootstrap — GL accounts and inbox table")
class LmsEventConsumerBootstrapTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V2__seed_chart_of_accounts_and_inbox_table.sql");

    @Test
    @DisplayName("seed migration seeds every hardcoded GL account UUID referenced by the consumer")
    void seedMigrationSeedsEveryHardcodedGlAccount() throws IOException {
        String sql = readMigration();

        assertThat(sql)
                .as("POOL_ACCOUNT_ID must be seeded")
                .contains(LmsEventConsumer.POOL_ACCOUNT_ID.toString());
        assertThat(sql)
                .as("INTEREST_INCOME_ID must be seeded")
                .contains(LmsEventConsumer.INTEREST_INCOME_ID.toString());
        assertThat(sql)
                .as("INTEREST_RECEIVABLE_ID must be seeded")
                .contains(LmsEventConsumer.INTEREST_RECEIVABLE_ID.toString());
    }

    @Test
    @DisplayName("seed migration creates the inbox_events table the consumer depends on")
    void seedMigrationCreatesInboxEventsTable() throws IOException {
        String sql = readMigration();

        assertThat(sql).containsIgnoringCase("CREATE TABLE inbox_events");
    }

    private String readMigration() throws IOException {
        assertThat(Files.exists(MIGRATION))
                .as("Expected migration file at %s", MIGRATION.toAbsolutePath())
                .isTrue();
        return Files.readString(MIGRATION);
    }
}
