package com.oncall.phase1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.phase1.dto.UploadDocumentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class Phase1ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void searchOOM_shouldReturnSop001() throws Exception {
        String html = "<html><head><title>后端服务 On-Call SOP</title></head><body>服务 OOM 处理流程</body></html>";
        upload("sop-001", html);

        mockMvc.perform(get("/v1/search").queryParam("q", "OOM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].id").value("sop-001"));
    }

    @Test
    void searchReplication_shouldReturnEmptyBecauseInScript() throws Exception {
        String html = """
                <html>
                  <head><title>数据库 SOP</title></head>
                  <body>主从延迟故障处理</body>
                  <script>var replicationLag = 10;</script>
                </html>
                """;
        upload("sop-002", html);

        mockMvc.perform(get("/v1/search").queryParam("q", "replication"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isEmpty());
    }

    @Test
    void searchAmpersand_shouldMatchBodyContent() throws Exception {
        String html = "<html><head><title>网络 & CDN On-Call SOP</title></head><body>网络 & CDN 故障排查</body></html>";
        upload("sop-010", html);

        mockMvc.perform(get("/v1/search").queryParam("q", "&"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].id").value("sop-010"));
    }

    private void upload(String id, String html) throws Exception {
        mockMvc.perform(post("/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UploadDocumentRequest(id, html))))
                .andExpect(status().isCreated());
    }
}
