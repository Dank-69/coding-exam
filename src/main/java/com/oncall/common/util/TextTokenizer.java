package com.oncall.common.util;

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

        StringBuilder current = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (isAsciiLetterOrDigit(c)) {
                current.append(Character.toLowerCase(c));
                continue;
            }

            flushCurrent(tokens, current);
            if (isCjk(c)) {
                // Keep CJK characters as searchable units so queries like "故障" can match.
                tokens.add(String.valueOf(c));
                continue;
            }
            if (c == '&') {
                tokens.add("&");
            }
        }
        flushCurrent(tokens, current);

        if (tokens.isEmpty()) {
            tokens.add(input.trim().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    private void flushCurrent(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
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
