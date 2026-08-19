package com.example.app.web;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness and readiness, on the paths the deploy pipeline and the landing page
 * expect. Actuator is still enabled at {@code /actuator} for real operations;
 * these two exist because the platform's health check, the Kubernetes probes and
 * the welcome page all address {@code /health} and {@code /ready}, and because
 * the split below is the important part.
 */
@RestController
public class HealthController {

    private static final Logger LOG = LoggerFactory.getLogger(HealthController.class);
    private static final Instant STARTED_AT = Instant.now();

    private final ObjectProvider<DataSource> dataSource;
    private final String version;

    public HealthController(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
        String tag = System.getenv("APP_VERSION");
        this.version = (tag == null || tag.isBlank()) ? "dev" : tag;
    }

    /**
     * Liveness. Deliberately does NOT touch the database — a database outage
     * must not make Kubernetes restart pods that are serving fine.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("uptime_s", Duration.between(STARTED_AT, Instant.now()).toSeconds());
        body.put("version", version);
        return body;
    }

    /**
     * Readiness. Checks the database, but only when one is configured, so the
     * same manifest works with the {@code none} database module.
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        DataSource ds = dataSource.getIfAvailable();
        Map<String, Object> body = new LinkedHashMap<>();
        if (ds == null) {
            body.put("status", "ready");
            body.put("database", "not configured");
            return ResponseEntity.ok(body);
        }
        try (var connection = ds.getConnection()) {
            if (!connection.isValid(5)) {
                throw new IllegalStateException("connection is not valid");
            }
        } catch (Exception e) {
            // Logged as well as returned: when a rollout stalls, the pod log is
            // what the pipeline prints and what an operator reads. A reason that
            // exists only in an HTTP response body nobody called is a reason
            // nobody sees.
            LOG.error("readiness check failed: {}", e.getMessage());
            body.put("status", "not ready");
            body.put("database", "unreachable");
            body.put("error", String.valueOf(e.getMessage()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        body.put("status", "ready");
        body.put("database", "connected");
        return ResponseEntity.ok(body);
    }
}
