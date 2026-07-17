package com.originex.starter.datasource;

import org.springframework.boot.DefaultPropertiesPropertySource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Map;

/**
 * Makes the <b>stock</b> {@code spring.datasource} Hikari pool accept raw-JSON
 * {@code String} parameters for {@code jsonb} columns, by defaulting the
 * PostgreSQL driver's {@code stringtype=unspecified}.
 *
 * <p>Why this exists: {@code OutboxEventJpaEntity.metadataJson} is a {@code String}
 * mapped to a {@code jsonb} column. Hibernate binds it as {@code varchar}, and
 * PostgreSQL rejects that with {@code SQLSTATE 42804} — <i>column "metadata" is of
 * type jsonb but expression is of type character varying</i>. With
 * {@code stringtype=unspecified} the value is sent untyped and the server casts it
 * to {@code jsonb}, preserving the raw JSON with no re-serialization.
 *
 * <p>{@code 84e584e} applied the same driver property to the RLS app/system pools
 * in {@code RlsDataSourceAutoConfiguration}, which is why outbox writes succeed
 * under the {@code rls} profile today. Every service on the stock datasource still
 * had the defect: any transaction writing the outbox fails, the Kafka listener
 * retries and drops the record (there is no DLQ), and the symptom surfaces far
 * from the cause — as a downstream state that never arrives.
 *
 * <p>Contributed as a <b>default</b> property (lowest precedence), so a service or
 * environment can still override {@code stringtype} explicitly.
 *
 * <p>This is a stopgap at the connection layer, not the root fix: the entity should
 * ultimately declare its JSON type (e.g. Hibernate's {@code @JdbcTypeCode(SqlTypes.JSON)})
 * so the binding is correct regardless of driver settings. Tracked separately —
 * see issue #3.
 */
public class StockDataSourceDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String STRINGTYPE_PROPERTY =
            "spring.datasource.hikari.data-source-properties.stringtype";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        DefaultPropertiesPropertySource.addOrMerge(
                Map.of(STRINGTYPE_PROPERTY, "unspecified"), environment.getPropertySources());
    }
}
