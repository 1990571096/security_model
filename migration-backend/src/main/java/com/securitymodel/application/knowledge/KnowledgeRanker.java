package com.securitymodel.application.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure local ranker: ACL filtering happens before this class; no model or network call. */
public final class KnowledgeRanker {

    private static final double TITLE_WEIGHT = 3.0;
    private static final double SECTION_WEIGHT = 2.0;
    private static final double CONTENT_WEIGHT = 1.0;

    private KnowledgeRanker() {
    }

    public static List<KnowledgeHit> rank(String question, List<KnowledgeSearchRow> candidates,
                                          int topK, double minCoverage) {
        return rank(question, candidates, topK, minCoverage, 1.0);
    }

    public static List<KnowledgeHit> rank(String question, List<KnowledgeSearchRow> candidates,
                                          int topK, double minCoverage, double minScore) {
        List<String> queryTerms = KnowledgeTokenizer.terms(question);
        if (queryTerms.isEmpty() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int total = candidates.size();
        Map<Long, Set<String>> titleTerms = new HashMap<>();
        Map<Long, Set<String>> sectionTerms = new HashMap<>();
        Map<Long, Set<String>> contentTerms = new HashMap<>();
        for (KnowledgeSearchRow row : candidates) {
            titleTerms.put(row.getChunkId(), new HashSet<>(KnowledgeTokenizer.terms(row.getTitle())));
            sectionTerms.put(row.getChunkId(), new HashSet<>(KnowledgeTokenizer.terms(row.getSectionPath())));
            contentTerms.put(row.getChunkId(), new HashSet<>(KnowledgeTokenizer.terms(row.getContent())));
        }

        Map<String, Integer> documentFrequency = new HashMap<>();
        for (String term : queryTerms) {
            int frequency = 0;
            for (KnowledgeSearchRow row : candidates) {
                if (contains(titleTerms, row, term) || contains(sectionTerms, row, term)
                        || contains(contentTerms, row, term)) {
                    frequency++;
                }
            }
            documentFrequency.put(term, frequency);
        }

        List<KnowledgeHit> hits = new ArrayList<>();
        double totalIdf = 0;
        for (String term : queryTerms) {
            totalIdf += idf(total, documentFrequency.getOrDefault(term, 0));
        }
        for (KnowledgeSearchRow row : candidates) {
            int matched = 0;
            double score = 0;
            double matchedIdf = 0;
            for (String term : queryTerms) {
                double idf = idf(total, documentFrequency.getOrDefault(term, 0));
                double weight = 0;
                if (contains(titleTerms, row, term)) {
                    weight += TITLE_WEIGHT;
                }
                if (contains(sectionTerms, row, term)) {
                    weight += SECTION_WEIGHT;
                }
                if (contains(contentTerms, row, term)) {
                    weight += CONTENT_WEIGHT;
                }
                if (weight > 0) {
                    matched++;
                    score += idf * weight;
                    matchedIdf += idf;
                }
            }
            // IDF-weighted coverage rejects "common word only" matches: an unknown topic
            // leaves its rare terms unmatched and its weighted coverage collapses.
            double coverage = totalIdf <= 0 ? 0 : matchedIdf / totalIdf;
            if (matched > 0 && coverage >= minCoverage && score >= minScore) {
                hits.add(new KnowledgeHit(row, score, matched, coverage));
            }
        }
        hits.sort(Comparator.comparingDouble(KnowledgeHit::score).reversed()
                .thenComparing(hit -> hit.row().getChunkId()));
        return hits.size() <= topK ? hits : new ArrayList<>(hits.subList(0, topK));
    }

    private static boolean contains(Map<Long, Set<String>> terms, KnowledgeSearchRow row, String term) {
        return terms.getOrDefault(row.getChunkId(), Set.of()).contains(term);
    }

    private static double idf(int total, int documentFrequency) {
        return Math.log(1 + (total - documentFrequency + 0.5) / (documentFrequency + 0.5));
    }
}
