# Agent + RAG 演示运行手册

本文记录当前可真实运行的 DeepSeek Agent 与知识检索链路，以及本机演示环境。它是
操作说明，不代表生产验收结论。

## 1. 已实现的能力

### 1.1 受控 Agent（DeepSeek）

- `DeepSeekAssistantModel` 实现 `AssistantModel`，只在
  `security-model.assistant.model.provider=deepseek` 时启用。
- 使用 OpenAI 兼容的 `/chat/completions`，支持普通回答和 function calling。
- 工具来自服务端显式白名单，模型不能新增工具，也没有 shell / 任意 SQL / 任意文件工具。
- 写工具只返回预览和一次性确认凭据；确认前不写库、不启动训练。
- 模型只收到脱敏后的任务摘要、参数指南和知识片段；API Key 只放在 `Authorization`
  请求头，不进入请求体、日志或响应。
- 外部模型故障映射为 `model_auth_failed`、`model_timeout`、`model_unavailable`、
  `model_invalid_response`、`model_not_configured`，核心任务/报告 API 不依赖模型。

### 1.2 RAG 检索

- ACL-first：先按 `shared` / 本人 `private` 取候选，再排序；跨用户片段不会进入模型。
- 关键词排序保留为基线（`KnowledgeRanker`）。
- 新增本地 embedding：`LocalEmbeddingModel` 将词、二元/三元字符 n-gram 哈希到
  256 维并做 L2 归一化，完全离线、无额外依赖。
- 向量库：Redis 8 `vectorset` 的 `VADD` / `VSIM`，向量 key 为
  `security_model:knowledge:vectors:v1`，元素为 `c:<chunkId>`。
- 混合排序：关键词命中与向量余弦分数合并，Redis 不可用时自动退回关键词检索。
- 向量检索只返回 chunkId，最终仍由 MySQL/Java 校验文档 `status` 与 ACL，向量库不承担
  权限判断。
- 当前 embedding 是可替换的本地演示实现，不是 BGE 神经模型；要换成 BGE/ONNX 时只需
  替换 `LocalEmbeddingModel`，检索和 ACL 代码不变。

## 2. 本地启动

### 2.1 环境

- Java 17+；本机验证使用 JDK 20。
- MySQL 8.0，本地开发密码通过 `DB_PWD` 传入。
- Redis 8.4，必须启用 `vectorset` 模块。
- DeepSeek Key 放在 `secrets/deepseek.key.txt`，该目录已被 `.gitignore` 忽略。

### 2.2 演示库

不要向已有业务库 `eladmin` 直接导入演示 schema。使用独立的
`security_model_demo`：

```powershell
$env:MYSQL_PWD='<本地密码>'
mysql -uroot -e "CREATE DATABASE IF NOT EXISTS security_model_demo CHARACTER SET utf8mb4"
mysql -uroot --default-character-set=utf8mb4 security_model_demo -e "source sql/eladmin.sql"
mysql -uroot --default-character-set=utf8mb4 security_model_demo -e "source sql/security_model_mvp_seed.sql"
mysql -uroot --default-character-set=utf8mb4 security_model_demo -e "source sql/security_model_execution_snapshot.sql"
mysql -uroot --default-character-set=utf8mb4 security_model_demo -e "source sql/security_model_task_run_context.sql"
mysql -uroot --default-character-set=utf8mb4 security_model_demo -e "source sql/security_model_clean_subset_export.sql"
mysql -uroot --default-character-set=utf8mb4 security_model_demo -e "source sql/security_model_operation_audit.sql"
mysql -uroot --default-character-set=utf8mb4 security_model_demo -e "source sql/security_model_ownership.sql"
mysql -uroot --default-character-set=utf8mb4 security_model_demo -e "source sql/security_model_gpu_lease.sql"
mysql -uroot --default-character-set=utf8mb4 security_model_demo -e "source sql/security_model_task_dispatch.sql"
mysql -uroot --default-character-set=utf8mb4 security_model_demo -e "source sql/security_model_request_idempotency.sql"
mysql -uroot --default-character-set=utf8mb4 security_model_demo -e "source sql/security_model_knowledge.sql"
mysql -uroot --default-character-set=utf8mb4 security_model_demo -e "source sql/security_model_assistant.sql"
```

