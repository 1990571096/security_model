package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.knowledge.KnowledgeCitation;
import com.securitymodel.application.knowledge.KnowledgeHit;
import com.securitymodel.application.knowledge.KnowledgeRetrievalService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** ACL-first local retrieval; no model call and no generated answer. */
@Component
public class KnowledgeSearchTool implements AssistantTool {

    private final KnowledgeRetrievalService retrievalService;
    private final ObjectMapper objectMapper;

    public KnowledgeSearchTool(KnowledgeRetrievalService retrievalService, ObjectMapper objectMapper) {
        this.retrievalService = retrievalService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        AssistantSchemas.text(schema, "question", "检索问题，最多 500 字", 500, true);
        return new AssistantToolDescriptor("search_knowledge",
                "按当前用户权限检索本地 SDD/算法/私有报告片段，返回带章节和行号的引用；不调用外部模型",
                false, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of("question"));
        String question = AssistantArgs.requireText(arguments, "question", 500);
        List<KnowledgeHit> hits = retrievalService.search(question);
        List<KnowledgeCitation> citations = retrievalService.citations(hits);

        ArrayNode dataCitations = objectMapper.createArrayNode();
        for (KnowledgeCitation citation : citations) {
            ObjectNode item = dataCitations.addObject();
            item.put("citationId", citation.citationId());
            item.put("title", citation.title());
            item.put("sourceType", citation.sourceType());
            item.put("sourceKey", citation.sourceKey());
            item.put("version", citation.version());
            item.put("sectionPath", citation.sectionPath());
            item.put("startLine", citation.startLine());
            item.put("endLine", citation.endLine());
            item.put("snippet", citation.snippet());
            item.put("visibility", citation.visibility());
        }
        ObjectNode data = objectMapper.createObjectNode();
        data.set("citations", dataCitations);
        String summary = citations.isEmpty()
                ? "本地知识库未命中，不能据此生成结论。"
                : "本地知识库命中 " + citations.size() + " 条引用，最高引用 "
                + citations.get(0).citationId() + "。";
        return new AssistantToolResult(summary, data, null, null, null, null, null);
    }
}
