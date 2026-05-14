package com.oncall.phase3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.phase1.dto.UploadDocumentRequest;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class Phase3ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void uploadDocuments() throws Exception {
        for (int i = 1; i <= 10; i++) {
            String id = String.format("sop-%03d", i);
            Path file = Path.of("data", id + ".html");
            String html = Files.readString(file, StandardCharsets.UTF_8);
            mockMvc.perform(post("/v1/documents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new UploadDocumentRequest(id, html))))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void oomQuestion_shouldReadSop001() throws Exception {
        JsonNode root = chatJson("服务 OOM 了怎么办");
        assertThat(root.path("toolCalls")).isNotEmpty();
        assertThat(root.path("toolCalls").get(0).path("tool").asText()).isEqualTo("readFile");
        assertThat(root.path("toolCalls").get(0).path("args").path("filename").asText()).isEqualTo("sop-001.html");
        assertThat(root.path("answer").asText()).contains("sop-001");
    }

    @Test
    void databaseQuestion_shouldReadSop002() throws Exception {
        JsonNode root = chatJson("数据库主从延迟超过30秒怎么处理？");
        assertThat(root.path("toolCalls").isArray()).isTrue();
        assertThat(root.path("toolCalls").size()).isGreaterThan(0);
        assertThat(root.path("answer").asText()).contains("sop-002");
    }

    @Test
    void securityQuestion_shouldReadSop005() throws Exception {
        JsonNode root = chatJson("怀疑有人入侵了系统");
        assertThat(root.path("toolCalls").get(0).path("args").path("filename").asText()).isEqualTo("sop-005.html");
    }

    @Test
    void aiQuestion_shouldReadSop008() throws Exception {
        JsonNode root = chatJson("推荐结果质量下降了");
        assertThat(root.path("toolCalls").get(0).path("args").path("filename").asText()).isEqualTo("sop-008.html");
    }

    @Test
    void p0Question_shouldStreamTrace() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/chat").queryParam("message", "P0 故障的响应流程是什么？"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("tool_call")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("done")));
    }

    private JsonNode chatJson(String message) throws Exception {
        MvcResult result = mockMvc.perform(post("/v3/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new com.oncall.phase3.model.AgentChatRequest(message, List.of()))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}

