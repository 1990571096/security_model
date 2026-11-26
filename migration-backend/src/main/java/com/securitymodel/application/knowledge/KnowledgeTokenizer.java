package com.securitymodel.application.knowledge;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic tokenizer for the local Chinese/English corpus.
 *
 * <p>Chinese has no whitespace boundary, so contiguous Han runs are expanded into
 * unigrams and bigrams. Bigrams keep phrases such as "租约过期" discriminative while
 * unigrams keep short technical terms searchable. English/digit tokens are lower-cased
 * and a trailing plural "s" is also indexed as a light normalization. No model or
 * external tokenizer is involved, so evaluation is reproducible on every machine.</p>
 */
public final class KnowledgeTokenizer {

    private static final Set<String> STOP_TERMS = Set.of(
            "的", "了", "是", "在", "和", "与", "或", "及", "对", "为", "有", "无",
            "个", "哪", "些", "为什", "是多",
            "什么", "多少", "如何", "怎么", "是否", "可以", "请问", "一下", "这个", "那个",
            "为什么", "哪些", "哪个", "怎样", "是多少",
            "the", "a", "an", "is", "are", "of", "to", "and", "or", "in", "on", "for"
    );

    private KnowledgeTokenizer() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('\u00a0', ' ');
    }

    /** Unique terms in first-seen order; never returns blank terms. */
    public static List<String> terms(String text) {
        String normalized = normalize(text);
        Set<String> result = new LinkedHashSet<>();
        int index = 0;
        while (index < normalized.length()) {
            char current = normalized.charAt(index);
            if (isHan(current)) {
                int start = index;
                while (index < normalized.length() && isHan(normalized.charAt(index))) {
                    index++;
                }
                addHanTerms(normalized.substring(start, index), result);
            } else if (Character.isLetterOrDigit(current)) {
                int start = index;
                while (index < normalized.length()) {
                    char next = normalized.charAt(index);
                    if (!Character.isLetterOrDigit(next) && next != '_' && next != '-') {
                        break;
                    }
                    index++;
                }
                addWord(normalized.substring(start, index), result);
            } else {
                index++;
            }
        }
        return new ArrayList<>(result);
    }

    private static void addHanTerms(String run, Set<String> result) {
        for (int index = 0; index < run.length(); index++) {
            String single = run.substring(index, index + 1);
            if (!STOP_TERMS.contains(single)) {
                result.add(single);
            }
            if (index + 1 < run.length()) {
                String bigram = run.substring(index, index + 2);
                if (!STOP_TERMS.contains(bigram)) {
                    result.add(bigram);
                }
            }
        }
        // Short technical phrases are also kept whole so exact headings outrank incidental
        // unigram matches. Longer runs are left to bigrams to avoid noisy phrase keys.
        if (run.length() >= 3 && run.length() <= 6 && !STOP_TERMS.contains(run)) {
            result.add(run);
        }
    }

    private static void addWord(String raw, Set<String> result) {
        String word = raw.replace('_', ' ').replace('-', ' ').trim();
        if (word.isEmpty()) {
            return;
        }
        for (String part : word.split("\\s+")) {
            if (part.isEmpty() || STOP_TERMS.contains(part)) {
                continue;
            }
            if (part.length() >= 2) {
                result.add(part);
            }
            if (part.length() >= 4 && part.endsWith("s")) {
                result.add(part.substring(0, part.length() - 1));
            }
        }
    }

    private static boolean isHan(char value) {
        return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN;
    }
}
