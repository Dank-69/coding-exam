package com.oncall.phase2.bootstrap;

import com.oncall.phase1.model.DocumentEntity;
import com.oncall.phase1.store.DocumentRepository;
import com.oncall.phase2.service.SemanticSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(1)
@ConditionalOnProperty(prefix = "oncall.phase2.vector-warmup", name = "enabled", havingValue = "true")
public class VectorWarmupRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(VectorWarmupRunner.class);

    private final DocumentRepository documentRepository;
    private final SemanticSearchService semanticSearchService;

    public VectorWarmupRunner(DocumentRepository documentRepository, SemanticSearchService semanticSearchService) {
        this.documentRepository = documentRepository;
        this.semanticSearchService = semanticSearchService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<DocumentEntity> documents = List.copyOf(documentRepository.findAll());
        if (documents.isEmpty()) {
            log.info("v2 vector warm-up skipped: no documents loaded");
            return;
        }
        if (!semanticSearchService.canWarmUpVectors()) {
            log.info("v2 vector warm-up skipped: {}", semanticSearchService.vectorWarmUpDisabledReason());
            return;
        }

        log.info("v2 vector warm-up begin docs={} mode=startup-sequential", documents.size());
        long start = System.currentTimeMillis();
        int success = 0;
        int skippedOrFailed = 0;
        for (DocumentEntity document : documents) {
            if (semanticSearchService.warmUpDocumentVector(document)) {
                success++;
            } else {
                skippedOrFailed++;
            }
        }
        log.info("v2 vector warm-up done success={} skippedOrFailed={} took={}ms",
                success, skippedOrFailed, System.currentTimeMillis() - start);
    }
}
