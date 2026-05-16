package com.oncall.phase3.model;

import java.util.List;

public record SopIndex(
        String id,
        String filename,
        String title,
        String sourceHash,
        String parserVersion,
        String generatedAt,
        String indexFile,
        String dutyFile,
        String duty,
        String metricsFile,
        String metrics,
        String troubleshootingIndexFile,
        List<SopScenario> scenarios,
        String escalationFile,
        String escalation,
        String forbiddenFile,
        String forbidden,
        String commandsFile,
        String commands
) {
}
