package com.healthrecon.rag.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.qdrant.QdrantContainer;

@SpringBootTest(properties = {
        "app.ingestion.poll-ms=3600000",
        "app.ingestion.initial-delay-ms=3600000",
        "app.indexing.poll-ms=3600000",
        "app.indexing.initial-delay-ms=3600000",
        "app.golden.enabled=false",
        "app.golden.poll-ms=3600000",
        "app.golden.initial-delay-ms=3600000",
        "llm.embedding-dimension=768"
})
@AutoConfigureMockMvc
abstract class BaseIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final QdrantContainer QDRANT = new QdrantContainer("qdrant/qdrant:latest");

    static {
        POSTGRES.start();
        QDRANT.start();
    }

    @DynamicPropertySource
    static void containersProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("qdrant.host", QDRANT::getHost);
        registry.add("qdrant.grpc-port", QDRANT::getGrpcPort);
    }

    @MockitoBean
    protected ChatModel chatModel;

    @MockitoBean
    protected EmbeddingModel embeddingModel;

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected static String json(Object value) throws Exception {
        return new ObjectMapper().writeValueAsString(value);
    }

    protected static MediaType appJson() {
        return MediaType.APPLICATION_JSON;
    }

    protected String registerAndGetToken(String email, String password, String name) throws Exception {
        var body = objectMapper.createObjectNode()
                .put("email", email)
                .put("password", password)
                .put("displayName", name)
                .toString();
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/register")
                        .contentType(appJson())
                        .content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        String token = parseBody(result.getResponse().getContentAsString()).path("token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("register did not return a token");
        }
        return token;
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected JsonNode parseBody(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}