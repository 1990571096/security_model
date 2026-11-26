package com.securitymodel.application.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExperimentAssistantIntentTest {

    @Test
    void routesComparisonSubsetAndRatioFollowUp() {
        assertEquals(ExperimentAssistantIntent.Kind.COMPARE,
                ExperimentAssistantIntent.route("对比任务 #2122901116713205761 和 #2122913324293771266，保留比例 30%").kind());
        assertEquals(ExperimentAssistantIntent.Kind.SUBSET,
                ExperimentAssistantIntent.route("至少 2 万张，哪些档位可用").kind());
        assertEquals(ExperimentAssistantIntent.Kind.COMPARE,
                ExperimentAssistantIntent.route("只看 30%").kind());
    }

    @Test
    void keepsTaskListAndReportRoutes() {
        assertEquals(ExperimentAssistantIntent.Kind.TASKS,
                ExperimentAssistantIntent.route("列出我的失败任务").kind());
        assertEquals(ExperimentAssistantIntent.Kind.REPORT,
                ExperimentAssistantIntent.route("解读任务 #42 的报告").kind());
    }
}
