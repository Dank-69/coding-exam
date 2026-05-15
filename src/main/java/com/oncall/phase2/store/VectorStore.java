package com.oncall.phase2.store;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class VectorStore {
    private final Map<String, VectorEntry> vectors = new ConcurrentHashMap<>();

    public VectorEntry get(String id) {
        return vectors.get(id);
    }

    public void put(String id, VectorEntry entry) {
        vectors.put(id, entry);
    }

    public record VectorEntry(
            List<float[]> vectors,
            String signature
    ) {
    }
}
