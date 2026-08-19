package com.example.app.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Runtime facts, and a sample route to replace with your own. */
@RestController
public class InfoController {

    private static final Instant STARTED_AT = Instant.now();

    private final ObjectProvider<DataSource> dataSource;

    public InfoController(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/api/info")
    public Map<String, Object> info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "udap-spring-boot-eks-api");
        body.put("java", System.getProperty("java.version"));
        body.put("environment", System.getProperty("spring.profiles.active", "production"));
        body.put("database", dataSource.getIfAvailable() == null ? "none" : "configured");
        body.put("started_at", STARTED_AT.toString());
        return body;
    }

    @GetMapping("/api/echo")
    public Map<String, Object> echo(@RequestParam Map<String, String> params) {
        return Map.of("received", params);
    }
}
