package com.oncall.phase1.service;

import com.oncall.common.util.TextTokenizer;
import com.oncall.phase1.model.DocumentEntity;
import com.oncall.phase1.model.SearchResult;
import com.oncall.phase1.index.InvertedIndex;
import com.oncall.phase1.store.DocumentRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KeywordSearchService {
    private final TextTokenizer tokenizer;
    private final InvertedIndex invertedIndex;
    private final DocumentRepository repository;

    public KeywordSearchService(TextTokenizer tokenizer, InvertedIndex invertedIndex, DocumentRepository repository) {
        this.tokenizer = tokenizer;
        this.invertedIndex = invertedIndex;
        this.repository = repository;
    }

    public List<SearchResult> search(String query) {
        List<String> tokens = tokenizer.tokenize(query);
        int totalDocs = repository.size();
        if (totalDocs == 0) {
            return List.of();
        }

        Map<String, Double> scores = new HashMap<>();
        for (String token : tokens) {
            Map<String, Integer> posting = invertedIndex.posting(token);
            if (posting.isEmpty()) {
                continue;
            }
            int docFreq = posting.size();
            double idf = Math.log((totalDocs + 1.0) / (docFreq + 1.0)) + 1.0;
            for (Map.Entry<String, Integer> entry : posting.entrySet()) {
                int tf = entry.getValue();
                double tfWeight = 1.0 + Math.log(tf);
                scores.merge(entry.getKey(), tfWeight * idf, Double::sum);
            }
        }

        if (scores.isEmpty()) {
            return List.of();
        }

        List<SearchResult> results = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            DocumentEntity doc = repository.findById(entry.getKey());
            if (doc == null) {
                continue;
            }
            String snippet = buildSnippet(doc.plainText(), query, tokens);
            results.add(new SearchResult(doc.id(), doc.title(), snippet, round(entry.getValue(), 4)));
        }
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return results;
    }

    private String buildSnippet(String text, String query, List<String> tokens) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String probe = query == null ? "" : query.trim().toLowerCase();
        String lower = text.toLowerCase();

        int idx = -1;
        if (!probe.isEmpty()) {
            idx = lower.indexOf(probe);
        }
        if (idx < 0) {
            for (String token : tokens) {
                idx = lower.indexOf(token.toLowerCase());
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

    private double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }
}
