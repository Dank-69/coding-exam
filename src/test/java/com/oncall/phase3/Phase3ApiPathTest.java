package com.oncall.phase3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.phase1.dto.UploadDocumentRequest;
import com.oncall.phase3.client.MoonshotChatClient;
import com.oncall.phase3.model.AgentChatRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "oncall.bootstrap.enabled=false",
        "moonshot.api-key=test-key",
        "moonshot.embedding-api-key=",
        "moonshot.embeddings-enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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

        String html = Files.readString(Path.of("data", "sop-001.html"), StandardCharsets.UTF_8);
        mockMvc.perform(post("/v1/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UploadDocumentRequest("sop-001", html))))
                .andExpect(status().isCreated());
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
    }

    private JsonNode toolCallResponse() {
        return objectMapper.valueToTree(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of(
                                "role", "assistant",
                                "content", "",
                                "tool_calls", List.of(Map.of(
                                        "id", "call_1",
                                        "type", "function",
                                        "function", Map.of(
                                                "name", "readFile",
                                                "arguments", "{\"filename\":\"sop-001.html\"}"
                                        )
                                ))
                        )
                )),
                "usage", Map.of("total_tokens", 12)
        ));
    }

    private JsonNode finalAnswerResponse() {
        return objectMapper.valueToTree(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of(
                                "role", "assistant",
                                "content", "Use sop-001.html: collect JVM evidence, mitigate traffic, and recover safely."
                        )
                )),
                "usage", Map.of("total_tokens", 30)
        ));
    }
}
