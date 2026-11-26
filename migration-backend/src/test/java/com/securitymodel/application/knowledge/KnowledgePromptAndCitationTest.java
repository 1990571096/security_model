package com.securitymodel.application.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgePromptAndCitationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void outboundPayloadIsAWhitelistAndRedactsSensitiveData() {
        KnowledgeCitation citation = new KnowledgeCitation("C1", 42L, "sdd", "sdd:05-decisions.md",
                "租约设计", 2, "R2 > 租约",
                0, 10, 20,
                "路径 F:\\Study\\secret\\data.txt 和 key sk-abcdefgh12345678", 1.0,
                "shared", 7L);
        String body = KnowledgePromptBuilder
                .build(objectMapper, "model-x", "租约为什么不能接管", List.of(citation), 600)
                .toString();

        assertTrue(body.contains("[C1]"));
        assertFalse(body.contains("sdd:05-decisions.md"), "source key must not leave the process");
        assertFalse(body.contains("F:\\\\Study"), "absolute path must be redacted");
        assertFalse(body.contains("sk-abcdefgh12345678"), "credential must be redacted");
        assertFalse(body.contains("documentId"));
        assertFalse(body.contains("owner"));
        assertFalse(body.contains("\"tools\""), "no tool registry may be exposed");
        assertFalse(body.contains("\"tool_choice\""));
        assertFalse(body.contains("\"functions\""));
    }

    @Test
    void payloadContainsOnlyAllowedTopLevelFields() {
        KnowledgeCitation citation = new KnowledgeCitation("C1", 1L, "sdd", "sdd:05.md",
                "标题", 1, null, 0, 1, 1, "正文", 1.0, "shared", null);
        var payload = KnowledgePromptBuilder.build(objectMapper, "model-x", "问题", List.of(citation), 600);
        assertEquals(4, payload.size(), "model/messages/temperature/max_tokens only");
        assertTrue(payload.has("model"));
        assertTrue(payload.has("messages"));
    }

    @Test
    void fabricatedCitationRejectsWholeModelAnswer() {
        List<KnowledgeCitation> citations = List.of(citation());
        assertNull(KnowledgePromptBuilder.sanitizeAnswer("依据 [C1] 可知，另见 [C99]。", citations));
    }

    @Test
    void validCitationSurvivesAndIsLengthCapped() {
        List<KnowledgeCitation> citations = List.of(citation());
        String answer = KnowledgePromptBuilder.sanitizeAnswer("依据 [C1] 可知。", citations);
        assertEquals("依据 [C1] 可知。", answer);
        String longAnswer = "依据 [C1] " + "x".repeat(3000);
        assertEquals(2000, KnowledgePromptBuilder.sanitizeAnswer(longAnswer, citations).length());
    }

    private KnowledgeCitation citation() {
        return new KnowledgeCitation("C1", 1L, "sdd", "sdd:05.md", "标题", 1,
                "章节", 0, 1, 1, "正文", 1.0, "shared", null);
    }
}
