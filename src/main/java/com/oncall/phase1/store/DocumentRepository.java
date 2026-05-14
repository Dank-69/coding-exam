package com.oncall.phase1.store;

import com.oncall.phase1.model.DocumentEntity;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DocumentRepository {
    private final Map<String, DocumentEntity> documents = new ConcurrentHashMap<>();

    public void save(DocumentEntity document) {
        documents.put(document.id(), document);
    }

    public DocumentEntity findById(String id) {
        return documents.get(id);
    }

    public Collection<DocumentEntity> findAll() {
        return documents.values();
    }

    public int size() {
        return documents.size();
    }
}
