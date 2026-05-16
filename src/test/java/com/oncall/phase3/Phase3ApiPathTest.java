package com.oncall.phase3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.common.exception.ExternalServiceException;
import com.oncall.phase1.dto.UploadDocumentRequest;
import com.oncall.phase3.client.MoonshotChatClient;
import com.oncall.phase3.model.AgentChatRequest;
import com.oncall.phase3.model.AgentMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "oncall.bootstrap.enabled=false",
        "moonshot.api-key=test-key",
        "moonshot.embedding-api-key=",
        "moonshot.embeddings-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class Phase3ApiPathTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MoonshotChatClient chatClient;

    @BeforeEach
    void setUp() throws Exception {
        when(chatClient.isEnabled()).thenReturn(true);
        when(chatClient.chatCompletion(Mockito.anyList(), Mockito.anyList()))
                .thenReturn(toolCallResponse(), finalAnswerResponse());

        uploadSop("sop-001");
    }

    @Test
    void v3Chat_shouldUseChatApiFunctionCallingAndReadFileTool() throws Exception {
        MvcResult result = mockMvc.perform(post("/v3/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentChatRequest(
                                "What should I do for service OOM?",
                                List.of()
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("answer").asText()).contains("sop-001.html");
        assertThat(root.path("toolCalls")).hasSize(1);
        assertThat(root.path("toolCalls").get(0).path("success").asBoolean()).isTrue();
        assertThat(root.path("toolCalls").get(0).path("args").path("filename").asText())
                .isEqualTo("sop-001.html");

        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatClient, times(2)).chatCompletion(messagesCaptor.capture(), Mockito.anyList());
        List<?> secondRoundMessages = messagesCaptor.getAllValues().get(1);
        boolean sentToolResultBack = secondRoundMessages.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(message -> "tool".equals(message.get("role")));
        assertThat(sentToolResultBack).isTrue();
        Map<?, ?> assistantToolCallMessage = secondRoundMessages.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(message -> "assistant".equals(message.get("role")) && message.containsKey("tool_calls"))
                .findFirst()
                .orElseThrow();
        assertThat(assistantToolCallMessage.get("reasoning_content")).isEqualTo("Need to inspect SOP evidence.");
        String toolPayload = secondRoundMessages.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(message -> "tool".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content")))
                .findFirst()
                .orElse("");
        assertThat(toolPayload)
                .contains("Filename: sop-001.html")
                .contains("Relevant SOP excerpts")
                .doesNotContain("<!DOCTYPE html>");
        assertThat(toolPayload.length()).isLessThanOrEqualTo(1_000);
    }

    @Test
    void v3Chat_shouldRejectReadFileOutsideCandidateSet() throws Exception {
        when(chatClient.chatCompletion(Mockito.anyList(), Mockito.anyList()))
                .thenReturn(toolCallResponse("sop-999.html"), finalAnswerResponse("I cannot use that file because it was not an allowed candidate."));

        MvcResult result = mockMvc.perform(post("/v3/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentChatRequest(
                                "What should I do for service OOM?",
                                List.of()
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("toolCalls")).hasSize(1);
        assertThat(root.path("toolCalls").get(0).path("success").asBoolean()).isFalse();
        assertThat(root.path("toolCalls").get(0).path("excerpt").asText())
                .contains("file not found");

        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatClient, times(2)).chatCompletion(messagesCaptor.capture(), Mockito.anyList());
        List<?> firstRoundMessages = messagesCaptor.getAllValues().get(0);
        String systemPrompt = firstRoundMessages.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(message -> "system".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content")))
                .findFirst()
                .orElse("");
        assertThat(systemPrompt).contains("sop-001.html");
        assertThat(systemPrompt).doesNotContain("sop-999.html");
    }

    @Test
    void v3Chat_shouldKeepToolAvailableWhenRecommendedRouteMayNeedMoreEvidence() throws Exception {
        uploadSop("sop-003");
        uploadSop("sop-004");
        when(chatClient.chatCompletion(Mockito.anyList(), Mockito.anyList()))
                .thenReturn(
                        toolCallResponse("sop-001.html", "sop-003.html", "sop-004.html"),
                        finalAnswerResponse("Final P0 response based on the already-read SOP evidence.")
                );

        MvcResult result = mockMvc.perform(post("/v3/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentChatRequest(
                                "unmatched-nonce-abc-xyz",
                                List.of()
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("toolCalls")).hasSize(3);
        assertThat(root.path("answer").asText()).contains("Final P0 response");

        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatClient, times(2)).chatCompletion(messagesCaptor.capture(), toolsCaptor.capture());
        List<?> secondRequestMessages = messagesCaptor.getAllValues().get(1);
        boolean sentToolResultBack = secondRequestMessages.stream()
                .filter(Map.class::isInstance)
                .map(message -> (Map<?, ?>) message)
                .anyMatch(message -> "tool".equals(message.get("role")));
        assertThat(sentToolResultBack).isTrue();
        assertThat(toolsCaptor.getAllValues().get(1))
                .as("the prompt now recommends routes but still lets the model decide whether another module is needed")
                .isNotEmpty();
    }

    @Test
    void v3Chat_shouldExposeAllLoadedSopsWhenRetrievalHasNoHit() throws Exception {
        uploadSop("sop-002");
        when(chatClient.chatCompletion(Mockito.anyList(), Mockito.anyList()))
                .thenReturn(toolCallResponse("sop-002.html"), finalAnswerResponse("Use sop-002.html after checking the incident symptoms."));

        MvcResult result = mockMvc.perform(post("/v3/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentChatRequest(
                                "unmatched-nonce-abc-xyz",
                                List.of()
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("toolCalls")).hasSize(1);
        assertThat(root.path("toolCalls").get(0).path("success").asBoolean()).isTrue();
        assertThat(root.path("toolCalls").get(0).path("args").path("filename").asText())
                .isEqualTo("sop-002.html");

        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatClient, times(2)).chatCompletion(messagesCaptor.capture(), Mockito.anyList());
        List<?> firstRoundMessages = messagesCaptor.getAllValues().get(0);
        String systemPrompt = firstRoundMessages.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(message -> "system".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content")))
                .findFirst()
                .orElse("");
        assertThat(systemPrompt)
                .contains("sop-001.html")
                .contains("sop-002.html");
    }

    @Test
    void v3Chat_shouldExposeP0WorkflowCoreSopsAndSummaries() throws Exception {
        uploadSop("sop-004");
        when(chatClient.chatCompletion(Mockito.anyList(), Mockito.anyList()))
                .thenReturn(toolCallResponse("sop-004.html"), finalAnswerResponse("Use sop-001.html and sop-004.html for P0 response."));

        MvcResult result = mockMvc.perform(post("/v3/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentChatRequest(
                                "P0故障级别流程应该怎么处理",
                                List.of()
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("answer").asText()).contains("P0 response");

        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatClient, times(2)).chatCompletion(messagesCaptor.capture(), Mockito.anyList());
        List<?> firstRoundMessages = messagesCaptor.getAllValues().get(0);
        String systemPrompt = firstRoundMessages.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(message -> "system".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content")))
                .findFirst()
                .orElse("");
        assertThat(systemPrompt)
                .contains("Required routing workflow")
                .contains("First call readFile(\"sop_index.json\")")
                .contains("Fixed module structure")
                .contains("recommendedNextFilesForThisQuestion")
                .contains("recommendations only")
                .contains("sop-001.html")
                .contains("sop-001/index.json")
                .contains("sop-004.html")
                .contains("sop-004/index.json")
                .contains("03_troubleshooting.index.md")
                .contains("escalation");
    }

    @Test
    void v3Chat_shouldAllowStrongMatchedSopWhenRetrievalHasNoHit() throws Exception {
        uploadSop("sop-003");
        when(chatClient.chatCompletion(Mockito.anyList(), Mockito.anyList()))
                .thenReturn(toolCallResponse("sop-003.html"), finalAnswerResponse("Use sop-003.html for frontend asset recovery."));

        MvcResult result = mockMvc.perform(post("/v3/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentChatRequest(
                                "jsbundlemissing",
                                List.of()
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("toolCalls")).hasSize(1);
        assertThat(root.path("toolCalls").get(0).path("success").asBoolean()).isTrue();
        assertThat(root.path("toolCalls").get(0).path("args").path("filename").asText())
                .isEqualTo("sop-003.html");

        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatClient, times(2)).chatCompletion(messagesCaptor.capture(), Mockito.anyList());
        List<?> firstRoundMessages = messagesCaptor.getAllValues().get(0);
        String systemPrompt = firstRoundMessages.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(message -> "system".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content")))
                .findFirst()
                .orElse("");
        assertThat(systemPrompt).contains("sop-003.html");
        assertThat(systemPrompt).doesNotContain("sop-001.html");
    }

    @Test
    void v3Chat_shouldExposeDeterministicIndexAsReadFileOnlyRoutingFile() throws Exception {
        when(chatClient.chatCompletion(Mockito.anyList(), Mockito.anyList()))
                .thenReturn(
                        toolCallResponse("sop-001/index.json"),
                        toolCallResponse("sop-001/03_02_oom.md"),
                        finalAnswerResponse("Use the OOM scenario evidence from sop-001/03_02_oom.md.")
                );

        MvcResult result = mockMvc.perform(post("/v3/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentChatRequest(
                                "服务 OOM 了怎么办？",
                                List.of()
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("toolCalls")).hasSize(2);
        assertThat(root.path("toolCalls").get(0).path("tool").asText()).isEqualTo("readFile");
        assertThat(root.path("toolCalls").get(0).path("success").asBoolean()).isTrue();
        assertThat(root.path("toolCalls").get(0).path("args").path("filename").asText()).isEqualTo("sop-001/index.json");
        assertThat(root.path("toolCalls").get(0).path("excerpt").asText()).contains("OOM");
        assertThat(root.path("toolCalls").get(1).path("tool").asText()).isEqualTo("readFile");
        assertThat(root.path("toolCalls").get(1).path("args").path("filename").asText()).isEqualTo("sop-001/03_02_oom.md");

        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatClient, times(3)).chatCompletion(messagesCaptor.capture(), toolsCaptor.capture());
        assertThat(toolsCaptor.getAllValues().get(0)).hasSize(1);
        Map<?, ?> readFileTool = (Map<?, ?>) toolsCaptor.getAllValues().get(0).get(0);
        Map<?, ?> readFileFunction = (Map<?, ?>) readFileTool.get("function");
        assertThat(readFileFunction.get("name")).isEqualTo("readFile");
        List<?> secondRoundMessages = messagesCaptor.getAllValues().get(1);
        String toolPayload = secondRoundMessages.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(message -> "tool".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content")))
                .findFirst()
                .orElse("");
        assertThat(toolPayload)
                .contains("Filename: sop-001/index.json")
                .contains("Relevant SOP excerpts")
                .contains("OOM")
                .doesNotContain("<!DOCTYPE html>");
    }

    @Test
    void v3Chat_shouldUseHistoryTopicForTopiclessFollowUp() throws Exception {
        uploadSop("sop-002");
        when(chatClient.chatCompletion(Mockito.anyList(), Mockito.anyList()))
                .thenReturn(
                        toolCallResponse("sop-002/05_forbidden.md"),
                        finalAnswerResponse("数据库禁止操作应以 sop-002/05_forbidden.md 为准。")
                );

        MvcResult result = mockMvc.perform(post("/v3/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentChatRequest(
                                "那我应该注意什么，有什么是我不能操作的",
                                List.of(
                                        new AgentMessage("user", "数据库从库连接不到主库"),
                                        new AgentMessage("assistant", "这个问题对应 sop-002 数据库 DBA SOP 的主从复制故障场景。")
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("toolCalls")).hasSize(1);
        assertThat(root.path("toolCalls").get(0).path("success").asBoolean()).isTrue();
        assertThat(root.path("toolCalls").get(0).path("args").path("filename").asText())
                .isEqualTo("sop-002/05_forbidden.md");

        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatClient, times(2)).chatCompletion(messagesCaptor.capture(), Mockito.anyList());
        List<?> firstRoundMessages = messagesCaptor.getAllValues().get(0);
        String systemPrompt = firstRoundMessages.stream()
                .filter(Map.class::isInstance)
                .map(message -> (Map<?, ?>) message)
                .filter(message -> "system".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content")))
                .findFirst()
                .orElse("");
        assertThat(systemPrompt)
                .contains("Use prior SOP topic scope: sop-002")
                .contains("sop-002/index.json")
                .contains("sop-002/05_forbidden.md")
                .doesNotContain("sop-001/index.json")
                .doesNotContain("sop-003/index.json");
    }

    @Test
    void v3Chat_shouldSwitchTopicWhenCurrentQuestionHasExplicitNewTopic() throws Exception {
        uploadSop("sop-002");
        uploadSop("sop-008");
        when(chatClient.chatCompletion(Mockito.anyList(), Mockito.anyList()))
                .thenReturn(
                        toolCallResponse("sop-008/03_02_model_quality_drop.md"),
                        finalAnswerResponse("推荐质量下降应按 sop-008 的模型效果下降场景处理。")
                );

        MvcResult result = mockMvc.perform(post("/v3/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AgentChatRequest(
                                "推荐结果质量下降了",
                                List.of(
                                        new AgentMessage("user", "数据库从库连接不到主库"),
                                        new AgentMessage("assistant", "这个问题对应 sop-002 数据库 DBA SOP 的主从复制故障场景。")
                                )
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(root.path("toolCalls")).hasSize(1);
        assertThat(root.path("toolCalls").get(0).path("success").asBoolean()).isTrue();
        assertThat(root.path("toolCalls").get(0).path("args").path("filename").asText())
                .isEqualTo("sop-008/03_02_model_quality_drop.md");

        ArgumentCaptor<List> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatClient, times(2)).chatCompletion(messagesCaptor.capture(), Mockito.anyList());
        List<?> firstRoundMessages = messagesCaptor.getAllValues().get(0);
        String systemPrompt = firstRoundMessages.stream()
                .filter(Map.class::isInstance)
                .map(message -> (Map<?, ?>) message)
                .filter(message -> "system".equals(message.get("role")))
                .map(message -> String.valueOf(message.get("content")))
                .findFirst()
                .orElse("");
        assertThat(systemPrompt)
                .contains("Current question has explicit SOP topic hint(s): sop-008")
                .contains("sop-008/index.json")
                .contains("sop-008/03_02_model_quality_drop.md")
                .doesNotContain("sop-002/index.json");
    }

    @Test
    void v3Stream_shouldFallbackWithoutTerminalErrorEventWhenChatApiFails() throws Exception {
        when(chatClient.chatCompletion(Mockito.anyList(), Mockito.anyList()))
                .thenThrow(new ExternalServiceException("Chat API failed: token limit"));

        MvcResult result = mockMvc.perform(get("/v3/chat").queryParam("message", "P0 故障的响应流程是什么？"))
                .andReturn();

        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andReturn();
        }

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .contains("Chat API 调用失败，正在切换到本地 SOP 策略")
                .contains("event:tool_call")
                .contains("event:done");
        assertThat(body).doesNotContain("event:error");
    }

    @Test
    void v3StreamPost_shouldSendHistoryInBody() throws Exception {
        uploadSop("sop-002");
        when(chatClient.chatCompletion(Mockito.anyList(), Mockito.anyList()))
                .thenReturn(
                        toolCallResponse("sop-002/05_forbidden.md"),
                        finalAnswerResponse("数据库主从问题的禁止操作来自 sop-002/05_forbidden.md。")
                );

        MvcResult result = mockMvc.perform(post("/v3/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(new AgentChatRequest(
                                "那我需要注意什么，比如说不能做什么",
                                List.of(
                                        new AgentMessage("user", "数据库主从连接断开怎么办"),
                                        new AgentMessage("assistant", "该问题对应 sop-002 数据库 DBA SOP。")
                                )
                        ))))
                .andReturn();

        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isOk())
                    .andReturn();
        }

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .contains("event:tool_call")
                .contains("sop-002/05_forbidden.md")
                .contains("event:done");
    }

    private void uploadSop(String id) throws Exception {
        String html = Files.readString(Path.of("data", id + ".html"), StandardCharsets.UTF_8);
        mockMvc.perform(post("/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UploadDocumentRequest(id, html))))
                .andExpect(status().isCreated());
    }

    private JsonNode toolCallResponse() {
        return toolCallResponse("sop-001.html");
    }

    private JsonNode toolCallResponse(String... filenames) {
        List<Map<String, Object>> toolCalls = new java.util.ArrayList<>();
        for (int i = 0; i < filenames.length; i++) {
            String filename = filenames[i];
            toolCalls.add(Map.of(
                    "id", "call_" + (i + 1),
                    "type", "function",
                    "function", Map.of(
                            "name", "readFile",
                            "arguments", "{\"filename\":\"" + filename + "\"}"
                    )
            ));
        }
        return objectMapper.valueToTree(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of(
                                "role", "assistant",
                                "content", "",
                                "reasoning_content", "Need to inspect SOP evidence.",
                                "tool_calls", toolCalls
                        )
                )),
                "usage", Map.of("total_tokens", 12)
        ));
    }

    private JsonNode finalAnswerResponse() {
        return finalAnswerResponse("Use sop-001.html: collect JVM evidence, mitigate traffic, and recover safely.");
    }

    private JsonNode finalAnswerResponse(String content) {
        return objectMapper.valueToTree(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of(
                                "role", "assistant",
                                "content", content
                        )
                )),
                "usage", Map.of("total_tokens", 30)
        ));
    }
}