`security_model_gpu_lease_block.sql` 只在旧库缺列时执行；新建库通常已包含
`blocked_at`，重复执行会报 duplicate column，可跳过。

演示库若没有 LDD 算法行，可从一个已有数据库中复制同一行，或显式插入
`implementation_path='vab://pre-defense/ldd'` 的启用算法；不要修改正式论文实验配置。

### 2.3 启动后端

```powershell
$env:DB_NAME='security_model_demo'
$env:DB_PWD='<本地密码>'
$env:SECURITY_MODEL_JWT_SECRET='至少32字节的本地开发密钥'
$env:DEEPSEEK_API_KEY_FILE='F:\Study\postgraduate\毕业论文\security_model\secrets\deepseek.key.txt'
mvn -f migration-backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=deepseek
```

`deepseek` profile 会把知识回答和助手模型都指向
`https://api.deepseek.com/chat/completions`，模型名为 `deepseek-chat`。不激活 profile
时默认仍是 `disabled`，不会外发请求。

## 3. 演示顺序

1. `GET /auth/code` 取验证码，`POST /auth/login` 登录，保存返回的 Bearer Token。
2. `GET /api/assistant/tools` 展示服务端工具白名单。
3. `POST /api/knowledge/reindex` 重建 SDD 索引；首次会同时写入 Redis 向量。
4. `POST /api/knowledge/query` 提问，响应中的 `retrievalMode=hybrid`、
   `vectorHits` 大于 0 表示向量通道参与；`citations` 带章节/行号。
5. `POST /api/assistant/chat` 提问“现在有哪些可用算法和数据集”，观察
   `get_parameter_guide` 工具调用和 `modelStatus=deepseek-flash`。
6. 提问“帮我创建一个任务……”：模型调用 `preview_create_task`，返回
   `pendingConfirmations`，此时数据库没有新任务。
7. `POST /api/assistant/confirmations/confirm` 使用确认 ID 和 token 真正创建任务；
   创建后仍需单独预览/确认启动，永远不会自动启动训练。
8. 再问“查询任务 <id> 的状态摘要”，验证只读工具按当前用户归属返回结果。

## 4. 验证

- `mvn -B -f migration-backend/pom.xml -Pmysql-it verify`（`MYSQL_IT_PASSWORD`、
  `REDIS_IT=true`）→ 230 项单元测试 + 81 项 MySQL/Redis 集成测试全部通过。
- 最终外发请求体已由真实 ChatService + DeepSeek 适配器 + 本地假 HTTP 连续两次请求捕获，覆盖工具执行后的最终回答，断言绝对路径（含 `/api/private`、`/custom dir`）、API Key 与 `Authorization` 不在 body 中。
- 本轮前端未改，但已重跑 `npm run build` 并通过（element chunk 947.92 kB 警告仍在），日志 `tmp/review-current-frontend-build.log`。
- 本机日志：`tmp/review-current-full-verify.log`、`tmp/review-current-assistant-it.log`、
  `tmp/review-current-vector.log`、`tmp/r11-app-vector5.log`。

## 5. 事实边界

- 本机真实冒烟已通过：DeepSeek 普通回答、function calling、创建预览、确认落库、
  本人任务查询、RAG 带引用回答。
- 本地向量库已写入 408 个 SDD chunk；`VADD` 写入 visibility/owner 属性，`VSIM FILTER` 在候选阶段做 ACL。
- 哈希 embedding 是词法候选重排，不是神经语义召回；词法候选为空时直接拒答，不能宣传成 BGE 语义检索。
- 这不是生产级多用户验收：生产库迁移、BGE 神经 embedding、完整外部模型故障矩阵、
  Ubuntu CI 仍未完成。
- 本地 embedding 是哈希特征，不等同于语义神经 embedding；演示时应明确说明。
