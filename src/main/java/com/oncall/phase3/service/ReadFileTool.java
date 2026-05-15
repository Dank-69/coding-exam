package com.oncall.phase3.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

@Component
public class ReadFileTool {
    private static final Pattern SAFE_FILENAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path dataDir = Path.of("data").toAbsolutePath().normalize();

    public String readFile(String filename) {
        String safeFilename = validate(filename);
        Path target = dataDir.resolve(safeFilename).normalize();
        if (!target.startsWith(dataDir)) {
            throw new IllegalArgumentException("filename must stay inside data/");
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("file not found: " + safeFilename);
        }
        try {
            Path realDataDir = dataDir.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realDataDir)) {
                throw new IllegalArgumentException("filename must stay inside data/");
            }
            return Files.readString(realTarget, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to read file: " + safeFilename, ex);
        }
    }

    private String validate(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename is required");
        }
        String trimmed = filename.trim();
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")
                || !SAFE_FILENAME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("filename must be a plain file name containing only letters, numbers, '.', '_' or '-'");
        }
        return trimmed;
    }
}
