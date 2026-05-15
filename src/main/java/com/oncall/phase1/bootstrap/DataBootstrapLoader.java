package com.oncall.phase1.bootstrap;

import com.oncall.phase1.model.DocumentEntity;
import com.oncall.phase1.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Component
@Order(0)
@ConditionalOnProperty(prefix = "oncall.bootstrap", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DataBootstrapLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataBootstrapLoader.class);

    private final DocumentService documentService;

    @Value("${oncall.bootstrap.data-dir:data}")
    private String dataDir;

    public DataBootstrapLoader(DocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path directory = Path.of(dataDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            log.warn("startup import skipped: data directory not found path='{}'", directory);
            return;
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".html"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            log.error("startup import failed to scan directory path='{}' reason={}", directory, ex.getMessage());
            return;
        }

        if (files.isEmpty()) {
            log.warn("startup import skipped: no html files found in path='{}'", directory);
            return;
        }

        log.info("startup import begin path='{}' files={}", directory, files.size());
        int success = 0;
        int failed = 0;
        for (Path file : files) {
            try {
                String html = Files.readString(file, StandardCharsets.UTF_8);
                String id = toDocumentId(file);
                DocumentEntity document = documentService.upsert(id, html);
                success++;
                log.info("startup import ok file='{}' id='{}' title='{}'", file.getFileName(), document.id(), document.title());
            } catch (Exception ex) {
                failed++;
                log.error("startup import failed file='{}' reason={}", file.getFileName(), ex.getMessage());
            }
        }
        log.info("startup import done success={} failed={}", success, failed);
    }

    private String toDocumentId(Path file) {
        String filename = file.getFileName().toString();
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(0, idx) : filename;
    }
}
