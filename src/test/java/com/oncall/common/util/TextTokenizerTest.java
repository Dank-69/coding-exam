package com.oncall.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextTokenizerTest {

    private final TextTokenizer tokenizer = new TextTokenizer();

    @Test
    void tokenize_shouldUseHanlpWordsWithoutCjkCharacterFallback() {
        List<String> tokens = tokenizer.tokenize("数据库主从延迟故障");

        assertThat(tokens).contains("数据库", "故障");
        assertThat(tokens).doesNotContain("数", "据", "库", "故", "障");
    }

    @Test
    void tokenize_shouldKeepAsciiAndAmpersandCompatibility() {
        List<String> tokens = tokenizer.tokenize("服务 OOM & CDN");

        assertThat(tokens).contains("服务", "oom", "&", "cdn");
        assertThat(tokens).doesNotContain("服", "务");
    }
}
