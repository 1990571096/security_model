package com.securitymodel.application.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownKnowledgeChunkerTest {

    @Test
    void preservesTitleSectionAndLineRanges() {
        String markdown = "# 项目状态\n\n第一段。\n\n## R2 租约\n\n租约过期不能直接接管。\n";
        List<MarkdownKnowledgeChunker.Draft> drafts =
                MarkdownKnowledgeChunker.chunk(markdown, "fallback", 800);

        assertEquals(2, drafts.size());
        assertEquals("项目状态", drafts.get(0).title());
        assertEquals("第一段。", drafts.get(0).content());
        assertEquals(3, drafts.get(0).startLine());
        assertEquals(3, drafts.get(0).endLine());

        assertEquals("项目状态 > R2 租约", drafts.get(1).sectionPath());
        assertEquals(7, drafts.get(1).startLine());
        assertEquals(7, drafts.get(1).endLine());
        assertTrue(drafts.get(1).content().contains("租约过期"));
    }

    @Test
    void splitsAtMaxSizeWithoutLosingLines() {
        StringBuilder markdown = new StringBuilder("# Doc\n\n");
        for (int index = 0; index < 20; index++) {
            markdown.append("- line ").append(index).append('\n');
        }
        List<MarkdownKnowledgeChunker.Draft> drafts =
                MarkdownKnowledgeChunker.chunk(markdown.toString(), "fallback", 120);

        assertTrue(drafts.size() > 1, "long document must be split");
        for (MarkdownKnowledgeChunker.Draft draft : drafts) {
            assertTrue(draft.content().length() <= 120, "chunk must respect max size");
            assertTrue(draft.endLine() >= draft.startLine());
        }
    }

    @Test
    void emptyOrHeadingOnlyInputProducesNoChunks() {
        assertTrue(MarkdownKnowledgeChunker.chunk("", "fallback", 800).isEmpty());
        assertTrue(MarkdownKnowledgeChunker.chunk("# Only heading\n\n## Another\n", "fallback", 800).isEmpty());
        assertFalse(MarkdownKnowledgeChunker.chunk("# Doc\n\ntext", "fallback", 800).isEmpty());
    }
}
