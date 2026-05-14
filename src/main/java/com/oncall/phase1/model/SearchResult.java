package com.oncall.phase1.model;

public record SearchResult(
        String id,
        String title,
        String snippet,
        double score
) {
}
