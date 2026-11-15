package com.securitymodel.application.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded, idempotent repair of vector rows whose retired document cleanup failed. */
@Component
public class KnowledgeVectorReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorReconcileJob.class);

    private final KnowledgeIndexService indexService;
    private final int batchSize;

    public KnowledgeVectorReconcileJob(
            KnowledgeIndexService indexService,
            @Value("${security-model.knowledge.vector.reconcile-batch:50}") int batchSize) {
        this.indexService = indexService;
        this.batchSize = Math.max(1, Math.min(200, batchSize));
    }

    @Scheduled(fixedDelayString = "${security-model.knowledge.vector.reconcile-interval-ms:300000}",
            initialDelayString = "${security-model.knowledge.vector.reconcile-initial-delay-ms:60000}")
    public void reconcile() {
        try {
            int cleaned = indexService.reconcileRetiredVectors(batchSize);
            if (cleaned > 0) {
                log.info("退役知识向量对账完成: cleaned={}", cleaned);
            }
        } catch (RuntimeException exception) {
            log.warn("退役知识向量对账任务失败: reason={}", exception.getClass().getSimpleName());
        }
    }
}
