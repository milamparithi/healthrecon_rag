package com.healthrecon.rag.integration;

import com.healthrecon.rag.service.IngestionJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentSetFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IngestionJob ingestionJob;

    @Test
    void fullDocumentSetLifecycleWithIsolation() throws Exception {
        // user A
        String tokenA = registerAndGetToken("owner-a@example.com", "password123", "Owner A");
        String tokenB = registerAndGetToken("owner-b@example.com", "password123", "Owner B");
        String authA = bearer(tokenA);
        String authB = bearer(tokenB);

        // create a document set
        MvcResult created = mvc.perform(MockMvcRequestBuilders.post("/api/documentsets")
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content(json(Map.of("name", "Research Notes", "description", "my docs"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Research Notes"))
                .andReturn();
        UUID docSetId = UUID.fromString(parseBody(created.getResponse().getContentAsString()).get("id").asText());

        // upload a txt file and process the pending ingestion
        mvc.perform(MockMvcRequestBuilders.multipart("/api/documentsets/{id}/documents", docSetId)
                        .file(new MockMultipartFile("files", "notes.txt", "text/plain",
                                "Banfield is a great dog. Berri is a cat.".getBytes()))
                        .header("Authorization", authA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].status").value("UPLOADED"));

        ingestionJob.processPending();

        // document set is READY, document shows extracted text
        mvc.perform(MockMvcRequestBuilders.get("/api/documentsets/{id}", docSetId)
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.documentCount").value(1));

        MvcResult docs = mvc.perform(MockMvcRequestBuilders.get("/api/documentsets/{id}/documents", docSetId)
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        String docId = parseBody(docs.getResponse().getContentAsString()).get("content").get(0).get("id").asText();

        mvc.perform(MockMvcRequestBuilders.get("/api/documentsets/{id}/documents/{docId}", docSetId, docId)
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.extractedText").value("Banfield is a great dog. Berri is a cat."))
                .andExpect(jsonPath("$.extractedTextLength").value(40));

        // duplicate upload is skipped
        mvc.perform(MockMvcRequestBuilders.multipart("/api/documentsets/{id}/documents", docSetId)
                        .file(new MockMultipartFile("files", "notes.txt", "text/plain",
                                "Banfield is a great dog. Berri is a cat.".getBytes()))
                        .header("Authorization", authA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].status").value("DUPLICATE"));

        // rename the set (description update along the way)
        mvc.perform(MockMvcRequestBuilders.patch("/api/documentsets/{id}", docSetId)
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content(json(Map.of("name", "Research Notes v2", "description", "renamed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Research Notes v2"))
                .andExpect(jsonPath("$.description").value("renamed"));

        // renaming to an existing name for the same owner → 409
        MvcResult taken = mvc.perform(MockMvcRequestBuilders.post("/api/documentsets")
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content(json(Map.of("name", "Taken"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID takenId = UUID.fromString(parseBody(taken.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(MockMvcRequestBuilders.patch("/api/documentsets/{id}", docSetId)
                        .header("Authorization", authA)
                        .contentType(appJson())
                        .content(json(Map.of("name", "Taken"))))
                .andExpect(status().isConflict());

        // quota reflects the single stored document
        mvc.perform(MockMvcRequestBuilders.get("/api/quota")
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedBytes").value(40))
                .andExpect(jsonPath("$.limitBytes").value(104857600));

        // upload two more files to exercise pagination (3 docs total)
        mvc.perform(MockMvcRequestBuilders.multipart("/api/documentsets/{id}/documents", docSetId)
                        .file(new MockMultipartFile("files", "one.txt", "text/plain", "x".getBytes()))
                        .file(new MockMultipartFile("files", "two.txt", "text/plain", "y".getBytes()))
                        .header("Authorization", authA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].status").value("UPLOADED"))
                .andExpect(jsonPath("$[1].status").value("UPLOADED"));

        mvc.perform(MockMvcRequestBuilders.get("/api/documentsets/{id}/documents", docSetId)
                        .param("page", "0").param("size", "2")
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(MockMvcRequestBuilders.get("/api/documentsets/{id}/documents", docSetId)
                        .param("page", "1").param("size", "2")
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        // delete all documents → set back to EMPTY, quota freed
        mvc.perform(MockMvcRequestBuilders.delete("/api/documentsets/{id}/documents", docSetId)
                        .header("Authorization", authA))
                .andExpect(status().isNoContent());

        mvc.perform(MockMvcRequestBuilders.get("/api/documentsets/{id}", docSetId)
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMPTY"))
                .andExpect(jsonPath("$.documentCount").value(0));

        mvc.perform(MockMvcRequestBuilders.get("/api/quota")
                        .header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedBytes").value(0));

        // user B: complete isolation
        mvc.perform(MockMvcRequestBuilders.get("/api/documentsets")
                        .header("Authorization", authB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(MockMvcRequestBuilders.get("/api/documentsets/{id}", docSetId)
                        .header("Authorization", authB))
                .andExpect(status().isNotFound());

        mvc.perform(MockMvcRequestBuilders.multipart("/api/documentsets/{id}/documents", docSetId)
                        .file(new MockMultipartFile("files", "stolen.txt", "text/plain", "x".getBytes()))
                        .header("Authorization", authB))
                .andExpect(status().isNotFound());

        mvc.perform(MockMvcRequestBuilders.delete("/api/documentsets/{id}", docSetId)
                        .header("Authorization", authB))
                .andExpect(status().isNotFound());

        // unauthenticated access
        mvc.perform(MockMvcRequestBuilders.get("/api/documentsets"))
                .andExpect(status().isUnauthorized());

        // user A deletes the set -> everything gone
        mvc.perform(MockMvcRequestBuilders.delete("/api/documentsets/{id}", docSetId)
                        .header("Authorization", authA))
                .andExpect(status().isNoContent());

        mvc.perform(MockMvcRequestBuilders.get("/api/documentsets/{id}", docSetId)
                        .header("Authorization", authA))
                .andExpect(status().isNotFound());
    }
}