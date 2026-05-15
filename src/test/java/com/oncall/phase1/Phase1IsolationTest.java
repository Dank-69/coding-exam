package com.oncall.phase1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.phase1.dto.UploadDocumentRequest;
import com.oncall.phase2.client.MoonshotEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
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
class Phase1IsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MoonshotEmbeddingClient embeddingClient;

    @Test
    void postV1Documents_shouldNotTouchEmbeddingClient() throws Exception {
        mockMvc.perform(post("/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UploadDocumentRequest(
                                "isolation-doc",
                                "<html><head><title>Isolation</title></head><body>database replica lag</body></html>"
                        ))))
                .andExpect(status().isCreated());

        verifyNoInteractions(embeddingClient);
    }
}
