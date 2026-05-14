package com.oncall.phase2.controller;

import com.oncall.phase1.dto.SearchResponse;
import com.oncall.phase1.model.SearchResult;
import com.oncall.phase2.service.SemanticSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
@RequestMapping("/v2")
public class Phase2Controller {
    private static final Logger log = LoggerFactory.getLogger(Phase2Controller.class);
    private final SemanticSearchService semanticSearchService;

    public Phase2Controller(SemanticSearchService semanticSearchService) {
        this.semanticSearchService = semanticSearchService;
    }

    @GetMapping("/search")
    @ResponseBody
    public SearchResponse search(@RequestParam("q") String query) {
        long start = System.currentTimeMillis();
        List<SearchResult> results = semanticSearchService.search(query);
        long took = System.currentTimeMillis() - start;
        log.info("GET /v2/search q='{}' -> results={} took={}ms", query, results.size(), took);
        return new SearchResponse(query, results, took);
    }

    @GetMapping
    public String page(@RequestParam(name = "q", required = false) String query, Model model) {
        String q = query == null ? "" : query;
        List<SearchResult> results = q.isBlank() ? List.of() : semanticSearchService.search(q);
        model.addAttribute("query", q);
        model.addAttribute("results", results);
        return "v2-search";
    }
}

