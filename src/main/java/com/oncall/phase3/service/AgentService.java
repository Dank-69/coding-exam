package com.oncall.phase3.service;

import com.oncall.common.util.HtmlParser;
import com.oncall.common.util.TextTokenizer;
import com.oncall.phase1.model.DocumentEntity;
import com.oncall.phase1.model.SearchResult;
import com.oncall.phase1.service.KeywordSearchService;
import com.oncall.phase1.store.DocumentRepository;
import com.oncall.phase2.service.SemanticSearchService;
import com.oncall.phase3.model.AgentChatResponse;
import com.oncall.phase3.model.AgentMessage;
import com.oncall.phase3.model.AgentToolCall;
import com.oncall.phase3.model.AgentTraceEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class AgentService {
    private final SemanticSearchService semanticSearchService;
    private final KeywordSearchService keywordSearchService;
    private final DocumentRepository documentRepository;
    private final ReadFileTool readFileTool;
    private final HtmlParser htmlParser;
    private final TextTokenizer tokenizer;

    public AgentService(
            SemanticSearchService semanticSearchService,
            KeywordSearchService keywordSearchService,
            DocumentRepository documentRepository,
            ReadFileTool readFileTool,
            HtmlParser htmlParser,
            TextTokenizer tokenizer
    ) {
        this.semanticSearchService = semanticSearchService;
        this.keywordSearchService = keywordSearchService;
        this.documentRepository = documentRepository;
        this.readFileTool = readFileTool;
        this.htmlParser = htmlParser;
        this.tokenizer = tokenizer;
    }

    public AgentChatResponse chat(String message, List<AgentMessage> history, Consumer<AgentTraceEvent> tracer) {
        String query = message == null ? "" : message.trim();
        if (query.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        Consumer<AgentTraceEvent> sink = tracer == null ? event -> { } : tracer;

        sink.accept(new AgentTraceEvent("thinking", "正在分析问题并定位相关 SOP..."));

        List<DocumentEntity> candidates = resolveCandidates(query);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No SOP documents available. Please upload data first.");
        }

        List<AgentToolCall> toolCalls = new ArrayList<>();
        List<Evidence> evidenceList = new ArrayList<>();
        for (DocumentEntity candidate : candidates) {
            String filename = candidate.id() + ".html";
            sink.accept(new AgentTraceEvent("tool_call", "{\"tool\":\"readFile\",\"args\":{\"filename\":\"" + filename + "\"}}"));
            String raw = readFileTool.readFile(filename);
            HtmlParser.ParsedHtml parsed = htmlParser.parse(raw);
            String excerpt = excerpt(parsed.plainText(), query);
            AgentToolCall toolCall = new AgentToolCall(
                    "readFile",
                    Map.of("filename", filename),
                    true,
                    raw.length(),
                    excerpt
            );
            toolCalls.add(toolCall);
            sink.accept(new AgentTraceEvent(
                    "tool_result",
                    "{\"success\":true,\"length\":" + raw.length() + ",\"title\":\"" + escapeJson(parsed.title()) + "\"}"
            ));
            evidenceList.add(new Evidence(candidate.id(), parsed.title(), parsed.plainText()));
        }

        String answer = composeAnswer(query, evidenceList, history);
        for (String chunk : splitAnswer(answer)) {
            sink.accept(new AgentTraceEvent("message", chunk));
        }
        long totalTokens = estimateTokens(query, evidenceList, answer);
        sink.accept(new AgentTraceEvent("done", "{\"totalTokens\":" + totalTokens + "}"));
        return new AgentChatResponse(answer, toolCalls, totalTokens);
    }

    private List<DocumentEntity> resolveCandidates(String query) {
        Map<String, Double> scores = new LinkedHashMap<>();
        addRankedScores(scores, semanticSearchService.search(query), 3.0);
        addRankedScores(scores, keywordSearchService.search(query), 1.5);

        if (scores.isEmpty()) {
            List<String> queryTokens = tokenizer.tokenize(query);
            documentRepository.findAll().forEach(doc -> scores.put(doc.id(), scoreDocument(doc, queryTokens)));
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

    private double scoreDocument(DocumentEntity doc, List<String> queryTokens) {
        if (doc == null || queryTokens == null || queryTokens.isEmpty()) {
            return 0;
        }
        String haystack = (doc.title() + " " + doc.plainText()).toLowerCase(Locale.ROOT);
        double score = 0;
        for (String token : queryTokens) {
            if (token.isBlank()) {
                continue;
            }
            if (haystack.contains(token.toLowerCase(Locale.ROOT))) {
                score += 1.0;
            }
        }
        return score;
    }

    private String composeAnswer(String query, List<Evidence> evidenceList, List<AgentMessage> history) {
        StringBuilder sb = new StringBuilder();
        if (evidenceList.size() == 1) {
            Evidence evidence = evidenceList.get(0);
            sb.append("我读取了 ").append(evidence.filename()).append("（").append(evidence.title()).append("）。");
            sb.append("建议按文档里的排查顺序处理：");
            appendBullets(sb, extractActionItems(evidence.plainText(), query, 4));
        } else {
            String docs = evidenceList.stream()
                    .map(e -> e.filename() + "（" + e.title() + "）")
                    .collect(Collectors.joining("、"));
            sb.append("我综合了 ").append(docs).append("。");
            sb.append("建议按下面顺序处理：");
            List<String> items = new ArrayList<>();
            for (Evidence evidence : evidenceList) {
                items.addAll(extractActionItems(evidence.plainText(), query, 2));
            }
            appendBullets(sb, dedupe(items, 5));
        }

        if (history != null && !history.isEmpty()) {
            sb.append("\n\n（已结合历史对话上下文）");
        }
        return sb.toString().trim();
    }

    private List<String> extractActionItems(String text, String query, int maxItems) {
        List<String> queryTokens = tokenizer.tokenize(query);
        List<String> sentences = splitSentences(text);
        List<ScoredSentence> scored = new ArrayList<>();
        for (String sentence : sentences) {
            double score = scoreSentence(sentence, queryTokens);
            if (score > 0) {
                scored.add(new ScoredSentence(sentence, score));
            }
        }
        if (scored.isEmpty()) {
            for (String sentence : sentences) {
                if (sentence.length() > 8) {
                    scored.add(new ScoredSentence(sentence, 0.1));
                }
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredSentence::score).reversed());
        return scored.stream()
                .map(ScoredSentence::sentence)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(this::cleanSentence)
                .limit(maxItems)
                .collect(Collectors.toList());
    }

    private double scoreSentence(String sentence, List<String> queryTokens) {
        String lower = sentence.toLowerCase(Locale.ROOT);
        double score = 0;
        for (String token : queryTokens) {
            if (token.isBlank()) {
                continue;
            }
            if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                score += 2.0;
            }
        }
        if (containsAny(lower, "检查", "确认", "排查", "处理", "降级", "回滚", "重启", "恢复", "告警", "升级", "隔离", "封禁", "限流")) {
            score += 1.5;
        }
        if (sentence.matches(".*[一二三四五六七八九十0-9][、\\.|\\)]?.*")) {
            score += 1.0;
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

    private List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replace("\r", "\n");
        String[] parts = normalized.split("[\\n。！？；;]+");
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }

    private List<String> dedupe(List<String> items, int maxItems) {
        return items.stream()
                .map(this::cleanSentence)
                .distinct()
                .limit(maxItems)
                .collect(Collectors.toList());
    }

    private String cleanSentence(String sentence) {
        String result = sentence.replaceAll("\\s+", " ").trim();
        if (result.length() > 160) {
            result = result.substring(0, 160).trim() + "...";
        }
        return result;
    }

    private void appendBullets(StringBuilder sb, List<String> items) {
        if (items.isEmpty()) {
            sb.append(" 先确认告警、日志和影响范围，再根据 SOP 执行降级或恢复动作。");
            return;
        }
        sb.append("\n");
        for (int i = 0; i < items.size(); i++) {
            sb.append(i + 1).append(". ").append(items.get(i));
            if (i < items.size() - 1) {
                sb.append("\n");
            }
        }
    }

    private String excerpt(String text, String query) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> queryTokens = tokenizer.tokenize(query);
        String lower = text.toLowerCase(Locale.ROOT);
        int idx = -1;
        for (String token : queryTokens) {
            idx = lower.indexOf(token.toLowerCase(Locale.ROOT));
            if (idx >= 0) {
                break;
            }
        }
        if (idx < 0) {
            idx = 0;
        }
        int start = Math.max(0, idx - 40);
        int end = Math.min(text.length(), idx + 120);
        String value = text.substring(start, end).trim();
        if (start > 0) {
            value = "..." + value;
        }
        if (end < text.length()) {
            value = value + "...";
        }
        return value;
    }

    private long estimateTokens(String query, List<Evidence> evidenceList, String answer) {
        int chars = query == null ? 0 : query.length();
        for (Evidence evidence : evidenceList) {
            chars += evidence.plainText().length();
        }
        chars += answer.length();
        return Math.max(1L, Math.round(chars / 4.0));
    }

    private List<String> splitAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        for (String line : answer.split("\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                chunks.add(trimmed);
            }
        }
        return chunks;
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private record Evidence(String filename, String title, String plainText) {
    }

    private record ScoredSentence(String sentence, double score) {
    }
}
