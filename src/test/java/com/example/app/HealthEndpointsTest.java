package com.example.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * The endpoint contract the deploy pipeline depends on.
 *
 * <p>These run without a database on purpose: that is the {@code database=none}
 * shape, and it is also what CI has. The readiness-with-a-database path is
 * exercised by the real deploy, whose probe is the same call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class HealthEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthReportsOkWithoutTouchingTheDatabase() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/health"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("ok"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.uptime_s").exists());
    }

    @Test
    void readyIsReadyWhenNoDatabaseIsConfigured() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/ready"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("ready"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.database").value("not configured"));
    }

    @Test
    void infoDescribesTheRunningService() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/info"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.service").value("udap-spring-boot-eks-api"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.database").value("none"));
    }

    @Test
    void echoReturnsTheQueryString() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/echo").param("any", "value"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.received.any").value("value"));
    }

    @Test
    void rootResolvesToTheWelcomePage() throws Exception {
        // MockMvc does not follow the forward to a static resource, so the
        // assertion is that Spring resolved / to the welcome page at all. The
        // page being served with text/html is checked against a real container.
        mockMvc.perform(MockMvcRequestBuilders.get("/"))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.forwardedUrl("index.html"));
    }
}
