package com.originex.testsupport.rls;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * Reusable harness for role-aware RLS integration tests. Starts a Postgres
 * Testcontainer whose init script provisions the three RLS roles
 * ({@code originex_owner} / {@code originex_system} / {@code originex_app}) with
 * the same names and passwords the shared {@code rls} Spring profile defaults to,
 * so an integration test only has to activate that profile (or, for DB-level
 * tests, open a datasource per role via the helpers here).
 *
 * <p>Two usage styles:
 * <ul>
 *   <li><b>App-level wiring tests</b> — {@code @SpringBootTest} +
 *       {@code @ActiveProfiles("rls")}; point {@code spring.datasource.url} at the
 *       container and let the profile connect the app as {@code originex_app} and
 *       Flyway as {@code originex_owner}.</li>
 *   <li><b>DB-level semantics tests</b> — no Spring context: call
 *       {@link #migrateAsOwner} to build the schema, then assert RLS behaviour
 *       through {@link #appDataSource(JdbcDatabaseContainer)} /
 *       {@link #systemDataSource(JdbcDatabaseContainer)} /
 *       {@link #ownerDataSource(JdbcDatabaseContainer)}.</li>
 * </ul>
 *
 * <p>The roles and passwords mirror {@code dev/init-scripts/init-databases.sql};
 * this is the test-scoped, single-database version.
 */
public final class RlsPostgresSupport {

    public static final String OWNER_USERNAME = "originex_owner";
    public static final String OWNER_PASSWORD = "originex_owner_local";
    public static final String SYSTEM_USERNAME = "originex_system";
    public static final String SYSTEM_PASSWORD = "originex_system_local";
    public static final String APP_USERNAME = "originex_app";
    public static final String APP_PASSWORD = "originex_app_local";

    /** Matches the image used by the existing LMS integration test. */
    private static final String IMAGE = "postgres:16-alpine";
    /** Classpath resource bundled in this module. */
    private static final String ROLE_INIT_SCRIPT = "rls/test-roles.sql";

    private RlsPostgresSupport() {
    }

    /**
     * A Postgres container that provisions the three RLS roles at startup (before
     * Spring/Flyway). The superuser login stays {@code originex}/{@code originex_local}
     * (used only to resolve the {@code rls} profile's URL fallback and to run the
     * init script); application access goes through the provisioned roles.
     */
    public static PostgreSQLContainer<?> newContainer(String databaseName) {
        return new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName(databaseName)
                .withUsername("originex")
                .withPassword("originex_local")
                .withInitScript(ROLE_INIT_SCRIPT);
    }

    /** Owner role (BYPASSRLS + CREATE) — runs Flyway / DDL, and seeds fixtures across tenants. */
    public static DataSource ownerDataSource(JdbcDatabaseContainer<?> container) {
        return dataSource(container, OWNER_USERNAME, OWNER_PASSWORD);
    }

    /** System role (BYPASSRLS) — the route background schedulers use; sees all tenants. */
    public static DataSource systemDataSource(JdbcDatabaseContainer<?> container) {
        return dataSource(container, SYSTEM_USERNAME, SYSTEM_PASSWORD);
    }

    /** App role (NOBYPASSRLS) — the RLS-subject runtime path; isolation depends on it. */
    public static DataSource appDataSource(JdbcDatabaseContainer<?> container) {
        return dataSource(container, APP_USERNAME, APP_PASSWORD);
    }

    public static DataSource dataSource(JdbcDatabaseContainer<?> container, String username, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(container.getJdbcUrl());
        ds.setUser(username);
        ds.setPassword(password);
        return ds;
    }

    /**
     * Runs the service's Flyway migrations as {@code originex_owner} — exactly how
     * the {@code rls} profile runs them at application boot — so DB-level tests
     * assert against a schema owned by the owner role with RLS policies in force.
     * Defaults to {@code classpath:db/migration} when no locations are given.
     */
    public static void migrateAsOwner(JdbcDatabaseContainer<?> container, String... locations) {
        Flyway.configure()
                .dataSource(ownerDataSource(container))
                .locations(locations.length == 0 ? new String[]{"classpath:db/migration"} : locations)
                .load()
                .migrate();
    }
}
