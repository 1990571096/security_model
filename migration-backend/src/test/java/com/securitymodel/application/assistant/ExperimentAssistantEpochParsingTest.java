package com.securitymodel.application.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExperimentAssistantEpochParsingTest {

    @Test
    void usesTargetOfExplicitChangeInsteadOfBaselineNumber() {
        assertEquals(6, AssistantChatService.requestedEpochs(
                "基于任务 #2122901116713205761 的训练轮数 5，生成增加到 6 轮的下一轮草案。"));
        assertEquals(6, AssistantChatService.requestedEpochs("把训练轮数从 5 改为 6"));
        assertEquals(7, AssistantChatService.requestedEpochs("5 → 7 epochs"));
        assertEquals(8, AssistantChatService.requestedEpochs("num_epochs=8"));
        assertEquals(6, AssistantChatService.requestedEpochs("训练轮数 6 轮"));
        assertNull(AssistantChatService.requestedEpochs("基于这个任务生成下一轮草案"));
    }

    @Test
    void readsSampleThresholdAcrossSpacesAndChineseUnits() {
        assertEquals(20_000, AssistantChatService.minimumSamples("我至少需要 2 万张"));
        assertEquals(15_000, AssistantChatService.minimumSamples("我至少需要 15000 张"));
        assertEquals(15_000, AssistantChatService.minimumSamples("我至少需要 15000 个样本"));
        assertEquals(12_500, AssistantChatService.minimumSamples("至少 1.25 万个"));
    }
}
