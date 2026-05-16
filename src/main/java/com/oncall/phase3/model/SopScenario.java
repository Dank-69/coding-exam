package com.oncall.phase3.model;

import java.util.List;

public record SopScenario(
        String name,
        String file,
        String content,
        List<String> keywords
) {
}
