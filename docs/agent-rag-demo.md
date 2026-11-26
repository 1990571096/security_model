# 知识检索与受控助手实操

## 从本地知识查询开始

1. 在 [共享知识库](../knowledge-base/experiment-failure.md) 维护实验说明。启动脚本默认使用 `knowledge-base/`，可通过 `-KnowledgeRoot` 或 `SECURITY_MODEL_KNOWLEDGE_ROOT` 指定其他独立目录。项目设计资料仍放在 `docs/sdd/`。
2. 管理员在知识页点击「同步共享知识库」，或调用 `POST /api/knowledge/reindex`。核对 `changed`、`skipped`、`retired` 和 `failed`。同步只扫描目录顶层的 Markdown，并退役已移除的文件及旧 SDD 索引。算法目录和私有任务报告各有独立索引入口。[索引实现](../migration-backend/src/main/java/com/securitymodel/application/knowledge/KnowledgeIndexService.java)
3. 查看「已索引文档」，再搜索 `GPU_OOM 显存不足`。对照 `citations` 回到对应文件和行号。普通用户只能看到共享资料和本人的私有报告。
4. 对照 `KnowledgeRetrievalService`。先按身份取允许访问的候选，再进行词法排序、相关性门槛判断与可用的向量重排。Redis 向量能力不可用时走词法路径。`LocalEmbeddingModel` 是本地哈希特征。[检索实现](../migration-backend/src/main/java/com/securitymodel/application/knowledge/KnowledgeRetrievalService.java)

## 使用实验助手

从自己的失败任务详情点击「分析失败」。助手核对当前任务的运行标识和尝试次数，按报告错误码与派发记录列出失败阶段、已确认事实、证据缺口和下一步检查。任务状态或尝试变化时，页面会提示返回任务详情。普通问题直接通过本地知识检索返回引用；任务状态可在工作台查看。

当前页面的诊断与知识问答均为只读。现有场景与确认 API 仍可用于兼容测试。

## 兼容接口

| 场景 | 证据来源 | 观察 |
| --- | --- | --- |
| `ldd_parameter_validation` | `validate_ldd_parameters` | 参数是否合法及错误码 |
| `task_preflight` | `get_my_task_summary`、可选 `preview_start_task` | 阻塞项、警告、可选的待确认动作 |
| `result_explanation` | `get_my_report_summary` | 任务状态、白名单指标、失败摘要与导出可用性 |

`GET /api/assistant/scenarios` 返回当前场景契约，`GET /api/assistant/tools` 返回工具白名单。`GET /api/assistant/status` 可核对模型是否启用。默认配置下可查看场景和工具，可用显式工具预览接口验证规则。聊天联调用仓库的本地假模型或单独授权配置的外部模型。[场景目录](../migration-backend/src/main/java/com/securitymodel/application/assistant/AssistantScenarioCatalog.java) · [假模型](../scripts/dev/assistant_browser_fake_model.py) · [DeepSeek 配置](../migration-backend/src/main/resources/application-deepseek.yml)

已有本人 `pending` 任务时，可以向 `POST /api/assistant/tools/preview_start_task/preview` 发送 `{"taskId":42}`。观察预览前后任务原状态和当前轮次。预览可以写确认记录和审计，真正的启动通过 `POST /api/assistant/confirmations/confirm`，请求含 `confirmationId` 与 `token`。确认服务检查凭据、当前用户与上下文，再经允许的写执行器调用业务命令。进行实际确认时使用已经准备好的学习任务环境。

外部模型是可选配置。知识查询和助手工具会通过后端限制候选、引用和对外摘要。模型可请求白名单中的工具，实际业务状态由服务端决定。

```powershell
mvn -B -f migration-backend/pom.xml '-Dtest=KnowledgeIndexServiceTest,AssistantConfirmationServiceTest' test
```

[R7 历史证据](sdd/23-r7-scenario-integration-closeout.md) · [当前交付范围](sdd/26-current-delivery-facts.md)
