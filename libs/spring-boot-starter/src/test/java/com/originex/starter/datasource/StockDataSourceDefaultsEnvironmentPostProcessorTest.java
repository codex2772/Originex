package com.originex.starter.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;

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
    @DisplayName("is registered in META-INF/spring.factories so Spring actually runs it")
    void isRegisteredForDiscovery() throws Exception {
        // Without this, the class is inert: the post-processor never runs, no property is
        // contributed, and outbox writes keep failing — while every other test here still
        // passes, because they exercise the class directly. An earlier attempt registered
        // it via META-INF/spring/...EnvironmentPostProcessor.imports, which Spring ignores:
        // the .imports mechanism is for AutoConfiguration only. EnvironmentPostProcessor
        // must be declared in spring.factories.
        Enumeration<URL> resources =
                getClass().getClassLoader().getResources("META-INF/spring.factories");

        boolean registered = false;
        while (resources.hasMoreElements()) {
            Properties properties = new Properties();
            try (InputStream in = resources.nextElement().openStream()) {
                properties.load(in);
            }
            String declared = properties.getProperty(EnvironmentPostProcessor.class.getName());
            if (declared != null
                    && declared.contains(StockDataSourceDefaultsEnvironmentPostProcessor.class.getName())) {
                registered = true;
                break;
            }
        }

        assertThat(registered)
                .as("StockDataSourceDefaultsEnvironmentPostProcessor must be declared under "
                        + EnvironmentPostProcessor.class.getName() + " in META-INF/spring.factories")
                .isTrue();
    }

    @Test
    @DisplayName("the property name actually binds to the Hikari pool's driver properties")
    void propertyBindsToTheHikariPool() {
        // The other tests prove the property is contributed and discoverable. This proves
        // the last link: that STRINGTYPE_PROPERTY is the name Spring Boot binds to
        // HikariConfig.dataSourceProperties, so the driver really receives it. Without
        // this, a renamed/misspelt property key would leave the post-processor running,
        // every test green, and jsonb writes still failing. Hikari does not connect at
        // construction, so no database is needed.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://localhost:1/never-connected",
                        "spring.datasource.username=originex",
                        "spring.datasource.password=originex_local",
                        STRINGTYPE_PROPERTY + "=unspecified")
                .run(context -> {
                    HikariDataSource dataSource = context.getBean(HikariDataSource.class);
                    assertThat(dataSource.getDataSourceProperties().getProperty("stringtype"))
                            .as("pgjdbc receives stringtype=unspecified, so a raw-JSON String "
                                    + "is accepted by a jsonb column")
                            .isEqualTo("unspecified");
                });
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
