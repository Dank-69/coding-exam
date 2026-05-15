package com.oncall.phase2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.phase1.dto.UploadDocumentRequest;
import com.oncall.phase2.client.MoonshotEmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "oncall.bootstrap.enabled=false",
        "oncall.phase2.vector-warmup.enabled=false",
        "moonshot.embedding-api-key=test-key",
        "moonshot.embeddings-enabled=true"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Phase2ApiPathTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MoonshotEmbeddingClient embeddingClient;

    @BeforeEach
    void setUp() throws Exception {
        when(embeddingClient.isEnabled()).thenReturn(true);
        when(embeddingClient.disabledReason()).thenReturn("");
        when(embeddingClient.embedSingle(anyString())).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        when(embeddingClient.embedBatch(Mockito.<List<String>>any())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream()
                    .map(this::vectorFor)
                    .toList();
        });

        upload("database-runbook", """
                <html><head><title>Database Replication SOP</title></head>
                <body>database replica lag binlog primary secondary failover recovery mysql</body></html>
                """);
        upload("frontend-runbook", """
                <html><head><title>Frontend Static Asset SOP</title></head>
                <body>frontend cache cdn cors chunk load error static asset browser page blank</body></html>
                """);
        upload("security-runbook", """
                <html><head><title>Security Incident SOP</title></head>
                <body>security intrusion waf malware credential leak incident containment</body></html>
                """);
    }

    @Test
    void v2Search_shouldUseEmbeddingApiPathAndReturnRankedSubset() throws Exception {
        MvcResult result = mockMvc.perform(get("/v2/search").queryParam("q", "database replica lag"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString()).path("results");
        List<String> ids = new ArrayList<>();
        for (JsonNode row : rows) {
            ids.add(row.path("id").asText());
        }

        assertThat(ids).isNotEmpty();
        assertThat(ids.get(0)).isEqualTo("database-runbook");
        assertThat(ids).doesNotContain("security-runbook");
        assertThat(ids).hasSizeLessThan(3);
        verify(embeddingClient).embedSingle("database replica lag");
        verify(embeddingClient, atLeastOnce()).embedBatch(Mockito.<List<String>>any());
    }

    private void upload(String id, String html) throws Exception {
        mockMvc.perform(post("/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UploadDocumentRequest(id, html))))
                .andExpect(status().isCreated());
    }

    private float[] vectorFor(String text) {
        if (text.contains("database replica lag")) {
            return new float[]{1.0f, 0.0f, 0.0f};
        }
        if (text.contains("frontend cache")) {
            return new float[]{0.25f, 0.95f, 0.0f};
        }
        return new float[]{0.0f, 1.0f, 0.0f};
    }
}
