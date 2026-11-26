# 05 看懂可选组件

**目标**是从代码里看出每种技术处理的是什么问题。

## 拆两条派发线

画一个任务事务框，里面写 `task + dispatch intent`。

1. 左边画 `db`。给框内加 GPU 租约，框外接 `AfterCommitTaskDispatcher` 和 `taskAsync`。
2. 右边画 `rabbit`。给框内加 Outbox，框外依次接 Relay、RabbitMQ、Worker。把 GPU 租约画在 Worker 领任务的位置。
3. 画出两个确认箭头。Relay 等 Broker 发布确认，Worker 在业务结果归结后按消费处理结果确认消息。
4. 假设 Broker 已接受消息、Relay 未写回发送结果。用消费账本和意图状态解释重复消息如何收敛。[派发契约](../sdd/21-r4-outbox-rabbitmq-design.md)

## 看 Redis 和助手

1. 在 `ResourceQueryService` 看目录读取与启动时权威读取。用一句话区分“用户页面资源列表”和“启动时资源事实”。[资源查询](../../migration-backend/src/main/java/com/securitymodel/application/resource/ResourceQueryService.java)
2. 按 Agent/RAG 手册做一次索引和查询。找一段引用，用行号回到 SDD 原文。再在 `KnowledgeRetrievalService` 找用户条件、词法门槛和向量重排。[Agent/RAG 实操](../agent-rag-demo.md)
3. 读 `AssistantController` 中 `/tools/{toolName}/preview` 和 `/confirmations/confirm`。分别写出输入、输出和数据库副作用。定位 `AssistantConfirmationService` 如何把一次确认交给服务器允许的动作，并关联原用户与上下文。[助手接口](../../migration-backend/src/main/java/com/securitymodel/interfaces/web/AssistantController.java) · [确认服务](../../migration-backend/src/main/java/com/securitymodel/application/assistant/AssistantConfirmationService.java)

## 读测试报告

```powershell
mvn -B -f migration-backend/pom.xml '-Dtest=DispatchMessageHandlerTest,KnowledgeIndexServiceTest,AssistantConfirmationServiceTest' test
python -m unittest discover -s algorithm-runtime/vab/tests -v
```

`*Test` 验证类或局部代码，部分测试启动短进程。集成测试使用独立 MySQL、Redis、Broker 等设施，按 CI 对应工作流配置。真实 LDD 验收在 VaB、PyTorch 和 GPU 环境验证工程运行。画三列分别写一条结论和一条证据。[CI 工作流](../../.github/workflows/migration-quality.yml) · [历史验收](../sdd/26-current-delivery-facts.md)

**自测**。Redis 关了为什么还能理解当前任务状态？任务由 MySQL 存储，资源查询可回源，知识查询可用本地词法路径。RabbitMQ 的消息只在选择相应派发模式后参与执行。

[下一课：讲给面试官听](06-tell-the-story.md)
