package com.oncall.phase3.service;

import com.oncall.phase1.model.DocumentEntity;
import com.oncall.phase1.store.DocumentRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(2)
@ConditionalOnProperty(prefix = "oncall.phase3.sop-index", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SopIndexWarmupRunner implements ApplicationRunner {
    private final DocumentRepository documentRepository;
    private final SopIndexCacheService sopIndexCacheService;

    public SopIndexWarmupRunner(
            DocumentRepository documentRepository,
            SopIndexCacheService sopIndexCacheService
    ) {
        this.documentRepository = documentRepository;
        this.sopIndexCacheService = sopIndexCacheService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<DocumentEntity> documents = List.copyOf(documentRepository.findAll());
        sopIndexCacheService.warmUp(documents);
    }
}
