package com.oncall.phase2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.phase1.dto.UploadDocumentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
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

@SpringBootTest(properties = {
        "moonshot.api-key=",
        "moonshot.embedding-api-key=",
        "moonshot.embeddings-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class Phase2OfficialAcceptanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void officialSemanticQueries_shouldMatchExpectedRanking() throws Exception {
        for (int i = 1; i <= 10; i++) {
            String id = String.format("sop-%03d", i);
            Path file = Path.of("data", id + ".html");
            String html = Files.readString(file, StandardCharsets.UTF_8);
            mockMvc.perform(post("/v1/documents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new UploadDocumentRequest(id, html))))
                    .andExpect(status().isCreated());
        }

        List<String> outageIds = idsFromQuery("服务器挂了");
        assertThat(outageIds.subList(0, Math.min(3, outageIds.size()))).contains("sop-001", "sop-004");

        List<String> securityIds = idsFromQuery("黑客攻击");
        assertThat(securityIds).isNotEmpty();
        assertThat(securityIds.get(0)).isEqualTo("sop-005");

        List<String> aiIds = idsFromQuery("机器学习模型出问题");
        assertThat(aiIds).isNotEmpty();
        assertThat(aiIds.get(0)).isEqualTo("sop-008");

        List<String> backendIds = idsFromQuery("后端");
        assertThat(backendIds).isNotEmpty();
        assertThat(backendIds.get(0)).isEqualTo("sop-001");
    }

    @Test
    void localFallback_shouldRankByContentInsteadOfSopId() throws Exception {
        upload("backend-runbook", """
                <html>
                  <head><title>后端服务应急 SOP</title></head>
                  <body>后端服务 服务大面积超时 服务不可用 熔断 降级 核心服务 告警 可用性</body>
                </html>
                """);
        upload("infra-runbook", """
                <html>
                  <head><title>SRE 基础设施故障 SOP</title></head>
                  <body>Kubernetes 集群 Pod 节点 Ingress 网关 API Server 不可用 基础设施故障 故障响应</body>
                </html>
                """);
        upload("security-runbook", """
                <html>
                  <head><title>信息安全 SOP</title></head>
                  <body>安全 入侵 SQL 注入 DDoS WAF 漏洞 恶意软件 应急响应</body>
                </html>
                """);

        List<String> ids = idsFromQuery("服务器挂了");

        assertThat(ids.subList(0, Math.min(2, ids.size())))
                .containsExactlyInAnyOrder("backend-runbook", "infra-runbook");
    }

    private List<String> idsFromQuery(String q) throws Exception {
        MvcResult result = mockMvc.perform(get("/v2/search").queryParam("q", q))
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

    private void upload(String id, String html) throws Exception {
        mockMvc.perform(post("/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UploadDocumentRequest(id, html))))
                .andExpect(status().isCreated());
    }
}
