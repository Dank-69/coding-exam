package com.oncall.phase2.service;

import com.oncall.common.exception.ExternalServiceException;
import com.oncall.common.util.TextTokenizer;
import com.oncall.phase1.model.DocumentEntity;
import com.oncall.phase1.model.SearchResult;
import com.oncall.phase1.store.DocumentRepository;
import com.oncall.phase2.client.MoonshotEmbeddingClient;
import com.oncall.phase2.store.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Service
public class SemanticSearchService {
    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);
    private static final int CHUNK_SIZE = 800;
    private static final int CHUNK_OVERLAP = 120;
    private static final double BM25_K1 = 1.2;
    private static final double BM25_B = 0.75;
    private static final double TITLE_TERM_WEIGHT = 2.2;
    private static final double ORIGINAL_TERM_WEIGHT = 3.0;
    private static final double EXPANDED_TERM_WEIGHT = 1.0;
    private static final double TITLE_PHRASE_BOOST = 6.0;
    private static final double BODY_PHRASE_BOOST = 2.0;
    private static final int MAX_RESULTS = 5;
    private static final double VECTOR_WEIGHT = 0.55;
    private static final double TEXT_SIGNAL_WEIGHT = 0.45;
    private static final double MIN_RELATIVE_SCORE = 0.30;
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "吗", "呢", "啊", "和", "与", "及", "或", "一个", "一下",
            "怎么", "如何", "什么", "怎么办", "处理", "排查", "问题", "出现", "发生"
    );

    private final MoonshotEmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final DocumentRepository repository;
    private final TextTokenizer tokenizer;

    public SemanticSearchService(
            MoonshotEmbeddingClient embeddingClient,
            VectorStore vectorStore,
            DocumentRepository repository,
            TextTokenizer tokenizer
    ) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.repository = repository;
        this.tokenizer = tokenizer;
    }

    public List<SearchResult> search(String query) {
        long start = System.currentTimeMillis();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (repository.size() == 0) {
            return List.of();
        }
        log.info("v2 semantic search start q='{}' docs={} embeddingEnabled={}",
                logQuery(query), repository.size(), embeddingClient.isEnabled());

        if (!embeddingClient.isEnabled()) {
            log.warn("v2 semantic search mode=fallback reason='{}' q='{}'",
                    embeddingClient.disabledReason(), logQuery(query));
            return fallbackSearch(query);
        }

        try {
            log.info("v2 semantic search mode=api-hybrid q='{}' topK={}", logQuery(query), MAX_RESULTS);
            float[] queryVector = embeddingClient.embedSingle(query.trim());
            if (!isValidVector(queryVector)) {
                log.warn("v2 semantic search mode=fallback reason='invalid_query_vector' q='{}'", logQuery(query));
                return fallbackSearch(query);
            }

            QueryPlan queryPlan = buildQueryPlan(normalize(query));
            List<DocumentProfile> profiles = repository.findAll().stream()
                    .map(this::profileOf)
                    .toList();
            Map<String, Double> textScores = scoreProfiles(queryPlan, profiles);
            List<VectorCandidate> candidates = new ArrayList<>();
            for (DocumentEntity doc : repository.findAll()) {
                List<float[]> docVectors = getOrBuildDocVectors(doc);
                double vectorScore = maxCosineSimilarity(queryVector, docVectors, doc.id());
                double textScore = textScores.getOrDefault(doc.id(), 0.0);
                if (vectorScore <= 0 && textScore <= 0) {
                    continue;
                }
                candidates.add(new VectorCandidate(doc, vectorScore, textScore));
            }
            List<SearchResult> results = rankApiCandidates(candidates, query, queryPlan.snippetProbes());
            if (results.isEmpty()) {
                log.warn("v2 semantic search mode=fallback reason='empty_result' q='{}'", logQuery(query));
                return fallbackSearch(query);
            }
            log.info("v2 semantic search ok query='{}' mode=api-hybrid results={} topIds={} took={}ms",
                    logQuery(query), results.size(), resultIds(results), System.currentTimeMillis() - start);
            return results;
        } catch (ExternalServiceException ex) {
            log.warn("v2 semantic search mode=fallback reason='embedding_error: {}' q='{}'",
                    ex.getMessage(), logQuery(query));
            return fallbackSearch(query);
        }
    }

    public void warmUpDocumentVectorAsync(DocumentEntity doc) {
        if (doc == null) {
            return;
        }
        if (!embeddingClient.isEnabled()) {
            log.debug("skip vector warm-up id='{}': {}", doc.id(), embeddingClient.disabledReason());
            return;
        }
        CompletableFuture.runAsync(() -> warmUpDocumentVector(doc));
    }

    public boolean warmUpDocumentVector(DocumentEntity doc) {
        if (doc == null) {
            return false;
        }
        if (!embeddingClient.isEnabled()) {
            log.debug("skip vector warm-up id='{}': {}", doc.id(), embeddingClient.disabledReason());
            return false;
        }
        long start = System.currentTimeMillis();
        try {
            getOrBuildDocVectors(doc);
            log.info("v2 vector warm-up ok id='{}' took={}ms", doc.id(), System.currentTimeMillis() - start);
            return true;
        } catch (Exception ex) {
            log.warn("v2 vector warm-up failed id='{}' reason={}", doc.id(), ex.getMessage());
            return false;
        }
    }

    public boolean canWarmUpVectors() {
        return embeddingClient.isEnabled();
    }

    public String vectorWarmUpDisabledReason() {
        return embeddingClient.disabledReason();
    }

    private List<SearchResult> fallbackSearch(String query) {
        log.info("v2 semantic search local fallback q='{}'", logQuery(query));
        String normalizedQuery = normalize(query);
        QueryPlan queryPlan = buildQueryPlan(normalizedQuery);
        if (queryPlan.termWeights().isEmpty() && queryPlan.phrases().isEmpty()) {
            return List.of();
        }

        List<DocumentProfile> profiles = repository.findAll().stream()
                .map(this::profileOf)
                .toList();
        Map<String, Double> localScores = scoreProfiles(queryPlan, profiles);

        List<SearchResult> results = new ArrayList<>();
        for (DocumentProfile profile : profiles) {
            double score = localScores.getOrDefault(profile.doc().id(), 0.0);
            if (score <= 0) {
                continue;
            }
            results.add(new SearchResult(
                    profile.doc().id(),
                    profile.doc().title(),
                    buildSnippet(profile.doc().plainText(), query, queryPlan.snippetProbes()),
                    round(score, 6)
            ));
        }
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        List<SearchResult> limited = limitResults(results);
        log.info("v2 semantic search local fallback q='{}' results={} topIds={}",
                logQuery(query), limited.size(), resultIds(limited));
        return limited;
    }

    private QueryPlan buildQueryPlan(String normalizedQuery) {
        Map<String, Double> termWeights = new LinkedHashMap<>();
        Set<String> phrases = new LinkedHashSet<>();
        Set<String> originalTokens = meaningfulTokens(normalizedQuery);

        for (String token : originalTokens) {
            addWeightedTerm(termWeights, token, ORIGINAL_TERM_WEIGHT);
        }
        if (!normalizedQuery.isBlank() && normalizedQuery.length() >= 2) {
            phrases.add(normalizedQuery);
        }

        for (Concept concept : detectConcepts(normalizedQuery, originalTokens)) {
            for (String term : concept.terms()) {
                addWeightedTerm(termWeights, term, EXPANDED_TERM_WEIGHT);
            }
            phrases.addAll(concept.phrases());
        }

        Set<String> snippetProbes = new LinkedHashSet<>();
        snippetProbes.addAll(phrases);
        snippetProbes.addAll(termWeights.keySet());
        return new QueryPlan(termWeights, phrases, snippetProbes);
    }

    private Set<Concept> detectConcepts(String normalizedQuery, Set<String> tokens) {
        Set<Concept> concepts = new LinkedHashSet<>();
        if (matchesAny(normalizedQuery, tokens, "服务器", "服务", "挂", "挂了", "宕机", "崩", "不可用", "超时", "故障", "p0", "oom", "outofmemory", "内存")) {
            concepts.add(new Concept(
                    List.of("后端", "服务", "超时", "降级", "熔断", "告警", "可用性", "oom", "outofmemoryerror", "java", "jvm", "堆", "内存", "kubernetes", "k8s", "pod", "集群", "基础设施", "ingress", "网关", "节点"),
                    List.of("后端服务", "服务大面积超时", "单服务oom", "outofmemoryerror", "堆转储", "基础设施故障", "kubernetes集群", "ingress网关", "核心服务", "故障响应")
            ));
        }
        if (matchesAny(normalizedQuery, tokens, "黑客", "攻击", "入侵", "被黑", "安全", "ddos", "漏洞", "恶意", "泄露")) {
            concepts.add(new Concept(
                    List.of("安全", "入侵", "攻击", "ddos", "漏洞", "waf", "ids", "siem", "封禁", "隔离", "威胁", "应急", "恶意软件", "数据泄露"),
                    List.of("信息安全", "安全事件", "入侵检测", "漏洞响应", "ddos攻击", "应急响应", "恶意软件")
            ));
        }
        if (matchesAny(normalizedQuery, tokens, "机器学习", "模型", "算法", "ai", "推荐", "推理", "gpu", "特征", "质量")) {
            concepts.add(new Concept(
                    List.of("ai", "算法", "模型", "推理", "推荐", "gpu", "特征", "漂移", "召回", "精排", "点击率", "相关性", "ab", "tensorflow", "pytorch"),
                    List.of("ai算法", "模型推理", "模型效果", "推荐系统", "推荐质量", "gpu集群", "特征服务", "效果下降")
            ));
        }
        if (matchesAny(normalizedQuery, tokens, "数据库", "主从", "复制", "延迟", "慢查询", "连接池", "恢复", "mysql", "redis")) {
            concepts.add(new Concept(
                    List.of("数据库", "dba", "主从", "复制", "延迟", "慢查询", "连接池", "binlog", "gtid", "备份", "恢复", "mysql", "redis"),
                    List.of("数据库dba", "主从复制", "主从延迟", "数据库连接", "慢查询", "数据恢复")
            ));
        }
        if (matchesAny(normalizedQuery, tokens, "cdn", "dns", "网络", "带宽", "负载均衡", "lb")) {
            concepts.add(new Concept(
                    List.of("网络", "cdn", "dns", "负载均衡", "lb", "带宽", "节点", "链路", "路由", "nginx", "haproxy"),
                    List.of("网络与cdn", "cdn节点", "dns解析", "负载均衡", "网络故障")
            ));
        }
        return concepts;
    }

    private DocumentProfile profileOf(DocumentEntity doc) {
        Map<String, Integer> titleTerms = termFrequency(tokenizer.tokenize(doc.title()));
        Map<String, Integer> bodyTerms = termFrequency(tokenizer.tokenize(doc.plainText()));
        int titleLength = titleTerms.values().stream().mapToInt(Integer::intValue).sum();
        int bodyLength = bodyTerms.values().stream().mapToInt(Integer::intValue).sum();
        int weightedLength = Math.max(1, bodyLength + (int) Math.round(titleLength * TITLE_TERM_WEIGHT));
        return new DocumentProfile(
                doc,
                titleTerms,
                bodyTerms,
                weightedLength,
                normalize(doc.title()),
                normalize(doc.plainText())
        );
    }

    private Map<String, Integer> documentFrequencies(List<DocumentProfile> profiles, Set<String> terms) {
        Map<String, Integer> frequencies = new HashMap<>();
        for (String term : terms) {
            int count = 0;
            for (DocumentProfile profile : profiles) {
                if (profile.hasTerm(term)) {
                    count++;
                }
            }
            frequencies.put(term, count);
        }
        return frequencies;
    }

    private Map<String, Double> scoreProfiles(QueryPlan queryPlan, List<DocumentProfile> profiles) {
        if (queryPlan.termWeights().isEmpty() && queryPlan.phrases().isEmpty()) {
            return Map.of();
        }
        double avgLength = profiles.stream()
                .mapToDouble(DocumentProfile::length)
                .average()
                .orElse(1.0);
        Map<String, Integer> docFreq = documentFrequencies(profiles, queryPlan.termWeights().keySet());
        int totalDocs = Math.max(1, profiles.size());
        Map<String, Double> scores = new HashMap<>();
        for (DocumentProfile profile : profiles) {
            double score = scoreProfile(profile, queryPlan, docFreq, totalDocs, avgLength);
            if (score > 0) {
                scores.put(profile.doc().id(), score);
            }
        }
        return scores;
    }

    private double scoreProfile(
            DocumentProfile profile,
            QueryPlan queryPlan,
            Map<String, Integer> docFreq,
            int totalDocs,
            double avgLength
    ) {
        double score = 0;
        for (Map.Entry<String, Double> entry : queryPlan.termWeights().entrySet()) {
            String term = entry.getKey();
            double tf = profile.weightedTermFrequency(term);
            if (tf <= 0) {
                continue;
            }
            int df = docFreq.getOrDefault(term, 0);
            if (df == 0) {
                continue;
            }
            double idf = Math.log(1.0 + (totalDocs - df + 0.5) / (df + 0.5));
            double denominator = tf + BM25_K1 * (1.0 - BM25_B + BM25_B * profile.length() / avgLength);
            score += entry.getValue() * idf * (tf * (BM25_K1 + 1.0) / denominator);
        }

        for (String phrase : queryPlan.phrases()) {
            if (phrase.isBlank()) {
                continue;
            }
            if (profile.normalizedTitle().contains(phrase)) {
                score += TITLE_PHRASE_BOOST;
            }
            if (profile.normalizedText().contains(phrase)) {
                score += BODY_PHRASE_BOOST;
            }
        }
        return score;
    }

    private Map<String, Integer> termFrequency(List<String> terms) {
        Map<String, Integer> counts = new HashMap<>();
        for (String term : terms) {
            String normalized = normalize(term);
            if (isMeaningfulTerm(normalized)) {
                counts.merge(normalized, 1, Integer::sum);
            }
        }
        return counts;
    }

    private Set<String> meaningfulTokens(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : tokenizer.tokenize(text)) {
            String normalized = normalize(token);
            if (isMeaningfulTerm(normalized)) {
                terms.add(normalized);
            }
        }
        return terms;
    }

    private boolean isMeaningfulTerm(String term) {
        return term != null && !term.isBlank() && !STOP_WORDS.contains(term);
    }

    private void addWeightedTerm(Map<String, Double> weights, String rawTerm, double weight) {
        for (String token : meaningfulTokens(rawTerm)) {
            weights.merge(token, weight, Double::sum);
        }
    }

    private boolean matchesAny(String normalizedQuery, Set<String> tokens, String... probes) {
        for (String probe : probes) {
            String normalizedProbe = normalize(probe);
            if (normalizedQuery.contains(normalizedProbe) || tokens.contains(normalizedProbe)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").trim();
    }

    private String logQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    private List<float[]> getOrBuildDocVectors(DocumentEntity doc) {
        String signature = signatureOf(doc);
        VectorStore.VectorEntry cached = vectorStore.get(doc.id());
        if (cached != null && signature.equals(cached.signature())) {
            return cached.vectors();
        }

        List<String> chunks = splitToChunks(doc.plainText());
        List<float[]> chunkVectors = embeddingClient.embedBatch(chunks);
        validateChunkVectors(chunkVectors);
        vectorStore.put(doc.id(), new VectorStore.VectorEntry(List.copyOf(chunkVectors), signature));
        return chunkVectors;
    }

    private List<String> splitToChunks(String text) {
        if (text == null || text.isBlank()) {
            return List.of("");
        }
        List<String> chunks = new ArrayList<>();
        int n = text.length();
        int step = Math.max(1, CHUNK_SIZE - CHUNK_OVERLAP);
        for (int start = 0; start < n; start += step) {
            int end = Math.min(n, start + CHUNK_SIZE);
            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
        }
        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    private void validateChunkVectors(List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            throw new ExternalServiceException("Embedding API returned empty vectors");
        }
        int dim = vectors.get(0).length;
        if (dim == 0) {
            throw new ExternalServiceException("Embedding API returned zero-dimensional vector");
        }
        for (float[] vector : vectors) {
            if (!sameDimension(vectors.get(0), vector)) {
                throw new ExternalServiceException("Embedding API returned vectors with inconsistent dimensions");
            }
        }
    }

    private double maxCosineSimilarity(float[] queryVector, List<float[]> docVectors, String docId) {
        double best = 0;
        if (docVectors == null || docVectors.isEmpty()) {
            return best;
        }
        for (float[] docVector : docVectors) {
            if (!sameDimension(queryVector, docVector)) {
                log.warn("skip vector chunk id='{}' reason='dimension_mismatch' queryDim={} docDim={}",
                        docId, queryVector.length, docVector == null ? 0 : docVector.length);
                continue;
            }
            double score = cosineSimilarity(queryVector, docVector);
            if (!Double.isNaN(score)) {
                best = Math.max(best, score);
            }
        }
        return best;
    }

    private List<SearchResult> rankApiCandidates(List<VectorCandidate> candidates, String query, Set<String> snippetProbes) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        double minVector = candidates.stream().mapToDouble(VectorCandidate::vectorScore).min().orElse(0);
        double maxVector = candidates.stream().mapToDouble(VectorCandidate::vectorScore).max().orElse(0);
        double maxText = candidates.stream().mapToDouble(VectorCandidate::textScore).max().orElse(0);

        List<SearchResult> results = new ArrayList<>();
        for (VectorCandidate candidate : candidates) {
            double vectorComponent = normalizeRange(candidate.vectorScore(), minVector, maxVector);
            double textComponent = maxText > 0 ? candidate.textScore() / maxText : 0;
            double score = VECTOR_WEIGHT * vectorComponent + TEXT_SIGNAL_WEIGHT * textComponent;
            results.add(new SearchResult(
                    candidate.doc().id(),
                    candidate.doc().title(),
                    buildSnippet(candidate.doc().plainText(), query, snippetProbes),
                    round(score, 6)
            ));
        }
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return limitResults(results);
    }

    private double normalizeRange(double value, double min, double max) {
        if (max <= min) {
            return value > 0 ? 1.0 : 0.0;
        }
        return Math.max(0, Math.min(1, (value - min) / (max - min)));
    }

    private List<SearchResult> limitResults(List<SearchResult> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        double best = results.get(0).score();
        return results.stream()
                .filter(result -> result.score() >= best * MIN_RELATIVE_SCORE)
                .limit(MAX_RESULTS)
                .toList();
    }

    private String resultIds(List<SearchResult> results) {
        return results.stream()
                .map(SearchResult::id)
                .toList()
                .toString();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (!sameDimension(a, b)) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private boolean sameDimension(float[] a, float[] b) {
        return isValidVector(a) && isValidVector(b) && a.length == b.length;
    }

    private boolean isValidVector(float[] vector) {
        return vector != null && vector.length > 0;
    }

    private String buildSnippet(String text, String query) {
        return buildSnippet(text, query, meaningfulTokens(query));
    }

    private String buildSnippet(String text, String query, Set<String> probes) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf(query.toLowerCase(Locale.ROOT));
        if (idx < 0) {
            for (String probe : probes) {
                idx = lower.indexOf(probe.toLowerCase(Locale.ROOT));
                if (idx >= 0) {
                    break;
                }
            }
        }
        if (idx < 0) {
            idx = 0;
        }
        int start = Math.max(0, idx - 50);
        int end = Math.min(text.length(), idx + 120);
        String snippet = text.substring(start, end).trim();
        if (start > 0) {
            snippet = "..." + snippet;
        }
        if (end < text.length()) {
            snippet = snippet + "...";
        }
        return snippet;
    }

    private String signatureOf(DocumentEntity doc) {
        String plain = doc.plainText() == null ? "" : doc.plainText();
        return doc.createdAt() + ":" + plain.length() + ":" + plain.hashCode();
    }

    private double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    private record QueryPlan(
            Map<String, Double> termWeights,
            Set<String> phrases,
            Set<String> snippetProbes
    ) {
    }

    private record Concept(
            List<String> terms,
            List<String> phrases
    ) {
    }

    private record VectorCandidate(
            DocumentEntity doc,
            double vectorScore,
            double textScore
    ) {
    }

    private record DocumentProfile(
            DocumentEntity doc,
            Map<String, Integer> titleTerms,
            Map<String, Integer> bodyTerms,
            int length,
            String normalizedTitle,
            String normalizedText
    ) {
        boolean hasTerm(String term) {
            return titleTerms.containsKey(term) || bodyTerms.containsKey(term);
        }

        double weightedTermFrequency(String term) {
            return bodyTerms.getOrDefault(term, 0) + titleTerms.getOrDefault(term, 0) * TITLE_TERM_WEIGHT;
        }
    }
}
