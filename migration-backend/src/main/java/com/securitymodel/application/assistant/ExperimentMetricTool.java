package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
class ExperimentMetricTool implements AssistantTool {
    static final String NAME = "explain_clean_purity";
    private final ObjectMapper mapper;

    ExperimentMetricTool(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        return new AssistantToolDescriptor(NAME, "解释平台报告中清洗纯度的含义和适用范围。",
                false, AssistantSchemas.object());
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of());
        var data = mapper.createObjectNode()
                .put("definition", "清洗纯度表示被选入保留子集的样本里，有多少比例是干净样本。"
                        + "计算方式是子集中的干净样本数除以选中样本总数，再乘以 100%。")
                .put("interpretation", "纯度越高，表示这批保留下来的样本中污染占比越低。"
                        + "比较时要同时看保留比例和实际保留样本数，并核对数据集与实验配置。")
                .put("limitation", "即使纯度达到 100%，单凭这个指标也无法判断找回了多少干净样本、"
                        + "清除了多少原始污染，或模型训练后的准确率。");
        return new AssistantToolResult("已读取平台的清洗纯度指标口径。",
                data, null, null, null, null, null);
    }
}
