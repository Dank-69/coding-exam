package com.oncall.phase3.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.common.config.MoonshotProperties;
import com.oncall.common.exception.ExternalServiceException;
import com.oncall.common.util.HtmlParser;
import com.oncall.phase1.model.DocumentEntity;
import com.oncall.phase1.model.SearchResult;
import com.oncall.phase1.service.KeywordSearchService;
import com.oncall.phase1.store.DocumentRepository;
import com.oncall.phase2.service.SemanticSearchService;
import com.oncall.phase3.client.MoonshotChatClient;
import com.oncall.phase3.model.AgentChatResponse;
import com.oncall.phase3.model.AgentMessage;
import com.oncall.phase3.model.AgentToolCall;
import com.oncall.phase3.model.AgentTraceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are an On-Call incident response assistant.
            You MUST ground your answer in SOP files by calling the readFile tool when needed.

            Available SOP files (exact filename required):
            %s

            Rules:
            1) Use the exact filename from the list above — never guess filenames.
            2) Prefer concrete action steps and escalation path.
            3) If uncertainty exists, explicitly say what to verify.
            4) Keep response concise, structured, and practical.
            5) Never claim to read a file unless tool call succeeded.
            """;

    private String buildSystemPrompt() {
        java.util.Collection<DocumentEntity> docs = documentRepository.findAll();
        if (docs.isEmpty()) {
            return SYSTEM_PROMPT_TEMPLATE.formatted("  (no SOP documents loaded — ask user to upload data first)");
        }
        StringBuilder listing = new StringBuilder();
        for (DocumentEntity doc : docs) {
            listing.append("  - ").append(doc.id()).append(".html: ").append(doc.title()).append("\n");
        }
        return SYSTEM_PROMPT_TEMPLATE.formatted(listing.toString());
    }

    private final MoonshotChatClient moonshotChatClient;
    private final SemanticSearchService semanticSearchService;
    private final KeywordSearchService keywordSearchService;
    private final DocumentRepository documentRepository;
    private final ReadFileTool readFileTool;
    private final HtmlParser htmlParser;
    private final ObjectMapper objectMapper;
    private final MoonshotProperties moonshotProperties;

    public AgentService(
            MoonshotChatClient moonshotChatClient,
            SemanticSearchService semanticSearchService,
            KeywordSearchService keywordSearchService,
            DocumentRepository documentRepository,
            ReadFileTool readFileTool,
            HtmlParser htmlParser,
            ObjectMapper objectMapper,
            MoonshotProperties moonshotProperties
    ) {
        this.moonshotChatClient = moonshotChatClient;
        this.semanticSearchService = semanticSearchService;
        this.keywordSearchService = keywordSearchService;
        this.documentRepository = documentRepository;
        this.readFileTool = readFileTool;
        this.htmlParser = htmlParser;
        this.objectMapper = objectMapper;
        this.moonshotProperties = moonshotProperties;
    }

    public AgentChatResponse chat(String message, List<AgentMessage> history, Consumer<AgentTraceEvent> tracer) {
        long start = System.currentTimeMillis();
        String query = message == null ? "" : message.trim();
        if (query.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        int historySize = history == null ? 0 : history.size();
        boolean apiEnabled = moonshotChatClient.isEnabled();
        log.info("v3 agent chat start q='{}' history={} apiEnabled={} provider={} model={}",
                logMessage(query), historySize, apiEnabled, moonshotProperties.providerName(), moonshotProperties.chatModel());
        Consumer<AgentTraceEvent> sink = tracer == null ? event -> { } : tracer;
        sink.accept(new AgentTraceEvent("thinking", "正在分析问题并定位相关 SOP..."));

        if (!apiEnabled) {
            String reason = "chat API key missing";
            log.warn("v3 agent chat mode=fallback reason='{}' provider={} q='{}'",
                    reason, moonshotProperties.providerName(), logMessage(query));
            AgentChatResponse response = localFallbackChat(query, history, sink);
            log.info("v3 agent chat end mode=fallback reason='{}' q='{}' toolCalls={} totalTokens={} took={}ms",
                    reason, logMessage(query), toolCallCount(response), response.totalTokens(), System.currentTimeMillis() - start);
            return response;
        }

        try {
            log.info("v3 agent chat mode=api provider={} model={} q='{}'",
                    moonshotProperties.providerName(), moonshotProperties.chatModel(), logMessage(query));
            AgentChatResponse response = aiNativeChat(query, history, sink);
            log.info("v3 agent chat end mode=api q='{}' toolCalls={} totalTokens={} took={}ms",
                    logMessage(query), toolCallCount(response), response.totalTokens(), System.currentTimeMillis() - start);
            return response;
        } catch (ExternalServiceException ex) {
            log.warn("v3 agent fallback on chat api error query='{}' reason={}", query, ex.getMessage());
            sink.accept(new AgentTraceEvent("error", "Chat API failed, fallback to local strategy"));
            AgentChatResponse response = localFallbackChat(query, history, sink);
            log.info("v3 agent chat end mode=fallback reason='api_error: {}' q='{}' toolCalls={} totalTokens={} took={}ms",
                    ex.getMessage(), logMessage(query), toolCallCount(response), response.totalTokens(), System.currentTimeMillis() - start);
            return response;
        }
    }

    private AgentChatResponse aiNativeChat(String query, List<AgentMessage> history, Consumer<AgentTraceEvent> sink) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));
        if (history != null) {
            for (AgentMessage msg : history) {
                if (msg == null || msg.role() == null || msg.content() == null) {
                    continue;
                }
                String role = msg.role().trim().toLowerCase(Locale.ROOT);
                if (!role.equals("user") && !role.equals("assistant")) {
                    continue;
                }
                messages.add(Map.of("role", role, "content", msg.content()));
            }
        }
        messages.add(Map.of("role", "user", "content", query));

        List<Map<String, Object>> tools = List.of(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "readFile",
                        "description", "Read a file from data/ by exact filename, for example sop-001.html",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filename", Map.of(
                                                "type", "string",
                                                "description", "The exact file name in data/, such as sop-001.html"
                                        )
                                ),
                                "required", List.of("filename"),
                                "additionalProperties", false
                        )
                )
        ));

        List<AgentToolCall> toolCalls = new ArrayList<>();
        long totalTokens = 0;
        String finalAnswer = "";

        for (int round = 0; round < Math.max(1, moonshotProperties.maxToolRounds()); round++) {
            log.info("v3 chat api request round={} messages={} tools={} provider={} model={}",
                    round + 1, messages.size(), tools.size(), moonshotProperties.providerName(), moonshotProperties.chatModel());
            JsonNode root = moonshotChatClient.chatCompletion(messages, tools);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new ExternalServiceException("Chat API response has no choices");
            }
            JsonNode assistant = choices.get(0).path("message");
            totalTokens = Math.max(totalTokens, root.path("usage").path("total_tokens").asLong(totalTokens));

            JsonNode toolCallsNode = assistant.path("tool_calls");
            if (toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                Map<String, Object> assistantWithTools = new LinkedHashMap<>();
                assistantWithTools.put("role", "assistant");
                assistantWithTools.put("content", assistant.path("content").isNull() ? "" : assistant.path("content").asText(""));
                assistantWithTools.put("tool_calls", objectMapper.convertValue(toolCallsNode, new TypeReference<List<Map<String, Object>>>() { }));
                messages.add(assistantWithTools);

                for (JsonNode tc : toolCallsNode) {
                    String toolCallId = tc.path("id").asText("");
                    String functionName = tc.path("function").path("name").asText("");
                    String argumentsRaw = tc.path("function").path("arguments").asText("{}");
                    log.info("v3 tool call tool={} args={}", functionName, argumentsRaw);
                    sink.accept(new AgentTraceEvent("tool_call", "{\"tool\":\"" + functionName + "\",\"args\":" + argumentsRaw + "}"));

                    ToolExecutionResult execution = executeTool(functionName, argumentsRaw);
                    toolCalls.add(execution.toolCall());
                    log.info("v3 tool result tool={} success={} length={}",
                            execution.toolCall().tool(), execution.toolCall().success(), execution.toolCall().length());
                    sink.accept(new AgentTraceEvent("tool_result", execution.tracePayload()));

                    Map<String, Object> toolMessage = new LinkedHashMap<>();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", toolCallId);
                    toolMessage.put("content", execution.modelPayload());
                    messages.add(toolMessage);
                }
                continue;
            }

            finalAnswer = assistant.path("content").isNull() ? "" : assistant.path("content").asText("");
            if (!finalAnswer.isBlank()) {
                break;
            }
        }

        if (finalAnswer.isBlank()) {
            throw new ExternalServiceException("Chat API returned empty final answer");
        }
        for (String line : splitAnswer(finalAnswer)) {
            sink.accept(new AgentTraceEvent("message", line));
        }
        sink.accept(new AgentTraceEvent("done", "{\"totalTokens\":" + totalTokens + "}"));
        return new AgentChatResponse(finalAnswer, toolCalls, totalTokens);
    }

    private ToolExecutionResult executeTool(String functionName, String argumentsRaw) {
        if (!"readFile".equals(functionName)) {
            String trace = "{\"success\":false,\"error\":\"unsupported tool: " + escapeJson(functionName) + "\"}";
            AgentToolCall call = new AgentToolCall(functionName, Map.of(), false, 0, "unsupported tool");
            return new ToolExecutionResult(call, trace, trace);
        }

        String filename = parseFilename(argumentsRaw);
        try {
            String raw = readFileTool.readFile(filename);
            HtmlParser.ParsedHtml parsed = htmlParser.parse(raw);
            String excerpt = excerpt(parsed.plainText());
            AgentToolCall call = new AgentToolCall("readFile", Map.of("filename", filename), true, raw.length(), excerpt);
            String trace = "{\"success\":true,\"length\":" + raw.length() + ",\"title\":\"" + escapeJson(parsed.title()) + "\"}";
            return new ToolExecutionResult(call, trace, raw);
        } catch (Exception ex) {
            String err = ex.getMessage() == null ? "tool execution failed" : ex.getMessage();
            AgentToolCall call = new AgentToolCall("readFile", Map.of("filename", filename), false, 0, err);
            String trace = "{\"success\":false,\"error\":\"" + escapeJson(err) + "\"}";
            return new ToolExecutionResult(call, trace, trace);
        }
    }

    private String parseFilename(String argumentsRaw) {
        try {
            JsonNode node = objectMapper.readTree(argumentsRaw == null ? "{}" : argumentsRaw);
            String filename = node.path("filename").asText("").trim();
            if (filename.isBlank()) {
                throw new IllegalArgumentException("filename is required");
            }
            return filename;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid tool arguments: " + argumentsRaw, ex);
        }
    }

    private AgentChatResponse localFallbackChat(String query, List<AgentMessage> history, Consumer<AgentTraceEvent> sink) {
        List<DocumentEntity> candidates = resolveCandidates(query);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No SOP documents available. Please upload data first.");
        }
        log.info("v3 local fallback candidates q='{}' ids={}", logMessage(query), candidateIds(candidates));

        List<AgentToolCall> toolCalls = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        List<String> groundedEvidence = new ArrayList<>();
        for (DocumentEntity candidate : candidates) {
            String filename = candidate.id() + ".html";
            sink.accept(new AgentTraceEvent("tool_call", "{\"tool\":\"readFile\",\"args\":{\"filename\":\"" + filename + "\"}}"));
            try {
                String raw = readFileTool.readFile(filename);
                HtmlParser.ParsedHtml parsed = htmlParser.parse(raw);
                String excerpt = excerpt(parsed.plainText());
                groundedEvidence.addAll(formatGroundedEvidence(filename, parsed.plainText(), query));
                toolCalls.add(new AgentToolCall("readFile", Map.of("filename", filename), true, raw.length(), excerpt));
                log.info("v3 local fallback tool result filename={} success=true length={}", filename, raw.length());
                sink.accept(new AgentTraceEvent("tool_result", "{\"success\":true,\"length\":" + raw.length() + ",\"title\":\"" + escapeJson(parsed.title()) + "\"}"));
                evidence.add(filename + "（" + parsed.title() + "）");
            } catch (Exception ex) {
                String err = ex.getMessage() == null ? "read failed" : ex.getMessage();
                toolCalls.add(new AgentToolCall("readFile", Map.of("filename", filename), false, 0, err));
                log.warn("v3 local fallback tool result filename={} success=false reason={}", filename, err);
                sink.accept(new AgentTraceEvent("tool_result", "{\"success\":false,\"error\":\"" + escapeJson(err) + "\"}"));
            }
        }
        String answer = """
                已基于 SOP 给出处置建议（本次为本地降级策略）：
                1. 先确认故障影响范围和告警级别（P0/P1）。
                2. 按 SOP 执行快速止血：限流、降级、回滚、隔离。
                3. 同步升级路径并明确负责人，持续更新进展。
                4. 恢复后补充复盘：根因、修复、预防项。

                参考文档：%s
                """.formatted(String.join("，", evidence));
        if (!groundedEvidence.isEmpty()) {
            answer += "\n\nSOP 关键依据：\n" + String.join("\n", groundedEvidence);
        }
        if (history != null && !history.isEmpty()) {
            answer += "\n（已结合历史对话上下文）";
        }
        for (String line : splitAnswer(answer)) {
            sink.accept(new AgentTraceEvent("message", line));
        }
        long totalTokens = Math.max(1L, answer.length() / 4L);
        sink.accept(new AgentTraceEvent("done", "{\"totalTokens\":" + totalTokens + "}"));
        return new AgentChatResponse(answer, toolCalls, totalTokens);
    }

    private List<DocumentEntity> resolveCandidates(String query) {
        Map<String, Double> scores = new LinkedHashMap<>();
        addRankedScores(scores, semanticSearchService.search(query), 3.0);
        addRankedScores(scores, keywordSearchService.search(query), 1.5);
        if (scores.isEmpty()) {
            documentRepository.findAll().forEach(doc -> scores.put(doc.id(), 0.0));
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(3)
                .map(entry -> documentRepository.findById(entry.getKey()))
                .filter(doc -> doc != null)
                .collect(Collectors.toList());
    }

    private void addRankedScores(Map<String, Double> scores, List<SearchResult> results, double baseWeight) {
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            double rankWeight = baseWeight / (i + 1.0);
            scores.merge(result.id(), rankWeight + result.score(), Double::sum);
        }
    }

    private List<String> formatGroundedEvidence(String filename, String plainText, String query) {
        List<String> lines = extractRelevantLines(plainText, query);
        List<String> formatted = new ArrayList<>();
        for (int i = 0; i < lines.size() && i < 2; i++) {
            formatted.add("- [" + filename + "] " + lines.get(i));
        }
        return formatted;
    }

    private List<String> extractRelevantLines(String plainText, String query) {
        List<String> candidates = splitEvidenceCandidates(plainText);
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> terms = queryTerms(query);
        List<EvidenceLine> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String line = normalizeEvidenceLine(candidates.get(i));
            double score = evidenceScore(line, terms, query);
            if (score > 0) {
                scored.add(new EvidenceLine(line, score, i));
            }
        }

        if (scored.isEmpty()) {
            return candidates.stream()
                    .map(this::normalizeEvidenceLine)
                    .filter(line -> !line.isBlank())
                    .limit(3)
                    .toList();
        }

        scored.sort(Comparator.comparingDouble(EvidenceLine::score).reversed()
                .thenComparingInt(EvidenceLine::index));
        return scored.stream()
                .map(EvidenceLine::text)
                .distinct()
                .limit(3)
                .toList();
    }

    private List<String> splitEvidenceCandidates(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return List.of();
        }
        String normalized = plainText.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] parts = normalized.split("(?<=[。！？!?；;])\\s*");
        List<String> candidates = new ArrayList<>();
        for (String part : parts) {
            String line = part == null ? "" : part.trim();
            if (line.length() >= 12) {
                candidates.add(line);
            }
        }
        return candidates;
    }

    private List<String> queryTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String[] parts = query.toLowerCase(Locale.ROOT)
                .split("[\\s,，.。:：;；!?！？()（）\\[\\]{}<>《》\"'、/\\\\]+");
        List<String> terms = new ArrayList<>();
        for (String part : parts) {
            String term = part == null ? "" : part.trim();
            if (term.length() >= 2 && !terms.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    private double evidenceScore(String line, List<String> terms, String query) {
        if (line == null || line.isBlank()) {
            return 0;
        }
        String lowerLine = line.toLowerCase(Locale.ROOT);
        String lowerQuery = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        double score = 0;
        if (!lowerQuery.isBlank() && lowerLine.contains(lowerQuery)) {
            score += 20;
        }
        for (String term : terms) {
            if (lowerLine.contains(term)) {
                score += Math.min(8, Math.max(2, term.length()));
            }
        }
        if (containsAny(lowerLine, "p0", "p1", "oom", "outofmemory", "回滚", "限流", "降级", "升级", "隔离")) {
            score += 2;
        }
        return score;
    }

    private boolean containsAny(String text, String... probes) {
        for (String probe : probes) {
            if (text.contains(probe)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeEvidenceLine(String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 180) + "...";
    }

    private List<String> splitAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        return answer.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private String excerpt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return "";
        }
        String trimmed = plainText.trim();
        return trimmed.length() <= 140 ? trimmed : trimmed.substring(0, 140) + "...";
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private int toolCallCount(AgentChatResponse response) {
        return response.toolCalls() == null ? 0 : response.toolCalls().size();
    }

    private String candidateIds(List<DocumentEntity> candidates) {
        return candidates.stream()
                .map(DocumentEntity::id)
                .collect(Collectors.joining(","));
    }

    private String logMessage(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    private record ToolExecutionResult(
            AgentToolCall toolCall,
            String tracePayload,
            String modelPayload
    ) {
    }

    private record EvidenceLine(
            String text,
            double score,
            int index
    ) {
    }
}
