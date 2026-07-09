package com.originex.ledger.adapter.out.persistence;

import com.originex.starter.outbox.OutboxEventJpaEntity;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards CLAUDE_ANALYSIS.md §9 backlog item: ledger-service's
 * outbox_events table (from V1) originally omitted the published_at
 * column that the shared {@link OutboxEventJpaEntity} maps and that
 * {@code OutboxPoller.markPublished()} / {@code deletePublishedBefore()}
 * read and write at runtime — so ledger both failed
 * {@code ddl-auto: validate} at boot and would have failed the first time
 * it published an event. V3__add_outbox_published_at.sql adds it.
 *
 * <p>This is a static file-content check, not a real-database integration
 * test — the repository has no Testcontainers/integration-test
 * infrastructure yet (tracked as a Phase 4 item), so this stays hermetic
 * while still catching the specific regression: it reflects over the
 * shared entity's {@code @Column} names (the authoritative contract) and
 * asserts every one appears somewhere in ledger-service's own migration
 * SQL. If someone removes the ALTER, or the shared entity gains a column
 * ledger's migrations don't provide, this fails immediately instead of
 * the drift only surfacing as a Hibernate validation error at boot.
 */
@DisplayName("Ledger outbox_events schema — matches shared OutboxEventJpaEntity")
class OutboxSchemaMigrationTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

    @Test
    @DisplayName("every @Column mapped by OutboxEventJpaEntity is declared in ledger's migrations")
    void everyMappedOutboxColumnIsDeclaredInMigrations() throws IOException {
        String allMigrationSql = readAllMigrations().toLowerCase();

        List<String> mappedColumns = mappedColumnNames(OutboxEventJpaEntity.class);
        assertThat(mappedColumns)
                .as("sanity: the entity should map several columns")
                .contains("published_at", "event_id", "status");

        for (String column : mappedColumns) {
            assertThat(allMigrationSql)
                    .as("column '%s' mapped by OutboxEventJpaEntity must be declared "
                            + "in a ledger-service migration", column)
                    .contains(column);
        }
    }

    private static List<String> mappedColumnNames(Class<?> entity) {
        List<String> names = new ArrayList<>();
        for (Field f : entity.getDeclaredFields()) {
            Column c = f.getAnnotation(Column.class);
            if (c != null && !c.name().isBlank()) {
                names.add(c.name().toLowerCase());
            }
        }
        return names;
    }

    private static String readAllMigrations() throws IOException {
        assertThat(Files.isDirectory(MIGRATION_DIR))
                .as("Expected migration directory at %s", MIGRATION_DIR.toAbsolutePath())
                .isTrue();
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            StringBuilder sb = new StringBuilder();
            for (Path p : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                sb.append(Files.readString(p)).append('\n');
            }
            return sb.toString();
        }
    }
}
