package com.securitymodel.application.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalEmbeddingModelTest {

    private final LocalEmbeddingModel model = new LocalEmbeddingModel(true);

    @Test
    void embeddingIsDeterministicAndNormalized() {
        float[] first = model.embed("受控助手如何防止越权访问其他用户的任务？");
        float[] second = model.embed("受控助手如何防止越权访问其他用户的任务？");

        assertEquals(LocalEmbeddingModel.DIMENSION, first.length);
        assertEquals(LocalEmbeddingModel.DIMENSION, second.length);
        for (int i = 0; i < first.length; i++) {
            assertEquals(first[i], second[i], 0.000001f);
        }
        assertEquals(1.0, norm(first), 0.0001);
    }

    @Test
    void relatedTextScoresHigherThanUnrelatedText() {
        float[] query = model.embed("受控助手如何防止越权访问其他用户的任务？");
        float[] related = model.embed("任务和报告工具复用 TaskAccessGuard，跨用户访问必须由服务端拒绝。");
        float[] unrelated = model.embed("红烧肉需要小火慢炖四十分钟，最后收汁。");

        assertTrue(cosine(query, related) > cosine(query, unrelated));
        assertTrue(cosine(query, related) > 0.05);
    }

    @Test
    void blankTextFallsBackToZeroVector() {
        float[] vector = model.embed("   ");
        assertEquals(LocalEmbeddingModel.DIMENSION, vector.length);
        assertEquals(0.0, norm(vector), 0.0001);
    }

    private double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
        }
        double leftNorm = norm(left);
        double rightNorm = norm(right);
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (leftNorm * rightNorm);
    }
}
