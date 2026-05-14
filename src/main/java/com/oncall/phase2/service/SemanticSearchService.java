package com.oncall.phase2.service;

import com.oncall.common.util.TextTokenizer;
import com.oncall.phase1.index.InvertedIndex;
import com.oncall.phase1.model.DocumentEntity;
import com.oncall.phase1.model.SearchResult;
import com.oncall.phase1.store.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SemanticSearchService {
    private static final double EXPANSION_WEIGHT = 0.4;
    private static final double CONCEPT_WEIGHT = 1.2;
    private static final double TITLE_PHRASE_BOOST = 4.0;
    private static final double BODY_PHRASE_BOOST = 1.5;

    private final TextTokenizer tokenizer;
    private final InvertedIndex invertedIndex;
    private final DocumentRepository repository;

    public SemanticSearchService(TextTokenizer tokenizer, InvertedIndex invertedIndex, DocumentRepository repository) {
        this.tokenizer = tokenizer;
        this.invertedIndex = invertedIndex;
        this.repository = repository;
    }

    public List<SearchResult> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        int totalDocs = repository.size();
        if (totalDocs == 0) {
            return List.of();
        }

        String normalizedQuery = normalize(query);
        List<String> queryTokens = tokenizer.tokenize(normalizedQuery);
        Set<String> expandedPhrases = expandSemanticPhrases(normalizedQuery);
        Map<String, Double> queryTokenWeights = buildQueryTokenWeights(queryTokens, expandedPhrases);
        Map<String, Double> scores = new HashMap<>();

        for (Map.Entry<String, Double> tokenWeight : queryTokenWeights.entrySet()) {
            String token = tokenWeight.getKey();
            double queryWeight = tokenWeight.getValue();
            Map<String, Integer> posting = invertedIndex.posting(token);
            if (posting.isEmpty()) {
                continue;
            }
            int docFreq = posting.size();
            double idf = Math.log((totalDocs + 1.0) / (docFreq + 1.0)) + 1.0;
            for (Map.Entry<String, Integer> entry : posting.entrySet()) {
                int tf = entry.getValue();
                double tfWeight = 1.0 + Math.log(tf);
                scores.merge(entry.getKey(), queryWeight * tfWeight * idf, Double::sum);
            }
        }

        Map<String, Double> conceptQueryWeights = buildConceptQueryWeights(normalizedQuery, queryTokens);
        if (!conceptQueryWeights.isEmpty()) {
            for (DocumentEntity doc : repository.findAll()) {
                double conceptBoost = scoreConceptBoost(doc, conceptQueryWeights);
                if (conceptBoost > 0) {
                    scores.merge(doc.id(), conceptBoost, Double::sum);
                }
            }
        }

        // For short intent queries (e.g. "后端"), exact phrase hit should dominate character-level overlap.
        for (DocumentEntity doc : repository.findAll()) {
            double phraseBoost = scorePhraseBoost(doc, normalizedQuery);
            if (phraseBoost > 0) {
                scores.merge(doc.id(), phraseBoost, Double::sum);
            }
        }

        if (scores.isEmpty()) {
            return List.of();
        }

        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, Double> scored : scores.entrySet()) {
            DocumentEntity doc = repository.findById(scored.getKey());
            if (doc == null) {
                continue;
            }
            String snippet = buildSnippet(doc.plainText(), normalizedQuery, queryTokens, expandedPhrases);
            results.add(new SearchResult(doc.id(), doc.title(), snippet, round(scored.getValue(), 4)));
        }

        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        double top = results.get(0).score();
        double threshold = Math.max(0.8, top * 0.35);
        List<SearchResult> filtered = results.stream()
                .filter(item -> item.score() >= threshold)
                .toList();
        return filtered.isEmpty() ? results : filtered;
    }

    private Map<String, Double> buildQueryTokenWeights(List<String> queryTokens, Set<String> expandedPhrases) {
        Map<String, Double> weights = new HashMap<>();
        for (String token : queryTokens) {
            weights.merge(token, 1.0, Double::sum);
        }
        for (String phrase : expandedPhrases) {
            for (String token : tokenizer.tokenize(phrase)) {
                weights.merge(token, EXPANSION_WEIGHT, Double::sum);
            }
        }
        return weights;
    }

    private Map<String, Double> buildConceptQueryWeights(String normalizedQuery, List<String> queryTokens) {
        Map<String, Double> conceptWeights = new HashMap<>();
        if (containsAny(normalizedQuery, queryTokens, "服务器", "服务", "挂了", "宕机", "不可用", "超时", "崩了")) {
            conceptWeights.put("service_outage", 1.0);
        }
        if (containsAny(normalizedQuery, queryTokens, "黑客", "攻击", "入侵", "安全", "ddos", "漏洞", "被黑")) {
            conceptWeights.put("security_incident", 1.0);
        }
        if (containsAny(normalizedQuery, queryTokens, "机器学习", "模型", "算法", "ai", "推荐", "推理", "gpu")) {
            conceptWeights.put("ai_model_issue", 1.0);
        }
        return conceptWeights;
    }

    private double scoreConceptBoost(DocumentEntity doc, Map<String, Double> conceptQueryWeights) {
        String text = normalize(doc.title() + " " + doc.plainText());
        double score = 0;
        for (Map.Entry<String, Double> concept : conceptQueryWeights.entrySet()) {
            double matched = 0;
            List<String> conceptTerms = conceptTerms(concept.getKey());
            for (String term : conceptTerms) {
                if (text.contains(term)) {
                    matched += 1.0;
                }
            }
            if (matched > 0) {
                score += concept.getValue() * CONCEPT_WEIGHT * matched;
            }
        }
        return score;
    }

    private List<String> conceptTerms(String concept) {
        return switch (concept) {
            case "service_outage" -> List.of(
                    "后端", "服务", "接口", "超时", "降级", "oom", "故障",
                    "sre", "k8s", "kubernetes", "集群", "告警", "容量", "节点"
            );
            case "security_incident" -> List.of(
                    "安全", "入侵", "攻击", "ddos", "漏洞", "威胁", "waf", "siem", "封禁", "应急"
            );
            case "ai_model_issue" -> List.of(
                    "ai", "算法", "模型", "推理", "推荐", "gpu", "漂移", "特征", "召回", "精排"
            );
            default -> List.of();
        };
    }

    private Set<String> expandSemanticPhrases(String normalizedQuery) {
        Set<String> expanded = new LinkedHashSet<>();
        if (containsAny(normalizedQuery, List.of(), "服务器", "服务", "挂了", "宕机", "崩了", "不可用", "后端")) {
            expanded.addAll(List.of(
                    "后端服务",
                    "服务超时",
                    "服务降级",
                    "系统故障",
                    "SRE 告警",
                    "K8s 集群"
            ));
        }
        if (containsAny(normalizedQuery, List.of(), "黑客", "攻击", "入侵", "被黑", "ddos")) {
            expanded.addAll(List.of(
                    "安全事件",
                    "入侵检测",
                    "漏洞响应",
                    "DDoS 防护",
                    "应急处置"
            ));
        }
        if (containsAny(normalizedQuery, List.of(), "机器学习", "模型", "算法", "推荐", "推理", "gpu")) {
            expanded.addAll(List.of(
                    "AI 算法",
                    "模型推理",
                    "推荐质量",
                    "GPU 集群",
                    "特征漂移"
            ));
        }
        return expanded;
    }

    private double scorePhraseBoost(DocumentEntity doc, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank() || normalizedQuery.length() < 2) {
            return 0;
        }
        String title = normalize(doc.title());
        String text = normalize(doc.plainText());
        double score = 0;
        if (title.contains(normalizedQuery)) {
            score += TITLE_PHRASE_BOOST;
        }
        if (text.contains(normalizedQuery)) {
            score += BODY_PHRASE_BOOST;
        }
        return score;
    }

    private boolean containsAny(String normalizedQuery, List<String> queryTokens, String... probes) {
        for (String probe : probes) {
            String lower = probe.toLowerCase(Locale.ROOT);
            if (normalizedQuery.contains(lower)) {
                return true;
            }
            if (!queryTokens.isEmpty() && queryTokens.contains(lower)) {
                return true;
            }
        }
        return false;
    }

    private String buildSnippet(String text, String normalizedQuery, List<String> queryTokens, Set<String> expandedPhrases) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int idx = findIndex(lower, normalizedQuery);
        if (idx < 0) {
            for (String token : queryTokens) {
                idx = findIndex(lower, token.toLowerCase(Locale.ROOT));
                if (idx >= 0) {
                    break;
                }
            }
        }
        if (idx < 0) {
            for (String phrase : expandedPhrases) {
                idx = findIndex(lower, phrase.toLowerCase(Locale.ROOT));
                if (idx >= 0) {
                    break;
                }

            }
        }
        if (idx < 0) {
            idx = 0;
        }

        int radius = 50;
        int start = Math.max(0, idx - radius);
        int end = Math.min(text.length(), idx + radius);
        String snippet = text.substring(start, end).trim();
        if (start > 0) {
            snippet = "..." + snippet;
        }
        if (end < text.length()) {
            snippet = snippet + "...";
        }
        return snippet;
    }

    private int findIndex(String text, String probe) {
        if (probe == null || probe.isBlank()) {
            return -1;
        }
        return text.indexOf(probe);
    }

    private String normalize(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT).trim();
    }

    private double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }
}
