package com.oncall.phase1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.phase1.dto.UploadDocumentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class Phase1OfficialAcceptanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void officialQueries_shouldMatchExpectedBehavior() throws Exception {
        for (int i = 1; i <= 10; i++) {
            String id = String.format("sop-%03d", i);
            Path file = Path.of("data", id + ".html");
            String html = Files.readString(file, StandardCharsets.UTF_8);
            mockMvc.perform(post("/v1/documents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new UploadDocumentRequest(id, html))))
                    .andExpect(status().isCreated());
        }

        List<String> oomIds = idsFromQuery("OOM");
        assertThat(oomIds).contains("sop-001");

        List<String> faultIds = idsFromQuery("故障");
        assertThat(faultIds.size()).isGreaterThan(1);

        List<String> replicationIds = idsFromQuery("replication");
        assertThat(replicationIds).isEmpty();

        List<String> cdnIds = idsFromQuery("CDN");
        assertThat(cdnIds).contains("sop-003", "sop-010");

        List<String> ampersandIds = idsFromQuery("&");
        assertThat(ampersandIds).isNotEmpty();
    }

    private List<String> idsFromQuery(String q) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/search").queryParam("q", q))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode results = root.path("results");
        List<String> ids = new ArrayList<>();
        for (JsonNode row : results) {
            ids.add(row.path("id").asText());
        }
        return ids;
    }
}
