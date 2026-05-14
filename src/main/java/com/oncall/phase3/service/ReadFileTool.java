package com.oncall.phase3.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ReadFileTool {
    private final Path dataDir = Path.of("data").toAbsolutePath().normalize();

    public String readFile(String filename) {
        validate(filename);
        Path target = dataDir.resolve(filename).normalize();
        if (!target.startsWith(dataDir)) {
            throw new IllegalArgumentException("filename must stay inside data/");
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw new IllegalArgumentException("file not found: " + filename);
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to read file: " + filename, ex);
        }
    }

    private void validate(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename is required");
        }
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException("filename must be a plain file name");
        }
    }
}

