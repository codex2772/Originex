package com.originex.starter.web;

import com.originex.starter.OriginexProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Cross-Origin Resource Sharing for browser clients — <b>fail-safe, default-off</b>.
 *
 * <p>The whole configuration is gated on {@code originex.web.cors.allowed-origins}
 * being present ({@link ConditionalOnProperty}). With no such property — the
 * production default — <b>none of these beans are created</b>, so no
 * {@code Access-Control-Allow-Origin} header is ever emitted and the browser
 * blocks cross-origin requests. There is no "empty list means allow-all" path:
 * absence means the bean does not exist, not that it exists permitting everything.
 * <b>Dev opts in</b> by listing explicit origins; production does nothing and is
 * safe. Wildcards are never used, so credentialed CORS stays legal.
 *
 * <p>Two delivery paths, one source of truth ({@link #originexCorsConfigurationSource}):
 * <ul>
 *   <li>Services <b>without</b> Spring Security on the classpath get a
 *       {@link CorsFilter} (this class) — plain servlet CORS handling.</li>
 *   <li>Services <b>with</b> Spring Security wire {@code http.cors(...)} against the
 *       same {@link CorsConfigurationSource} bean in {@code SecurityAutoConfiguration},
 *       because the security filter chain otherwise bypasses MVC-level CORS and
 *       preflight {@code OPTIONS} would fail on a secured service.</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "originex.web.cors", name = "allowed-origins")
@EnableConfigurationProperties(OriginexProperties.class)
public class WebCorsAutoConfiguration {

    /** Methods a browser client may use cross-origin. */
    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    /** Request headers a browser client may send cross-origin. */
    private static final List<String> ALLOWED_HEADERS =
            List.of("Authorization", "Content-Type", "X-Tenant-Id");

    private static final long MAX_AGE_SECONDS = 3600L;

    /**
     * The single source of truth for CORS policy, applied to every path. Built
     * from the explicitly configured origins — never a wildcard — so
     * {@code allowCredentials(true)} is legal.
     */
    @Bean
    public CorsConfigurationSource originexCorsConfigurationSource(OriginexProperties properties) {
        List<String> origins = properties.getWeb().getCors().getAllowedOrigins();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);          // exact match only, no "*"
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setAllowCredentials(true);
        config.setMaxAge(MAX_AGE_SECONDS);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Servlet-level CORS for services that have not opted into Spring Security.
     * Absent when Security is on the classpath — there, the security filter chain
     * handles CORS against the same source, and a second filter would double the
     * headers.
     */
    @Bean
    @ConditionalOnMissingClass("org.springframework.security.web.SecurityFilterChain")
    public CorsFilter originexCorsFilter(CorsConfigurationSource originexCorsConfigurationSource) {
        return new CorsFilter(originexCorsConfigurationSource);
    }
}
