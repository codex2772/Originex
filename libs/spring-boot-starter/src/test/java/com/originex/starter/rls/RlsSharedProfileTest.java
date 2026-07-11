package com.originex.starter.rls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the shared RLS enablement profile ({@code application-rls.yml}) that
 * ships in this starter — the single place that wires RLS for every service.
 *
 * <p>The value of that file is entirely in its content and placeholder wiring,
 * so this test loads it the way Spring Boot would (via
 * {@link YamlPropertySourceLoader}) and resolves properties through a real
 * {@link StandardEnvironment}. It asserts that activating the profile turns RLS
 * on with the correct role defaults, that the datasource/Flyway URLs fall back
 * to the service's own {@code spring.datasource.url}, and that the {@code RLS_*}
 * environment variables override the defaults.
 *
 * <p>No database is contacted; this is pure property resolution. The gating and
 * fail-loud <i>bean</i> behaviour is covered by
 * {@link RlsDataSourceAutoConfigurationTest} and {@link RlsAutoConfigurationTest}.
 */
@DisplayName("application-rls.yml — shared RLS enablement profile")
class RlsSharedProfileTest {

    private static final String STUB_DB_URL = "jdbc:postgresql://localhost:5432/originex_test";

    /**
     * Builds an environment containing the profile YAML plus {@code overrides} at
     * highest precedence (standing in for {@code RLS_*} env vars / the service's
     * own {@code spring.datasource.url}), exactly as Spring resolves placeholders.
     */
    private static StandardEnvironment environmentWith(Map<String, Object> overrides) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test-overrides", overrides));
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application-rls", new ClassPathResource("application-rls.yml"));
        yaml.forEach(env.getPropertySources()::addLast);
        return env;
    }

    @Test
    @DisplayName("activating the profile enables RLS with the expected role defaults")
    void profileEnablesRlsWithRoleDefaults() throws IOException {
        StandardEnvironment env = environmentWith(Map.of("spring.datasource.url", STUB_DB_URL));

        assertThat(env.getProperty("originex.rls.enabled")).isEqualTo("true");
        assertThat(env.getProperty("originex.rls.datasource.app.username")).isEqualTo("originex_app");
        assertThat(env.getProperty("originex.rls.datasource.system.username")).isEqualTo("originex_system");
        assertThat(env.getProperty("spring.flyway.user")).isEqualTo("originex_owner");
    }

    @Test
    @DisplayName("app/system/Flyway URLs fall back to the service's own datasource URL")
    void urlsFallBackToServiceDatasourceUrl() throws IOException {
        StandardEnvironment env = environmentWith(Map.of("spring.datasource.url", STUB_DB_URL));

        assertThat(env.getProperty("originex.rls.datasource.app.url")).isEqualTo(STUB_DB_URL);
        assertThat(env.getProperty("originex.rls.datasource.system.url")).isEqualTo(STUB_DB_URL);
        assertThat(env.getProperty("spring.flyway.url")).isEqualTo(STUB_DB_URL);
    }

    @Test
    @DisplayName("RLS_* environment variables override the defaults")
    void environmentVariablesOverrideDefaults() throws IOException {
        StandardEnvironment env = environmentWith(Map.of(
                "spring.datasource.url", STUB_DB_URL,
                "RLS_DATASOURCE_URL", "jdbc:postgresql://db-host:5432/prod",
                "RLS_APP_USERNAME", "prod_app",
                "RLS_OWNER_USERNAME", "prod_owner"));

        assertThat(env.getProperty("originex.rls.datasource.app.url"))
                .isEqualTo("jdbc:postgresql://db-host:5432/prod");
        assertThat(env.getProperty("originex.rls.datasource.app.username")).isEqualTo("prod_app");
        assertThat(env.getProperty("spring.flyway.user")).isEqualTo("prod_owner");
        // Unset overrides still resolve to their role defaults.
        assertThat(env.getProperty("originex.rls.datasource.system.username")).isEqualTo("originex_system");
    }
}
