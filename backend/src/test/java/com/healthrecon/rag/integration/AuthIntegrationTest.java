package com.healthrecon.rag.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends BaseIntegrationTest {

    @Test
    void registerReturnsTokenAndMeReturnsUser() throws Exception {
        String token = registerAndGetToken("ada@example.com", "password123", "Ada");

        mvc.perform(MockMvcRequestBuilders.get("/api/auth/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.displayName").value("Ada"));

        mvc.perform(MockMvcRequestBuilders.get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginReturnsToken() throws Exception {
        registerAndGetToken("grace@example.com", "password123", "Grace");

        mvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(appJson())
                        .content(json(java.util.Map.of(
                                "email", "grace@example.com",
                                "password", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("grace@example.com"));
    }

    @Test
    void loginRejectsBadCredentials() throws Exception {
        registerAndGetToken("alan@example.com", "password123", "Alan");

        mvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(appJson())
                        .content(json(java.util.Map.of(
                                "email", "alan@example.com",
                                "password", "wrong-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerRejectsDuplicateEmail() throws Exception {
        registerAndGetToken("dupe@example.com", "password123", "Dup");

        mvc.perform(MockMvcRequestBuilders.post("/api/auth/register")
                        .contentType(appJson())
                        .content(json(java.util.Map.of(
                                "email", "dupe@example.com",
                                "password", "other-password123",
                                "displayName", "Second"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    @Test
    void logoutReturnsNoContentAndTokenStillWorks() throws Exception {
        String token = registerAndGetToken("logout@example.com", "password123", "Lou");

        mvc.perform(MockMvcRequestBuilders.post("/api/auth/logout"))
                .andExpect(status().isNoContent());
    }
}