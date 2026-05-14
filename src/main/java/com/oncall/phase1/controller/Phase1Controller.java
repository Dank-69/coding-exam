package com.oncall.phase1.controller;

import com.oncall.phase1.dto.SearchResponse;
import com.oncall.phase1.dto.UploadDocumentRequest;
import com.oncall.phase1.dto.UploadDocumentResponse;
import com.oncall.phase1.model.DocumentEntity;
import com.oncall.phase1.model.SearchResult;
import com.oncall.phase1.service.DocumentService;
import com.oncall.phase1.service.KeywordSearchService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
@RequestMapping("/v1")
public class Phase1Controller {
    private static final Logger log = LoggerFactory.getLogger(Phase1Controller.class);
    private final DocumentService documentService;
    private final KeywordSearchService keywordSearchService;

    public Phase1Controller(DocumentService documentService, KeywordSearchService keywordSearchService) {
        this.documentService = documentService;
        this.keywordSearchService = keywordSearchService;
    }

    @PostMapping("/documents")
    @ResponseBody
    public ResponseEntity<UploadDocumentResponse> uploadDocument(@Valid @RequestBody UploadDocumentRequest request) {
        long start = System.currentTimeMillis();
        int htmlChars = request.html() == null ? 0 : request.html().length();
        log.info("POST /v1/documents id='{}' htmlChars={} (start)", request.id(), htmlChars);
        DocumentEntity document = documentService.upsert(request.id(), request.html());
        long took = System.currentTimeMillis() - start;
        log.info("POST /v1/documents id='{}' -> title='{}' took={}ms", document.id(), document.title(), took);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new UploadDocumentResponse(document.id(), document.title()));
    }

    @GetMapping("/search")
    @ResponseBody
    public SearchResponse search(@RequestParam("q") String query) {
        long start = System.currentTimeMillis();
        List<SearchResult> results = keywordSearchService.search(query);
        long took = System.currentTimeMillis() - start;
        log.info("GET /v1/search q='{}' -> results={} took={}ms", query, results.size(), took);
        return new SearchResponse(query, results, took);
    }

    @GetMapping
    public String page(@RequestParam(name = "q", required = false) String query, Model model) {
        String q = query == null ? "" : query;
        List<SearchResult> results = q.isBlank() ? List.of() : keywordSearchService.search(q);
        model.addAttribute("query", q);
        model.addAttribute("results", results);
        return "v1-search";
    }
}
