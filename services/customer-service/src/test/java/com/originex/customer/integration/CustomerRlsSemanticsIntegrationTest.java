package com.originex.customer.integration;

import com.originex.testsupport.rls.RlsPostgresSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-level (Layer 1) proof that PostgreSQL row-level security actually isolates
 * tenants once the app connects as {@code originex_app} — the guarantee no
 * existing test covers, because they connect as the container superuser (which
 * bypasses RLS). No Spring context: the schema is migrated as {@code originex_owner}
 * and assertions are issued directly through per-role datasources, so the checks
 * are fast and deterministic. Wiring (filter / interceptor / scheduler) is proven
 * separately by the app-level tests.
 *
 * <p>Covers, against the real hardened {@code customers} policy
 * (fail-closed {@code current_setting('app.tenant_id', true)} + {@code WITH CHECK}):
 * tenant isolation, cross-tenant write rejection, fail-closed reads/writes, and
 * the system-role BYPASSRLS path.
 */
@Testcontainers
@Tag("rls")
@DisplayName("Customer RLS semantics — isolation / WITH CHECK / fail-closed / system-bypass (Testcontainers)")
class CustomerRlsSemanticsIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String PHONE_A = "9990000001";
    private static final String PHONE_B = "9990000002";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = RlsPostgresSupport.newContainer("originex_customer");

    private static DataSource appDs;
    private static DataSource systemDs;
    private static DataSource ownerDs;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        // Flyway runs as the owner role — exactly as the rls profile does at boot.
        RlsPostgresSupport.migrateAsOwner(POSTGRES);
        appDs = RlsPostgresSupport.appDataSource(POSTGRES);
        systemDs = RlsPostgresSupport.systemDataSource(POSTGRES);
        ownerDs = RlsPostgresSupport.ownerDataSource(POSTGRES);

        // Seed one customer per tenant via the owner role (BYPASSRLS).
        try (Connection c = ownerDs.getConnection()) {
            insert(c, TENANT_A, "Alice", PHONE_A);
            insert(c, TENANT_B, "Bob", PHONE_B);
        }
    }

    @Test
    @DisplayName("each role connects as the expected Postgres user")
    void connectsAsExpectedRoles() throws SQLException {
        assertThat(currentUser(appDs)).isEqualTo("originex_app");
        assertThat(currentUser(systemDs)).isEqualTo("originex_system");
        assertThat(currentUser(ownerDs)).isEqualTo("originex_owner");
    }

    @Test
    @DisplayName("tenant isolation: the app role sees only its own tenant's rows")
    void appRoleSeesOnlyItsOwnTenant() throws SQLException {
        try (Connection a = appDs.getConnection()) {
            setTenant(a, TENANT_A);
            assertThat(existsPhone(a, PHONE_A)).as("A sees its own row").isTrue();
            assertThat(existsPhone(a, PHONE_B)).as("A cannot see B's row").isFalse();
        }
        try (Connection b = appDs.getConnection()) {
            setTenant(b, TENANT_B);
            assertThat(existsPhone(b, PHONE_B)).as("B sees its own row").isTrue();
            assertThat(existsPhone(b, PHONE_A)).as("B cannot see A's row").isFalse();
        }
    }

    @Test
    @DisplayName("WITH CHECK: the app role cannot write a row tagged for another tenant")
    void appRoleCannotWriteAcrossTenants() throws SQLException {
        try (Connection a = appDs.getConnection()) {
            setTenant(a, TENANT_A);
            // Tenant A tries to insert a row owned by tenant B → WITH CHECK rejects.
            assertThatThrownBy(() -> insert(a, TENANT_B, "Mallory", "9990000010"))
                    .isInstanceOf(SQLException.class);
            // Same connection/tenant, a row owned by A is accepted (sanity).
            insert(a, TENANT_A, "Alice2", "9990000011");
        }
    }

    @Test
    @DisplayName("fail-closed: with no app.tenant_id set, the app role sees nothing and cannot write")
    void appRoleWithNoTenantIsFailClosed() throws SQLException {
        try (Connection a = appDs.getConnection()) {
            // No set_config on this fresh connection → current_setting(...,true) is NULL.
            assertThat(existsPhone(a, PHONE_A)).as("no tenant → no rows visible").isFalse();
            assertThat(existsPhone(a, PHONE_B)).isFalse();
            assertThatThrownBy(() -> insert(a, TENANT_A, "Ghost", "9990000012"))
                    .as("no tenant → writes rejected")
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("system-role bypass: the BYPASSRLS role sees every tenant's rows without a tenant set")
    void systemRoleBypassesRls() throws SQLException {
        try (Connection s = systemDs.getConnection()) {
            assertThat(existsPhone(s, PHONE_A)).as("system sees tenant A").isTrue();
            assertThat(existsPhone(s, PHONE_B)).as("system sees tenant B").isTrue();
        }
    }

    // ── helpers ──

    private static void setTenant(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            ps.setString(1, tenant.toString());
            ps.executeQuery();
        }
    }

    private static void insert(Connection c, UUID rowTenant, String firstName, String phone) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into customers(tenant_id, first_name, last_name, phone) values (?::uuid, ?, 'Test', ?)")) {
            ps.setString(1, rowTenant.toString());
            ps.setString(2, firstName);
            ps.setString(3, phone);
            ps.executeUpdate();
        }
    }

    private static boolean existsPhone(Connection c, String phone) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*) from customers where phone = ?")) {
            ps.setString(1, phone);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static String currentUser(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select current_user")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
