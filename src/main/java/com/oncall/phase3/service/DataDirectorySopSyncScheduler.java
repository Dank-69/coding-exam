package com.oncall.phase3.service;

import com.oncall.phase1.model.DocumentEntity;
import com.oncall.phase1.service.DocumentService;
import com.oncall.phase1.store.DocumentRepository;
import com.oncall.phase2.service.SemanticSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Component
@ConditionalOnProperty(prefix = "oncall.bootstrap", name = {"enabled", "sync-enabled"}, havingValue = "true", matchIfMissing = true)
public class DataDirectorySopSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(DataDirectorySopSyncScheduler.class);

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final SopIndexCacheService sopIndexCacheService;
    private final SemanticSearchService semanticSearchService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${oncall.bootstrap.data-dir:data}")
    private String dataDir;

    public DataDirectorySopSyncScheduler(
            DocumentService documentService,
            DocumentRepository documentRepository,
            SopIndexCacheService sopIndexCacheService,
            SemanticSearchService semanticSearchService
    ) {
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.sopIndexCacheService = sopIndexCacheService;
        this.semanticSearchService = semanticSearchService;
    }

    @Scheduled(
            initialDelayString = "${oncall.bootstrap.sync-initial-delay-ms:30000}",
            fixedDelayString = "${oncall.bootstrap.sync-fixed-delay-ms:30000}"
    )
    public void syncDataDirectory() {
        if (!running.compareAndSet(false, true)) {
            log.debug("data directory SOP sync skipped: previous run still active");
            return;
        }
        try {
            SyncResult result = syncOnce();
            if (result.changed() > 0 || result.failed() > 0) {
                log.info("data directory SOP sync done scanned={} changed={} failed={}",
                        result.scanned(), result.changed(), result.failed());
            }
        } finally {
            running.set(false);
        }
    }

    SyncResult syncOnce() {
        Path directory = Path.of(dataDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            log.debug("data directory SOP sync skipped: directory not found path='{}'", directory);
            return new SyncResult(0, 0, 0);
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isHtmlFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            log.warn("data directory SOP sync failed to scan path='{}' reason={}", directory, ex.getMessage());
            return new SyncResult(0, 0, 1);
        }

        int changed = 0;
        int failed = 0;
        List<DocumentEntity> changedDocuments = new ArrayList<>();
        for (Path file : files) {
            try {
                String html = Files.readString(file, StandardCharsets.UTF_8);
                String id = toDocumentId(file);
                DocumentEntity existing = documentRepository.findById(id);
                if (existing != null && sameContent(existing.html(), html)) {
                    continue;
                }
                DocumentEntity document = documentService.upsert(id, html);
                sopIndexCacheService.indexForPrompt(document);
                semanticSearchService.warmUpDocumentVectorAsync(document);
                changedDocuments.add(document);
                changed++;
                log.info("data directory SOP sync imported file='{}' id='{}' title='{}'",
                        file.getFileName(), document.id(), document.title());
            } catch (Exception ex) {
                failed++;
                log.warn("data directory SOP sync failed file='{}' reason={}",
                        file.getFileName(), ex.getMessage());
            }
        }

        if (!changedDocuments.isEmpty()) {
            sopIndexCacheService.ensureGlobalIndex(List.copyOf(documentRepository.findAll()));
        }
        return new SyncResult(files.size(), changed, failed);
    }

    private boolean isHtmlFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".html");
    }

    private String toDocumentId(Path file) {
        String filename = file.getFileName().toString();
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(0, idx) : filename;
    }

    private boolean sameContent(String existing, String current) {
        return sha256(existing == null ? "" : existing).equals(sha256(current == null ? "" : current));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    record SyncResult(int scanned, int changed, int failed) {
    }
}
