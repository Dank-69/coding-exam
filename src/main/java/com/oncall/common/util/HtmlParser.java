package com.oncall.common.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

@Component
public class HtmlParser {

    public ParsedHtml parse(String html) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        doc.select("script,style,noscript,template").remove();

        String title = doc.title();
        if (title == null || title.isBlank()) {
            title = "Untitled SOP";
        }
        String plainText = doc.body() == null ? "" : doc.body().text();
        return new ParsedHtml(title.trim(), plainText);
    }

    public record ParsedHtml(String title, String plainText) {
    }
}
