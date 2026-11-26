package com.securitymodel.application.assistant;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Product-facing contract for the three assistant scenarios. The catalog is descriptive:
 * it cannot execute tools and it is not a second tool registry. Every listed tool must exist
 * in {@link AssistantToolRegistry}; write tools must still be preview-only.
 */
@Component
public class AssistantScenarioCatalog {

    public record Scenario(
            String name,
            String title,
            String inputExample,
            List<String> tools,
            String requiredEvidenceTool,
            List<String> returnFields,
            String citationPolicy,
            String failureFallback,
            String sideEffectPolicy) {
    }

    private final List<Scenario> scenarios;

    @org.springframework.beans.factory.annotation.Autowired
    public AssistantScenarioCatalog(AssistantToolRegistry registry) {
        this(registry, true);
    }

    /**
     * @param validate when false, only build the catalog; used by unit tests that wire a
     *                 deliberately small tool registry. Production startup always validates.
     */
    public AssistantScenarioCatalog(AssistantToolRegistry registry, boolean validate) {
        this.scenarios = List.of(
                new Scenario(
                        "ldd_parameter_validation",
                        "LDD 参数校验",
                        "创建 LDD 清洗任务，数据集 4、算法 14，batch_size 128、num_epochs 5，检查参数是否可执行",
                        List.of("validate_ldd_parameters", "get_parameter_guide", "search_knowledge"),
                        "validate_ldd_parameters",
                        List.of("scenario", "status", "validation", "citations", "failure"),
                        "参数校验结论只取自 validate_ldd_parameters 的服务端配置策略；解释依据引用本地知识时返回 citationId/sectionPath/startLine/endLine",
                        "数据集/算法不可用、非 LDD 算法或参数非法时返回 status=answered 与 validation.valid=false、稳定 code；不生成创建确认",
                        "只读校验：不创建或修改实验任务、不写调度意图、不领取 GPU、不生成确认凭据；允许保存审计记录"),
                new Scenario(
                        "task_preflight",
                        "任务预检",
                        "任务 500 现在能否启动？列出阻塞项",
                        List.of("get_my_task_summary", "preview_start_task", "search_knowledge"),
                        "get_my_task_summary",
                        List.of("scenario", "status", "taskId", "taskStatus", "blockers", "warnings",
                                "pendingConfirmation", "citations", "failure"),
                        "任务状态以 get_my_task_summary 为准；调度/租约规则引用本地知识时返回 citationId 与行号",
                        "任务不存在或不属于当前用户时拒绝；不可启动时返回 blockers，不领取 GPU、不改变任务状态",
                        "preview_start_task 只校验并预览，确认前不创建或修改实验任务、不写调度意图、不领取 GPU；允许保存确认与审计记录"),
                new Scenario(
                        "result_explanation",
                        "结果解释",
                        "解释任务 500 的清洗结果和失败原因",
                        List.of("get_my_report_summary", "search_knowledge"),
                        "get_my_report_summary",
                        List.of("scenario", "status", "taskId", "taskStatus", "metrics", "failureSummary",
                                "exportAvailability", "citations", "failure"),
                        "指标只取自 get_my_report_summary 的白名单字段；方法/阈值解释引用本地知识时返回 citationId 与行号",
                        "任务不存在或越权时拒绝；结果未解析或未采集时明确返回 metrics=unavailable，不编造数值",
                        "只读；不生成写预览，不返回原始结果、日志、文件路径或其他用户数据")
        );
        if (!validate) {
            return;
        }
        for (Scenario scenario : scenarios) {
            Set<String> declared = Set.copyOf(scenario.tools());
            Set<String> registered = registry.descriptors().stream()
                    .map(AssistantToolDescriptor::name)
                    .collect(java.util.stream.Collectors.toSet());
            if (!registered.containsAll(declared)) {
                throw new IllegalStateException("助手场景声明了未注册工具: " + scenario.name());
            }
            if (!declared.contains(scenario.requiredEvidenceTool())) {
                throw new IllegalStateException("助手场景的必要证据工具不在白名单: " + scenario.name());
            }
            for (AssistantToolDescriptor descriptor : registry.descriptors()) {
                if (declared.contains(descriptor.name()) && descriptor.write()
                        && scenario.returnFields().stream().noneMatch("pendingConfirmation"::equals)) {
                    throw new IllegalStateException("写工具场景必须返回 pendingConfirmation: " + scenario.name());
                }
            }
            if (scenario.returnFields().stream().noneMatch("failure"::equals)) {
                throw new IllegalStateException("助手场景必须声明 failure 降级字段: " + scenario.name());
            }
        }
    }

    public List<Scenario> scenarios() {
        return scenarios;
    }

    public Scenario find(String name) {
        return scenarios.stream().filter(scenario -> scenario.name().equals(name))
                .findFirst().orElse(null);
    }
}
