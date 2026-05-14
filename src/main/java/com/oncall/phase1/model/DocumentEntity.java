package com.oncall.phase1.model;

public record DocumentEntity(
        String id,
        String title,
        String html,
        String plainText,
        long createdAt
) {
}
