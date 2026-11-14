package com.securitymodel.application.assistant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** No external provider is configured yet; the assistant reports this explicitly. */
@Component
@ConditionalOnProperty(prefix = "security-model.assistant.model", name = "provider",
        havingValue = "disabled", matchIfMissing = true)
public class DisabledAssistantModel implements AssistantModel {

    @Override
    public ModelReply respond(ModelRequest request) {
        return ModelReply.unavailable("model_disabled",
                "助手模型未配置。配置供应商适配器后可启用自然语言编排；核心任务与知识检索接口不受影响。");
    }
}
