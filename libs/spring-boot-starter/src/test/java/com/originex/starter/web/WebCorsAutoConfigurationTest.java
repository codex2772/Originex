package com.originex.starter.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the fail-safe posture of {@link WebCorsAutoConfiguration}: CORS is
 * <b>off unless explicitly opted into</b>, and when opted in it permits only the
 * configured origins with no wildcard.
 */
@DisplayName("WebCorsAutoConfiguration — fail-safe, explicit-origins CORS")
class WebCorsAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebCorsAutoConfiguration.class));

    @Test
    @DisplayName("no property → no CORS beans (production default is genuinely off)")
    void absentPropertyContributesNothing() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(CorsConfigurationSource.class));
    }

    @Test
    @DisplayName("empty value → allows NO origin (empty list is deny, never allow-all)")
    void emptyValueAllowsNoOrigin() {
        // An empty value is "present", so the source may be contributed — but an
        // empty allow-list must mean DENY, not the allow-all footgun.
        runner.withPropertyValues("originex.web.cors.allowed-origins=")
                .run(ctx -> {
                    CorsConfigurationSource source = ctx.getBean(CorsConfigurationSource.class);
                    CorsConfiguration config = source.getCorsConfiguration(
                            new MockHttpServletRequest("OPTIONS", "/v1/loans"));

                    assertThat(config.getAllowedOrigins()).doesNotContain("*");
                    assertThat(config.checkOrigin("http://localhost:3000")).isNull();
                });
    }

    @Test
    @DisplayName("explicit dev origins → source permits them, blocks others, no wildcard")
    void configuredOriginsArePermittedExactly() {
        runner.withPropertyValues(
                        "originex.web.cors.allowed-origins=http://localhost:3000,http://localhost:5173")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(CorsConfigurationSource.class);
                    CorsConfigurationSource source = ctx.getBean(CorsConfigurationSource.class);

                    MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/v1/loans");
                    CorsConfiguration config = source.getCorsConfiguration(request);

                    assertThat(config).isNotNull();
                    assertThat(config.getAllowedOrigins())
                            .containsExactly("http://localhost:3000", "http://localhost:5173")
                            .doesNotContain("*");
                    assertThat(config.getAllowCredentials()).isTrue();

                    // A configured origin is echoed back; an unknown origin is blocked.
                    assertThat(config.checkOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
                    assertThat(config.checkOrigin("http://evil.example.com")).isNull();
                });
    }
}
