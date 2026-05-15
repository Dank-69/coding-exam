package com.oncall.common.util;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class TextTokenizer {

    public List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        if (input == null || input.isBlank()) {
            return tokens;
        }

        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (isAsciiLetterOrDigit(c)) {
                flushCjk(tokens, cjk);
                ascii.append(Character.toLowerCase(c));
                continue;
            }

            if (isCjk(c)) {
                flushAscii(tokens, ascii);
                cjk.append(c);
                continue;
            }

            flushAscii(tokens, ascii);
            flushCjk(tokens, cjk);
            if (c == '&') {
                tokens.add("&");
            }
        }
        flushAscii(tokens, ascii);
        flushCjk(tokens, cjk);

        if (tokens.isEmpty()) {
            tokens.add(input.trim().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    private void flushAscii(List<String> tokens, StringBuilder ascii) {
        if (!ascii.isEmpty()) {
            tokens.add(ascii.toString());
            ascii.setLength(0);
        }
    }

    private void flushCjk(List<String> tokens, StringBuilder cjk) {
        if (cjk.isEmpty()) {
            return;
        }
        String text = cjk.toString();
        for (Term term : HanLP.segment(text)) {
            String word = term.word == null ? "" : term.word.trim().toLowerCase(Locale.ROOT);
            if (!word.isBlank()) {
                tokens.add(word);
            }
        }
        cjk.setLength(0);
    }

    private boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9');
    }

    private boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }
}
