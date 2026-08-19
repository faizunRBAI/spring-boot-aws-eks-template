package com.example.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point.
 *
 * <p>The same jar serves two roles. Started normally it runs the web
 * application; started with the {@code migrate} profile it applies Flyway
 * migrations and exits, which is what the db-migrate Kubernetes Job does before
 * a new Deployment is applied. Keeping both in one artefact means the schema can
 * never drift from the code that expects it.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
