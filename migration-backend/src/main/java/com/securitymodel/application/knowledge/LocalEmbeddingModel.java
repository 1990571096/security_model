package com.securitymodel.application.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Local, dependency-free embedding used for the demo vector index. It is intentionally
 * pluggable: replace this bean with a BGE/ONNX implementation later without changing
 * retrieval. The current implementation hashes word and character n-gram features into
 * a fixed 256-dimensional normalized vector, so it works fully offline.
 */
@Component
public class LocalEmbeddingModel {

    public static final int DIMENSION = 256;
    private static final long FNV_OFFSET = 1469598103934665603L;
    private static final long FNV_PRIME = 1099511628211L;

    private final boolean enabled;

    public LocalEmbeddingModel(
            @Value("${security-model.knowledge.embedding.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public int dimension() {
        return DIMENSION;
    }

    public float[] embed(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return new float[DIMENSION];
        }
        double[] vector = new double[DIMENSION];
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String token : tokens(normalized)) {
            add(vector, "w:" + token, 1.0);
        }
        String compact = compact(normalized);
        for (int i = 0; i + 1 < compact.length(); i++) {
            add(vector, "b:" + compact.charAt(i) + compact.charAt(i + 1), 0.85);
        }
        for (int i = 0; i + 2 < compact.length(); i++) {
            add(vector, "t:" + compact.substring(i, i + 3), 0.7);
        }
        for (int i = 0; i < compact.length(); i++) {
            add(vector, "u:" + compact.charAt(i), 0.35);
        }
        double norm = 0;
        for (double value : vector) {
            norm += value * value;
        }
        float[] result = new float[DIMENSION];
        if (norm == 0) {
            return result;
        }
        double sqrt = Math.sqrt(norm);
        for (int i = 0; i < DIMENSION; i++) {
            result[i] = (float) (vector[i] / sqrt);
        }
        return result;
    }

    private List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                current.append(c);
            } else if (!current.isEmpty()) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private String compact(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private void add(double[] vector, String feature, double weight) {
        long hash = FNV_OFFSET;
        for (int i = 0; i < feature.length(); i++) {
            hash ^= feature.charAt(i);
            hash *= FNV_PRIME;
        }
        int index = (int) Math.floorMod(hash, DIMENSION);
        int sign = (hash & (1L << 62)) == 0 ? 1 : -1;
        vector[index] += sign * weight;
    }
}
