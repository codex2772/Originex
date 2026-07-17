package com.originex.starter.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static com.originex.starter.datasource.StockDataSourceDefaultsEnvironmentPostProcessor.STRINGTYPE_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Stock datasource defaults — stringtype=unspecified for jsonb writes")
class StockDataSourceDefaultsEnvironmentPostProcessorTest {

    private final StockDataSourceDefaultsEnvironmentPostProcessor processor =
            new StockDataSourceDefaultsEnvironmentPostProcessor();

    @Test
    @DisplayName("defaults stringtype=unspecified so String->jsonb writes are accepted")
    void defaultsStringtype() {
        MockEnvironment environment = new MockEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(STRINGTYPE_PROPERTY)).isEqualTo("unspecified");
    }

    @Test
    @DisplayName("an explicit stringtype wins — the default never overrides configuration")
    void explicitConfigurationWins() {
        MockEnvironment environment = new MockEnvironment();
        // A higher-precedence source, as application.yml or an env var would be.
        environment.getPropertySources().addFirst(new MapPropertySource(
                "explicit", Map.of(STRINGTYPE_PROPERTY, "varchar")));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(STRINGTYPE_PROPERTY))
                .as("contributed as a default (lowest precedence), so explicit config still wins")
                .isEqualTo("varchar");
    }
}
