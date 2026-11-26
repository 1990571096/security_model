package com.securitymodel.application.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeTokenizerTest {

    @Test
    void expandsHanRunsIntoStableUnigramsAndBigrams() {
        List<String> terms = KnowledgeTokenizer.terms("租约过期后不能直接接管 GPU");
        assertTrue(terms.contains("租约"));
        assertTrue(terms.contains("过期"));
        assertTrue(terms.contains("接管"));
        assertTrue(terms.contains("gpu"));
        assertFalse(terms.contains("的"));
    }

    @Test
    void appliesNfkcCaseAndPluralNormalization() {
        List<String> terms = KnowledgeTokenizer.terms("ＧＰＵ Tasks chunks");
        assertTrue(terms.contains("gpu"));
        assertTrue(terms.contains("tasks"));
        assertTrue(terms.contains("chunk"));
        assertTrue(terms.contains("chunks"));
    }

    @Test
    void stopQuestionPhrasesDoNotBecomeAnchors() {
        List<String> terms = KnowledgeTokenizer.terms("为什么租约不能接管？");
        assertFalse(terms.contains("为什么"));
        assertFalse(terms.contains("为什"));
        assertTrue(terms.contains("租约"));
    }
}
