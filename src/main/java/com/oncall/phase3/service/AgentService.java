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
import com.oncall.phase3.model.SopIndex;
import com.oncall.phase3.model.SopScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final int AGENT_CANDIDATE_LIMIT = 6;
    private static final int RETRIEVAL_RESULT_LIMIT = 5;
    private static final int API_TOOL_CALL_LIMIT = 3;
    private static final int TOOL_MODEL_PAYLOAD_LIMIT = 1_000;
    private static final double STRONG_MATCH_WEIGHT = 1_000.0;
    private static final Map<String, List<String>> SOP_ALIASES = Map.ofEntries(
            Map.entry("sop-001", List.of("后端", "服务器", "oom", "outofmemory", "outofmemoryerror", "jvm", "内存溢出", "堆内存", "服务超时", "接口超时")),
            Map.entry("sop-002", List.of("数据库", "dba", "主从", "主库", "从库", "binlog", "gtid", "复制延迟", "主从延迟", "慢查询", "连接池", "mysql", "redis", "数据恢复", "备份恢复")),
            Map.entry("sop-003", List.of("前端", "web", "白屏", "静态资源", "资源加载", "js", "javascript", "chunkloaderror", "浏览器", "兼容性")),
            Map.entry("sop-004", List.of("sre", "k8s", "kubernetes", "pod", "ingress", "prometheus", "grafana", "容量规划", "基础设施", "监控告警")),
            Map.entry("sop-005", List.of("安全", "入侵", "黑客", "攻击", "ddos", "漏洞", "waf", "ids", "siem", "数据泄露", "被黑", "恶意软件")),
            Map.entry("sop-006", List.of("数据平台", "etl", "spark", "flink", "hive", "kafka", "数据管道", "任务失败", "数据延迟")),
            Map.entry("sop-007", List.of("移动", "app", "崩溃率", "热修复", "ios", "android", "推送", "客户端", "jspatch", "oom", "内存泄漏")),
            Map.entry("sop-008", List.of("ai", "算法", "模型", "机器学习", "推荐", "gpu", "推理", "特征", "召回", "精排", "效果下降", "质量下降")),
            Map.entry("sop-009", List.of("qa", "测试", "自动化测试", "发版", "selenium", "质量保障", "测试环境")),
            Map.entry("sop-010", List.of("网络", "cdn", "dns", "负载均衡", "lb", "bgp", "路由", "带宽", "cdn节点", "dns解析", "dnssec"))
    );
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are an On-Call incident response assistant.
            You have exactly one tool: readFile(fname: string) -> string.
            readFile can read safe relative paths under data/, including original SOP html files,
            the global sop_index.json file, and generated sop-xxx/ module files.
            You MUST ground your final answer in concrete SOP evidence from module markdown or original html,
            not only in a routing index file.

            Conversation context routing rules:
            - The current user question is authoritative. If it names a new SOP topic, domain, system, or concrete symptom, route by the current question even when prior turns discussed another SOP.
            - Use prior conversation context only when the current question is a follow-up without an explicit new topic, such as asking "what should I notice", "what must not be done", "how to escalate", or "what command should I use".
            - For those follow-up module questions, keep the SOP topic scope to the most recent clearly identified SOP topic(s) from the conversation. Do not broaden to every SOP only because sop_index.json contains many SOPs.
            - If both the current question and prior context are ambiguous, ask a short clarification question instead of reading unrelated SOP modules.

            Required routing workflow:
            1) First call readFile("sop_index.json").
            2) Use sop_index.json and the user question to decide the SOP topic or topics to inspect.
            3) For each selected SOP topic, read its sop-xxx/index.json file to inspect the fixed module structure.
            4) Decide the module or modules needed under each selected SOP topic.
            5) Read only the selected module markdown or selected troubleshooting scenario markdown.
            6) Answer using the successful tool results.

            Fixed module structure in every generated SOP directory:
            - sop-xxx/index.json: per-SOP routing index and module map.
            - 01_duties.md: duty, ownership, handoff, on-call responsibility.
            - 02_metrics.md: monitoring metrics, thresholds, alert rules.
            - 03_troubleshooting.index.md: common fault scenario index.
            - 03_NN_*.md: concrete troubleshooting scenario files.
            - 04_escalation.md: escalation path, P0 / response-flow handling.
            - 05_forbidden.md: forbidden operations, risky actions, safety constraints.
            - 06_commands.md: tools, commands, query/check procedures.

            Module selection rules:
            - The recommended paths below are only hints. You decide the final topics/modules.
            - It is valid to read multiple SOP topics or multiple modules when the question clearly spans them.
            - Do not read a module merely because it exists. Each readFile call must map to the user's wording or a necessary next step from an index.
            - A generic fault question such as "database fault, what should I do?" usually needs the topic index plus 03_troubleshooting.index.md only. Do not add duties/escalation/forbidden/commands unless the user asks for responsibility, response flow, risk constraints, or commands.
            - If a concrete symptom matches a scenario in 03_troubleshooting.index.md, read that scenario file.
            - Prefer generated module files. Read original html only when module files are missing or insufficient.

            Conversation-derived routing context:
            %s

            Question-specific SOP routing hints (exact filenames; recommendations only):
            %s

            Rules:
            1) Start with sop_index.json, then read selected sop-xxx/index.json, then selected module files.
            2) Before final answer, read the relevant module markdown or scenario markdown needed by the question.
            3) If uncertainty exists, explicitly say what to verify instead of reading a whole SOP.
            4) Keep response concise, structured, and practical.
            5) Never claim to read a file unless readFile succeeded.
            """;

    private String buildSystemPrompt(List<DocumentEntity> candidates, String query, List<AgentMessage> history) {
        String historyContext = historyRoutingContext(query, history);
        if (candidates == null || candidates.isEmpty()) {
            return SYSTEM_PROMPT_TEMPLATE.formatted(
                    historyContext,
                    "  (no SOP candidates available - ask user to upload data first)"
            );
        }
        boolean broadMultiSop = shouldReadMultipleTroubleshootingIndexes(query, new CandidateSelection(candidates, "prompt"));
        StringBuilder listing = new StringBuilder();
        for (DocumentEntity doc : candidates) {
            SopIndex index = sopIndexCacheService.indexForPrompt(doc);
            listing.append("- ").append(index.id()).append(": ").append(index.title()).append("\n");
            listing.append("  originalFile: ").append(index.filename()).append(" (fallback only)\n");
            listing.append("  sopIndex: ").append(index.indexFile()).append("\n");
            listing.append("  recommendedNextFilesForThisQuestion: ")
                    .append(String.join(", ", localRoutingFiles(index, query, broadMultiSop)))
                    .append("\n");
            listing.append("  fixedModules: 01_duties.md, 02_metrics.md, 03_troubleshooting.index.md, 03_NN_*.md, 04_escalation.md, 05_forbidden.md, 06_commands.md\n");
            listing.append("  scenarios: read ")
                    .append(index.troubleshootingIndexFile())
                    .append(" first for generic fault questions; read a scenario file only for a matching symptom.\n");
        }
        return SYSTEM_PROMPT_TEMPLATE.formatted(historyContext, listing.toString());
    }

    private final MoonshotChatClient moonshotChatClient;
    private final SemanticSearchService semanticSearchService;
    private final KeywordSearchService keywordSearchService;
    private final DocumentRepository documentRepository;
    private final ReadFileTool readFileTool;
    private final HtmlParser htmlParser;
    private final ObjectMapper objectMapper;
    private final MoonshotProperties moonshotProperties;
    private final SopIndexCacheService sopIndexCacheService;

    public AgentService(
            MoonshotChatClient moonshotChatClient,
            SemanticSearchService semanticSearchService,
            KeywordSearchService keywordSearchService,
            DocumentRepository documentRepository,
            ReadFileTool readFileTool,
            HtmlParser htmlParser,
            ObjectMapper objectMapper,
            MoonshotProperties moonshotProperties,
            SopIndexCacheService sopIndexCacheService
    ) {
        this.moonshotChatClient = moonshotChatClient;
        this.semanticSearchService = semanticSearchService;
        this.keywordSearchService = keywordSearchService;
        this.documentRepository = documentRepository;
        this.readFileTool = readFileTool;
        this.htmlParser = htmlParser;
        this.objectMapper = objectMapper;
        this.moonshotProperties = moonshotProperties;
        this.sopIndexCacheService = sopIndexCacheService;
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
            CandidateSelection selection = resolveCandidates(query, history);
            if (selection.documents().isEmpty()) {
                throw new IllegalArgumentException("No SOP documents available. Please upload data first.");
            }
            log.info("v3 agent retrieval candidates q='{}' source={} ids={}",
                    logMessage(query), selection.source(), candidateIds(selection.documents()));
            log.info("v3 agent chat mode=api provider={} model={} q='{}'",
                    moonshotProperties.providerName(), moonshotProperties.chatModel(), logMessage(query));
            AgentChatResponse response = aiNativeChat(query, history, sink, selection.documents());
            log.info("v3 agent chat end mode=api q='{}' toolCalls={} totalTokens={} took={}ms",
                    logMessage(query), toolCallCount(response), response.totalTokens(), System.currentTimeMillis() - start);
            return response;
        } catch (ExternalServiceException ex) {
            log.warn("v3 agent fallback on chat api error query='{}' reason={}", query, ex.getMessage());
            sink.accept(new AgentTraceEvent("thinking", "Chat API 调用失败，正在切换到本地 SOP 策略..."));
            AgentChatResponse response = localFallbackChat(query, history, sink);
            log.info("v3 agent chat end mode=fallback reason='api_error: {}' q='{}' toolCalls={} totalTokens={} took={}ms",
                    ex.getMessage(), logMessage(query), toolCallCount(response), response.totalTokens(), System.currentTimeMillis() - start);
            return response;
        }
    }

    private AgentChatResponse aiNativeChat(
            String query,
            List<AgentMessage> history,
            Consumer<AgentTraceEvent> sink,
            List<DocumentEntity> candidates
    ) {
        Set<String> recommendedReadFiles = recommendedReadFiles(candidates, query);
        Set<String> suggestedFilenames = new java.util.LinkedHashSet<>(recommendedReadFiles);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt(candidates, query, history)));
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
                        "description", "Read one safe relative path from data/. It may be sop_index.json, "
                                + "an original SOP html file, or a generated sop-xxx/ module/index file. "
                                + "Recommended paths for this question, not a permission list: "
                                + String.join(", ", suggestedFilenames),
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "filename", Map.of(
                                                "type", "string",
                                                "description", "Safe relative path under data/, such as sop_index.json, sop-001/index.json, or sop-001/03_02_oom.md"
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
        boolean finalInstructionAdded = false;

        int maxRounds = Math.max(1, moonshotProperties.maxToolRounds());
        int apiToolCallLimit = Math.min(12, Math.max(API_TOOL_CALL_LIMIT, recommendedReadFiles.size()));
        for (int round = 0; round < maxRounds; round++) {
            boolean allowTools = successfulToolCallCount(toolCalls) < apiToolCallLimit && round < maxRounds - 1;
            List<Map<String, Object>> requestTools = allowTools ? tools : List.of();
            if (!allowTools && !finalInstructionAdded) {
                messages.add(Map.of(
                        "role", "user",
                        "content", finalAnswerInstruction(query, toolCalls)
                ));
                finalInstructionAdded = true;
            }
            log.info("v3 chat api request round={} messages={} tools={} provider={} model={} toolCallsUsed={}/{}",
                    round + 1, messages.size(), requestTools.size(), moonshotProperties.providerName(),
                    moonshotProperties.chatModel(), successfulToolCallCount(toolCalls), apiToolCallLimit);
            JsonNode root = moonshotChatClient.chatCompletion(messages, requestTools);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new ExternalServiceException("Chat API response has no choices");
            }
            JsonNode assistant = choices.get(0).path("message");
            totalTokens = Math.max(totalTokens, root.path("usage").path("total_tokens").asLong(totalTokens));

            JsonNode toolCallsNode = assistant.path("tool_calls");
            if (toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                if (!allowTools) {
                    log.info("v3 tool calls ignored because tool budget is exhausted count={}", toolCallsNode.size());
                    continue;
                }
                Map<String, Object> assistantWithTools = new LinkedHashMap<>();
                assistantWithTools.put("role", "assistant");
                assistantWithTools.put("content", assistant.path("content").isNull() ? "" : assistant.path("content").asText(""));
                JsonNode reasoningContent = assistant.get("reasoning_content");
                if ((reasoningContent != null && !reasoningContent.isNull()) || requiresReasoningContentInToolMessages()) {
                    assistantWithTools.put("reasoning_content", reasoningContent == null || reasoningContent.isNull()
                            ? ""
                            : reasoningContent.asText(""));
                }
                assistantWithTools.put("tool_calls", objectMapper.convertValue(toolCallsNode, new TypeReference<List<Map<String, Object>>>() { }));
                messages.add(assistantWithTools);

                for (JsonNode tc : toolCallsNode) {
                    String toolCallId = tc.path("id").asText("");
                    String functionName = tc.path("function").path("name").asText("");
                    String argumentsRaw = tc.path("function").path("arguments").asText("{}");
                    log.info("v3 tool call tool={} args={}", functionName, argumentsRaw);
                    sink.accept(new AgentTraceEvent("tool_call", "{\"tool\":\"" + functionName + "\",\"args\":" + argumentsRaw + "}"));

                    ToolExecutionResult execution;
                    if (successfulToolCallCount(toolCalls) >= apiToolCallLimit) {
                        execution = toolBudgetExhaustedResult(functionName, argumentsRaw);
                    } else {
                        execution = executeTool(functionName, argumentsRaw, query);
                    }
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

    private ToolExecutionResult executeTool(
            String functionName,
            String argumentsRaw,
            String query
    ) {
        if (!"readFile".equals(functionName)) {
            String trace = "{\"success\":false,\"error\":\"unsupported tool: " + escapeJson(functionName) + "\"}";
            AgentToolCall call = new AgentToolCall(functionName, Map.of(), false, 0, "unsupported tool");
            return new ToolExecutionResult(call, trace, trace);
        }

        String filename = parseFilename(argumentsRaw);
        try {
            String raw = readFileTool.readFile(filename);
            boolean html = filename.toLowerCase(Locale.ROOT).endsWith(".html");
            HtmlParser.ParsedHtml parsed = html
                    ? htmlParser.parse(raw)
                    : new HtmlParser.ParsedHtml(filename, raw);
            String excerpt = toolCallExcerpt(parsed.plainText(), query);
            AgentToolCall call = new AgentToolCall("readFile", Map.of("filename", filename), true, raw.length(), excerpt);
            String trace = "{\"success\":true,\"length\":" + raw.length() + ",\"title\":\"" + escapeJson(parsed.title()) + "\"}";
            return new ToolExecutionResult(call, trace, compactToolModelPayload(filename, parsed.title(), parsed.plainText(), query));
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
            String filename = node.path("filename").asText(node.path("fname").asText("")).trim();
            if (filename.isBlank()) {
                throw new IllegalArgumentException("filename is required");
            }
            return filename;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid tool arguments: " + argumentsRaw, ex);
        }
    }

    private ToolExecutionResult toolBudgetExhaustedResult(String functionName, String argumentsRaw) {
        String filename;
        try {
            filename = parseFilename(argumentsRaw);
        } catch (Exception ex) {
            filename = "";
        }
        String err = "tool budget exhausted; answer with already-read SOP evidence";
        AgentToolCall call = new AgentToolCall(
                functionName == null || functionName.isBlank() ? "readFile" : functionName,
                Map.of("filename", filename),
                false,
                0,
                err
        );
        String trace = "{\"success\":false,\"error\":\"" + escapeJson(err) + "\"}";
        return new ToolExecutionResult(call, trace, trace);
    }

    private String finalAnswerInstruction(String query, List<AgentToolCall> toolCalls) {
        String files = toolCalls.stream()
                .filter(AgentToolCall::success)
                .map(call -> String.valueOf(call.args().get("filename")))
                .distinct()
                .collect(Collectors.joining(", "));
        if (!Boolean.getBoolean("oncall.phase3.legacy-final-structure")) {
            return """
                    You have enough SOP evidence. Do not call any tools again.
                    Answer the original user question in Chinese based only on the tool results already provided.
                    Original question: %s
                    SOP files already read: %s
                    Required structure:
                    1. 先回答用户直接问的主题和模块。
                    2. 如果是故障处理问题，给出排查、止血、恢复、验证动作。
                    3. 只有当已读证据包含升级/禁止操作时，才展开升级路径或禁止项。
                    4. 如果信息不足，说明还需要确认哪些症状，而不是补充未读取模块的内容。
                    """.formatted(query, files.isBlank() ? "(none)" : files);
        }
        return """
                You have enough SOP evidence. Do not call any tools again.
                Answer the original user question in Chinese based only on the tool results already provided.
                Original question: %s
                SOP files already read: %s
                Required structure:
                1. P0 判断与启动条件
                2. 前 5 分钟响应动作
                3. 止血、恢复与验证
                4. 升级、沟通与记录
                5. 恢复后的复盘事项
                """.formatted(query, files.isBlank() ? "(none)" : files);
    }

    private Set<String> recommendedReadFiles(List<DocumentEntity> candidates, String query) {
        Set<String> files = new java.util.LinkedHashSet<>();
        files.add("sop_index.json");
        boolean broadMultiSop = shouldReadMultipleTroubleshootingIndexes(query, new CandidateSelection(candidates, "recommended"));
        for (DocumentEntity candidate : candidates) {
            SopIndex index = sopIndexCacheService.indexForPrompt(candidate);
            files.addAll(localRoutingFiles(index, query, broadMultiSop));
        }
        return files;
    }

    private AgentChatResponse localFallbackChat(String query, List<AgentMessage> history, Consumer<AgentTraceEvent> sink) {
        CandidateSelection selection = resolveCandidates(query, history);
        List<DocumentEntity> candidates = selection.documents();
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No SOP documents available. Please upload data first.");
        }
        log.info("v3 local fallback candidates q='{}' source={} ids={}",
                logMessage(query), selection.source(), candidateIds(candidates));

        List<AgentToolCall> toolCalls = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        List<String> groundedEvidence = new ArrayList<>();
        readEvidenceFile("sop_index.json", query, toolCalls, evidence, groundedEvidence, sink);

        boolean broadMultiSop = shouldReadMultipleTroubleshootingIndexes(query, selection);
        List<DocumentEntity> routedCandidates = broadMultiSop
                ? candidates.stream().limit(AGENT_CANDIDATE_LIMIT).toList()
                : candidates;
        for (DocumentEntity candidate : routedCandidates) {
            SopIndex index = sopIndexCacheService.indexForPrompt(candidate);
            for (String filename : localRoutingFiles(index, query, broadMultiSop)) {
                readEvidenceFile(filename, query, toolCalls, evidence, groundedEvidence, sink);
            }
        }
        String answer = localFallbackAnswer(evidence, groundedEvidence, broadMultiSop);
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

    private void readEvidenceFile(
            String filename,
            String query,
            List<AgentToolCall> toolCalls,
            List<String> evidence,
            List<String> groundedEvidence,
            Consumer<AgentTraceEvent> sink
    ) {
        sink.accept(new AgentTraceEvent("tool_call", "{\"tool\":\"readFile\",\"args\":{\"filename\":\"" + escapeJson(filename) + "\"}}"));
        try {
            String raw = readFileTool.readFile(filename);
            boolean html = filename.toLowerCase(Locale.ROOT).endsWith(".html");
            HtmlParser.ParsedHtml parsed = html
                    ? htmlParser.parse(raw)
                    : new HtmlParser.ParsedHtml(filename, raw);
            String plainText = parsed.plainText();
            String excerpt = toolCallExcerpt(plainText, query);
            groundedEvidence.addAll(formatGroundedEvidence(filename, plainText, query));
            toolCalls.add(new AgentToolCall("readFile", Map.of("filename", filename), true, raw.length(), excerpt));
            log.info("v3 local fallback tool result filename={} success=true length={}", filename, raw.length());
            sink.accept(new AgentTraceEvent("tool_result", "{\"success\":true,\"length\":" + raw.length() + ",\"title\":\"" + escapeJson(parsed.title()) + "\"}"));
            evidence.add(filename);
        } catch (Exception ex) {
            String err = ex.getMessage() == null ? "read failed" : ex.getMessage();
            toolCalls.add(new AgentToolCall("readFile", Map.of("filename", filename), false, 0, err));
            log.warn("v3 local fallback tool result filename={} success=false reason={}", filename, err);
            sink.accept(new AgentTraceEvent("tool_result", "{\"success\":false,\"error\":\"" + escapeJson(err) + "\"}"));
        }
    }

    private List<String> localRoutingFiles(SopIndex index, String query, boolean broadMultiSop) {
        Set<String> files = new java.util.LinkedHashSet<>();
        files.add(index.indexFile());
        if (broadMultiSop) {
            files.add(index.troubleshootingIndexFile());
            return files.stream().toList();
        }

        String normalized = normalize(query);
        boolean workflowQuery = isP0WorkflowQuery(normalized)
                || containsAny(normalized, "响应流程", "处理流程", "升级流程", "事故流程", "告警流程");
        boolean routed = false;
        if (workflowQuery) {
            files.add(index.dutyFile());
            files.add(index.escalationFile());
            files.add(index.forbiddenFile());
            files.add(index.troubleshootingIndexFile());
            return files.stream().toList();
        }
        if (containsAny(normalized, "监控", "指标", "阈值", "告警规则")) {
            files.add(index.metricsFile());
            routed = true;
        }
        if (containsAny(normalized, "禁止", "不能做", "不能操作", "不允许", "风险操作", "注意什么", "需要注意", "风险")) {
            files.add(index.forbiddenFile());
            routed = true;
        }
        if (containsAny(normalized, "工具", "命令", "怎么查", "查询", "kubectl", "grafana", "kibana", "arthas")) {
            files.add(index.commandsFile());
            routed = true;
        }

        List<SopScenario> scenarios = scoredScenarios(index, query);
        if (!scenarios.isEmpty()) {
            scenarios.stream().limit(2).map(SopScenario::file).forEach(files::add);
            routed = true;
        }
        if (!routed || containsAny(normalized, "故障", "异常", "问题", "怎么办", "怎么处理", "排查")) {
            files.add(index.troubleshootingIndexFile());
        }
        return files.stream().toList();
    }

    private List<SopScenario> scoredScenarios(SopIndex index, String query) {
        if (index == null || index.scenarios() == null || index.scenarios().isEmpty()) {
            return List.of();
        }
        List<String> terms = queryTerms(query);
        List<ScenarioScore> scored = new ArrayList<>();
        for (int i = 0; i < index.scenarios().size(); i++) {
            SopScenario scenario = index.scenarios().get(i);
            double score = scenarioScore(scenario, terms, query);
            if (score > 0) {
                scored.add(new ScenarioScore(scenario, score, i));
            }
        }
        scored.sort(Comparator.comparingDouble(ScenarioScore::score).reversed()
                .thenComparingInt(ScenarioScore::index));
        return scored.stream().map(ScenarioScore::scenario).toList();
    }

    private double scenarioScore(SopScenario scenario, List<String> terms, String query) {
        String haystack = normalize(String.join(" ",
                scenario.name() == null ? "" : scenario.name(),
                scenario.content() == null ? "" : scenario.content(),
                scenario.keywords() == null ? "" : String.join(" ", scenario.keywords())
        ));
        String normalizedQuery = normalize(query);
        double score = 0;
        if (!normalizedQuery.isBlank() && haystack.contains(normalizedQuery)) {
            score += 20;
        }
        for (String term : terms) {
            String normalizedTerm = normalize(term);
            if (!normalizedTerm.isBlank() && haystack.contains(normalizedTerm)) {
                score += Math.min(8, Math.max(2, normalizedTerm.length()));
            }
        }
        return score;
    }

    private boolean shouldReadMultipleTroubleshootingIndexes(String query, CandidateSelection selection) {
        if (selection.documents().size() <= 1) {
            return false;
        }
        return isBroadSopRoutingQuery(query);
    }

    private boolean isBroadSopRoutingQuery(String query) {
        String normalized = normalize(query);
        return isVagueQuery(query)
                || containsAny(normalized, "服务异常", "服务故障", "系统异常", "线上问题", "不知道哪里", "都有哪些");
    }

    private String localFallbackAnswer(List<String> evidence, List<String> groundedEvidence, boolean broadMultiSop) {
        String scope = broadMultiSop
                ? "问题较泛，已先读取全局索引和多个 SOP 的故障处理索引，给出通用排查路径。"
                : "已按“全局索引 -> SOP 索引 -> 相关模块”的路径读取依据。";
        String answer = """
                %s
                1. 先确认告警级别、影响范围和是否命中核心链路。
                2. 查看最近发布、配置变更、依赖状态和核心监控，先定位是否需要止血。
                3. 按命中的 SOP 模块执行恢复动作，例如回滚、降级、限流、隔离或切换备用方案。
                4. 达到升级条件时同步负责人，说明故障现象、影响范围、已采取措施和当前判断。
                5. 恢复后记录时间线、根因、修复动作和预防项。

                参考文件：%s
                """.formatted(scope, evidence.isEmpty() ? "无成功读取文件" : String.join("，", evidence));
        if (broadMultiSop) {
            answer += "\n如果要给出具体恢复命令，需要继续确认异常属于后端、基础设施、网络/CDN、数据库、移动端、前端还是 AI 算法服务。";
        }
        return answer;
    }

    private CandidateSelection resolveCandidates(String query, List<AgentMessage> history) {
        List<DocumentEntity> allDocuments = allDocumentsSorted();
        sopIndexCacheService.ensureGlobalIndex(allDocuments);
        Set<String> strongMatches = strongMatchIds(query, allDocuments);
        if (isP0WorkflowQuery(normalize(query)) && !strongMatches.isEmpty()) {
            List<DocumentEntity> workflowCandidates = strongMatches.stream()
                    .sorted()
                    .map(documentRepository::findById)
                    .filter(doc -> doc != null)
                    .toList();
            return new CandidateSelection(workflowCandidates, "p0_workflow");
        }
        if (!strongMatches.isEmpty() && !isBroadSopRoutingQuery(query)) {
            List<DocumentEntity> strongCandidates = strongMatches.stream()
                    .sorted()
                    .map(documentRepository::findById)
                    .filter(doc -> doc != null)
                    .toList();
            return new CandidateSelection(strongCandidates, "strong_match");
        }
        if (shouldUseHistoryContext(query, history, strongMatches)) {
            List<DocumentEntity> historyCandidates = historyContextCandidates(history, allDocuments);
            if (!historyCandidates.isEmpty()) {
                return new CandidateSelection(historyCandidates, "history_followup");
            }
        }

        List<SearchResult> semanticResults = semanticSearchService.search(query);
        List<SearchResult> keywordResults = keywordSearchService.search(query);
        Map<String, Double> scores = new LinkedHashMap<>();
        for (String id : strongMatches) {
            scores.merge(id, STRONG_MATCH_WEIGHT, Double::sum);
        }
        addRankedScores(scores, semanticResults, 3.0, RETRIEVAL_RESULT_LIMIT);
        addRankedScores(scores, keywordResults, 1.5, RETRIEVAL_RESULT_LIMIT);
        addSopIndexScores(scores, query, allDocuments);

        if (shouldUseAllDocuments(query, strongMatches, semanticResults, keywordResults, scores, allDocuments)) {
            return new CandidateSelection(allDocuments, "all_documents_fallback");
        }

        List<DocumentEntity> candidates = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(AGENT_CANDIDATE_LIMIT)
                .map(entry -> documentRepository.findById(entry.getKey()))
                .filter(doc -> doc != null)
                .collect(Collectors.toList());
        return new CandidateSelection(candidates, candidateSource(strongMatches, semanticResults, keywordResults));
    }

    private boolean shouldUseHistoryContext(String query, List<AgentMessage> history, Set<String> currentStrongMatches) {
        return history != null
                && !history.isEmpty()
                && (currentStrongMatches == null || currentStrongMatches.isEmpty())
                && isContextDependentFollowUp(query);
    }

    private boolean isContextDependentFollowUp(String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized,
                "那", "这个", "这种", "上述", "上面", "前面", "刚才", "刚刚", "当前问题", "该问题", "它",
                "注意什么", "需要注意", "不能操作", "不能做", "不允许", "禁止", "风险",
                "怎么升级", "升级流程", "响应流程", "处理流程", "职责", "监控指标", "看什么指标",
                "用什么命令", "什么命令", "怎么查", "如何查询");
    }

    private List<DocumentEntity> historyContextCandidates(List<AgentMessage> history, List<DocumentEntity> allDocuments) {
        Set<String> ids = historyContextIds(history, allDocuments);
        if (ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .sorted()
                .map(documentRepository::findById)
                .filter(doc -> doc != null)
                .toList();
    }

    private Set<String> historyContextIds(List<AgentMessage> history, List<DocumentEntity> allDocuments) {
        Set<String> userIds = latestHistoryIds(history, allDocuments, "user");
        if (!userIds.isEmpty()) {
            return userIds;
        }
        return latestHistoryIds(history, allDocuments, "assistant");
    }

    private Set<String> latestHistoryIds(List<AgentMessage> history, List<DocumentEntity> allDocuments, String expectedRole) {
        if (history == null || history.isEmpty()) {
            return Set.of();
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            AgentMessage message = history.get(i);
            if (message == null || message.role() == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            String role = message.role().trim().toLowerCase(Locale.ROOT);
            if (!expectedRole.equals(role)) {
                continue;
            }
            Set<String> ids = strongMatchIds(message.content(), allDocuments);
            if (!ids.isEmpty()) {
                return ids;
            }
        }
        return Set.of();
    }

    private String historyRoutingContext(String query, List<AgentMessage> history) {
        List<DocumentEntity> allDocuments = allDocumentsSorted();
        Set<String> currentStrongMatches = strongMatchIds(query, allDocuments);
        if (currentStrongMatches != null && !currentStrongMatches.isEmpty()) {
            return "  Current question has explicit SOP topic hint(s): "
                    + currentStrongMatches.stream().sorted().collect(Collectors.joining(", "))
                    + ". Route by the current question, not by older turns.";
        }
        if (shouldUseHistoryContext(query, history, currentStrongMatches)) {
            Set<String> contextIds = historyContextIds(history, allDocuments);
            if (!contextIds.isEmpty()) {
                return "  Current question looks like a follow-up and has no explicit new topic. "
                        + "Use prior SOP topic scope: "
                        + contextIds.stream().sorted().collect(Collectors.joining(", "))
                        + ". For module intent words such as forbidden/escalation/commands, read only those module files under this scope.";
            }
        }
        return "  No prior SOP topic should constrain routing. Select topic(s) from the current question and sop_index.json.";
    }

    private void addSopIndexScores(Map<String, Double> scores, String query, List<DocumentEntity> allDocuments) {
        for (DocumentEntity document : allDocuments) {
            double score = sopIndexCacheService.score(document, query);
            if (score > 0) {
                scores.merge(document.id(), 2.0 + score, Double::sum);
            }
        }
    }

    private List<DocumentEntity> allDocumentsSorted() {
        return documentRepository.findAll().stream()
                .sorted(Comparator.comparing(DocumentEntity::id))
                .toList();
    }

    private void addRankedScores(Map<String, Double> scores, List<SearchResult> results, double baseWeight, int limit) {
        int count = Math.min(limit, results.size());
        for (int i = 0; i < count; i++) {
            SearchResult result = results.get(i);
            double rankWeight = baseWeight / (i + 1.0);
            scores.merge(result.id(), rankWeight + result.score(), Double::sum);
        }
    }

    private Set<String> strongMatchIds(String query, List<DocumentEntity> allDocuments) {
        if (query == null || query.isBlank() || allDocuments.isEmpty()) {
            return Set.of();
        }
        String normalizedQuery = normalize(query);
        Set<String> ids = new java.util.LinkedHashSet<>();
        if (isP0WorkflowQuery(normalizedQuery)) {
            addIfDocumentExists(ids, allDocuments, "sop-001");
            addIfDocumentExists(ids, allDocuments, "sop-004");
        }
        for (DocumentEntity doc : allDocuments) {
            String id = doc.id();
            String filename = id + ".html";
            if (normalizedQuery.contains(normalize(id))
                    || normalizedQuery.contains(normalize(filename))
                    || normalizedQuery.contains(normalize(id.replace("-", "")))) {
                ids.add(id);
                continue;
            }

            List<String> aliases = SOP_ALIASES.getOrDefault(id, List.of());
            for (String alias : aliases) {
                String normalizedAlias = normalize(alias);
                if (!normalizedAlias.isBlank() && normalizedQuery.contains(normalizedAlias)) {
                    ids.add(id);
                    break;
                }
            }
        }
        return ids;
    }

    private boolean isP0WorkflowQuery(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return false;
        }
        return normalizedQuery.contains("p0")
                || (normalizedQuery.contains("故障")
                && (normalizedQuery.contains("流程")
                || normalizedQuery.contains("响应")
                || normalizedQuery.contains("级别")
                || normalizedQuery.contains("升级")));
    }

    private void addIfDocumentExists(Set<String> ids, List<DocumentEntity> allDocuments, String id) {
        boolean exists = allDocuments.stream().anyMatch(document -> id.equals(document.id()));
        if (exists) {
            ids.add(id);
        }
    }

    private boolean shouldUseAllDocuments(
            String query,
            Set<String> strongMatches,
            List<SearchResult> semanticResults,
            List<SearchResult> keywordResults,
            Map<String, Double> scores,
            List<DocumentEntity> allDocuments
    ) {
        if (allDocuments.isEmpty()) {
            return false;
        }
        if (!strongMatches.isEmpty()) {
            return false;
        }
        if (semanticResults.isEmpty() && keywordResults.isEmpty()) {
            return true;
        }
        if (isVagueQuery(query)) {
            return true;
        }
        return scores.size() < 2 && allDocuments.size() > scores.size();
    }

    private boolean isVagueQuery(String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return true;
        }
        return Set.of("故障", "异常", "问题", "处理", "排查", "怎么办", "怎么处理", "响应流程", "p0")
                .contains(normalized);
    }

    private String candidateSource(
            Set<String> strongMatches,
            List<SearchResult> semanticResults,
            List<SearchResult> keywordResults
    ) {
        boolean hasRetrieval = !semanticResults.isEmpty() || !keywordResults.isEmpty();
        if (!strongMatches.isEmpty() && hasRetrieval) {
            return "strong_match+retrieval";
        }
        if (!strongMatches.isEmpty()) {
            return "strong_match";
        }
        return "retrieval";
    }

    private List<String> formatGroundedEvidence(String filename, String plainText, String query) {
        List<String> lines = extractRelevantLines(plainText, query);
        List<String> formatted = new ArrayList<>();
        for (int i = 0; i < lines.size() && i < 2; i++) {
            formatted.add("- [" + filename + "] " + lines.get(i));
        }
        return formatted;
    }

    private String compactToolModelPayload(String filename, String title, String plainText, String query) {
        List<String> lines = extractRelevantLines(plainText, query);
        StringBuilder payload = new StringBuilder();
        payload.append("Filename: ").append(filename).append("\n");
        payload.append("Title: ").append(title == null || title.isBlank() ? "(unknown)" : title).append("\n");
        payload.append("Relevant SOP excerpts:\n");
        if (lines.isEmpty()) {
            payload.append("- ").append(excerpt(plainText)).append("\n");
        } else {
            for (String line : lines) {
                payload.append("- ").append(line).append("\n");
            }
        }
        payload.append("Instruction: answer using only this SOP evidence and the user question.");
        return limitText(payload.toString(), TOOL_MODEL_PAYLOAD_LIMIT);
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
        String normalizedQuery = normalize(query);
        List<String> knownTerms = List.of(
                "p0", "p1", "故障", "级别", "流程", "响应", "处理", "升级", "沟通", "复盘",
                "止血", "恢复", "回滚", "降级", "限流", "隔离", "oom", "jvm", "js",
                "数据库", "主从", "连接池", "延迟", "白屏", "攻击", "安全", "cdn", "dns",
                "app", "模型", "推荐", "搜索", "点击率", "相关性", "质量下降", "效果下降",
                "特征", "召回", "ab", "实验", "gpu", "kafka", "积压"
        );
        List<String> terms = new ArrayList<>();
        for (String term : knownTerms) {
            if (normalizedQuery.contains(normalize(term)) && !terms.contains(term)) {
                terms.add(term);
            }
        }
        String[] parts = query.toLowerCase(Locale.ROOT)
                .split("[\\s,，.。:：;；!?！？()（）\\[\\]{}<>《》\"'、/\\\\]+");
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

    private boolean requiresReasoningContentInToolMessages() {
        String model = moonshotProperties.chatModel();
        return model != null && model.toLowerCase(Locale.ROOT).startsWith("kimi-");
    }

    private String normalizeEvidenceLine(String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 180) + "...";
    }

    private String limitText(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
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

    private String toolCallExcerpt(String plainText, String query) {
        List<String> lines = extractRelevantLines(plainText, query);
        if (!lines.isEmpty()) {
            return lines.stream().limit(2).collect(Collectors.joining(" "));
        }
        return excerpt(plainText);
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

    private int successfulToolCallCount(List<AgentToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return 0;
        }
        return (int) toolCalls.stream().filter(AgentToolCall::success).count();
    }

    private String candidateIds(List<DocumentEntity> candidates) {
        return candidates.stream()
                .map(DocumentEntity::id)
                .collect(Collectors.joining(","));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").trim();
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

    private record CandidateSelection(
            List<DocumentEntity> documents,
            String source
    ) {
    }

    private record EvidenceLine(
            String text,
            double score,
            int index
    ) {
    }

    private record ScenarioScore(
            SopScenario scenario,
            double score,
            int index
    ) {
    }
}
