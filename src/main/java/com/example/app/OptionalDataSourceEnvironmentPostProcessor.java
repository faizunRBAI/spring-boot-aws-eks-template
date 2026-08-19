package com.example.app;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Makes the database genuinely optional.
 *
 * <p>The {@code database} module choice can be {@code none}, in which case the
 * deploy creates no app-database Secret and SPRING_DATASOURCE_URL is empty.
 * Spring Boot's DataSource auto-configuration treats that as a misconfiguration
 * and refuses to start — so a perfectly valid deployment would crash-loop on
 * "Failed to configure a DataSource".
 *
 * <p>When no URL is present this switches the relevant auto-configurations off
 * instead. It runs as an EnvironmentPostProcessor rather than in {@code main}
 * so tests, which build the context directly, behave the same way the pod does.
 */
public class OptionalDataSourceEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

    private static final String EXCLUDE_KEY = "spring.autoconfigure.exclude";

    private static final String EXCLUDED = String.join(",",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        if (url != null && !url.isBlank()) {
            return;
        }
        environment.getPropertySources().addFirst(
            new MapPropertySource("udap-optional-datasource", Map.of(EXCLUDE_KEY, EXCLUDED)));
    }

    @Override
    public int getOrder() {
        // After the config-data processor, so application.yaml has been read and
        // spring.datasource.url can actually be resolved.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
