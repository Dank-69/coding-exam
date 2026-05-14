package com.oncall.phase1.dto;

import com.oncall.phase1.model.SearchResult;

import java.util.List;

public record SearchResponse(
        String query,
        List<SearchResult> results,
        long took
) {
}
