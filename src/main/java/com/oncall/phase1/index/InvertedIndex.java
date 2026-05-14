package com.oncall.phase1.index;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InvertedIndex {
    private final Map<String, Map<String, Integer>> termToDocTf = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> docToTerms = new ConcurrentHashMap<>();

    public synchronized void upsertDocument(String documentId, Iterable<String> tokens) {
        removeDocument(documentId);

        Set<String> termsInDoc = ConcurrentHashMap.newKeySet();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalized = token.trim().toLowerCase();
            termToDocTf
                    .computeIfAbsent(normalized, t -> new ConcurrentHashMap<>())
                    .merge(documentId, 1, Integer::sum);
            termsInDoc.add(normalized);
        }
        docToTerms.put(documentId, termsInDoc);
    }

    public synchronized void removeDocument(String documentId) {
        Set<String> existingTerms = docToTerms.getOrDefault(documentId, Collections.emptySet());
        for (String term : existingTerms) {
            Map<String, Integer> posting = termToDocTf.get(term);
            if (posting == null) {
                continue;
            }
            posting.remove(documentId);
            if (posting.isEmpty()) {
                termToDocTf.remove(term);
            }
        }
        docToTerms.remove(documentId);
    }

    public Map<String, Integer> posting(String term) {
        return termToDocTf.getOrDefault(term, Collections.emptyMap());
    }
}
