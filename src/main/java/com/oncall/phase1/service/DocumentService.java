package com.oncall.phase1.service;

import com.oncall.common.util.HtmlParser;
import com.oncall.common.util.TextTokenizer;
import com.oncall.phase1.index.InvertedIndex;
import com.oncall.phase1.model.DocumentEntity;
import com.oncall.phase1.store.DocumentRepository;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {
    private final HtmlParser htmlParser;
    private final TextTokenizer tokenizer;
    private final InvertedIndex invertedIndex;
    private final DocumentRepository repository;

    public DocumentService(HtmlParser htmlParser, TextTokenizer tokenizer, InvertedIndex invertedIndex, DocumentRepository repository) {
        this.htmlParser = htmlParser;
        this.tokenizer = tokenizer;
        this.invertedIndex = invertedIndex;
        this.repository = repository;
    }

    public DocumentEntity upsert(String id, String html) {
        HtmlParser.ParsedHtml parsed = htmlParser.parse(html);
        DocumentEntity document = new DocumentEntity(
                id,
                parsed.title(),
                html,
                parsed.plainText(),
                System.currentTimeMillis()
        );
        repository.save(document);
        invertedIndex.upsertDocument(id, tokenizer.tokenize(parsed.plainText()));
        return document;
    }
}
