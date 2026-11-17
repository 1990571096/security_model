# R7 受控实验助手场景联调收口（2026-11-17）

本文记录 R7 助手从“HTTP 契约与 MockMvc 验收”推进到“真实前端浏览器 + 隔离后端 + 脚本化假模型”的本轮证据。它不是生产验收，也不替代真实外部模型、真实 LDD/GPU 或原 `eladmin` 库的验证。

## 1. 本轮范围

| 项 | 本轮处理 |
| --- | --- |
| 参数策略 | LDD `dataset` / `trigger_type` 增加文本类型与支持集合校验；缺失、类型错误、未知枚举分别返回稳定错误码。 |
| 模型证据 | 为 `validate_ldd_parameters`、`get_my_task_summary` 增加专用、有限量、脱敏投影。 |
| 浏览器验收 | 隔离 MySQL + 8100 后端 + 8090 脚本化假模型 + 8014 真实 Vite 前端。 |
| 任务 ID | R7 当时增加 `taskIdText`，避免 19 位雪花 ID 在 JavaScript `Number` 中丢失精度；阶段 1 后续已将 HTTP 边界统一为十进制字符串，见 §9。 |
| 布局 | 修正助手双栏在 800px 视口的挤压，改为单栏；检查 390px 手机宽度无横向溢出。 |
| 文档 | 明确“工具执行失败 / 校验执行成功但参数无效 / 运行环境尚未验证”三态。 |
| 真实边界 | 未运行真实 LDD/GPU；未迁移或操作原 `eladmin`；未调用真实外部模型。 |

## 2. 参数策略与错误语义

`LddConfigurationPolicy` 的支持集合与种子 `sql/security_model_vab_seed.sql` 的 `config_schema` 保持一致：

- `dataset`：`cifar10`、`cifar100`、`gtsrb`
- `trigger_type`：`SIG`、`WaNet`、`badnet`、`blended`、`CL`
- `poisoned_mode`：`all2one`、`all2all`

错误码分工：

| 场景 | 错误码 |
| --- | --- |
| 缺失、`null`、空白 | `PARAMETER_REQUIRED` |
| 数字、布尔、对象、数组等非文本值 | `PARAMETER_TYPE_INVALID` |
| 文本但不在支持集合内 | `PARAMETER_VALUE_INVALID` |
| 数值越界 | `PARAMETER_OUT_OF_RANGE` |
| 未知参数键 | `PARAMETER_UNKNOWN` |

`LddConfigurationPolicyTest` 7/7 覆盖三种数据集 × 五种触发器、未知数据集、未知触发器、数字/布尔/对象类型错误、`data_path` / `poisoned_mode` 类型错误、种子 schema 选项一致性。普通任务 API 与助手共用同一策略；合法默认 `cifar10 / SIG` 保持兼容。

## 3. 模型evidence投影

`AssistantModelEvidence` 仍是 package-private、总上限 3200 字符。新增投影：

- 校验：`valid`、`code`、`message`、`parameterSource`、`configFingerprint`、`sideEffect`、`confirmationCreated`、最多 20 个标量 `normalizedParameters`；`data_path` 永不复制，对象/数组参数不复制，文本继续经 `KnowledgeSanitizer.forModel`。
- 预检：`executorMode`、`executorRole`、`executorConfiguration`、`blockers` / `warnings` 各最多 4 条，每条只保留 `code`（80 字）与 `message`（180 字）。
- 新增 `taskIdText`（80 字）进入模型证据，和数值 `taskId` 并存。

本地假 HTTP 捕获 `tmp/browser-fixture/model-requests.jsonl` 已核对第二轮请求：

- 合法校验：`valid=true`、`PARAMETERS_VALID`、`configFingerprint`、`normalizedParameters` 含 `batch_size=128` / `trigger_type=SIG`，无绝对路径、`data_path`、凭据或原始日志。
- 非法类型：`valid=false`、`PARAMETER_TYPE_INVALID`、`message` 明确 `dataset 必须是字符串`，同样无路径。
- 预检：`executorConfiguration=worker_unknown`、`WORKER_CONFIGURATION_UNKNOWN`、`taskIdText=2121793576624254978`，无绝对路径。
- `AssistantOutboundPrivacyTest` 4/4 通过，第二轮请求体经 `ObjectMapper` 解析后断言。

## 4. 浏览器验收矩阵

环境：

- 后端：`http://127.0.0.1:8100`，隔离库 `security_model_browser`，`rabbit/api`，模型 `fake-browser-model`，`base-url=http://127.0.0.1:8090/chat/completions`。
- 假模型：`scripts/dev/assistant_browser_fake_model.py`，监听 8090。
- 前端：`http://127.0.0.1:8014/assistant`，真实 Vite，代理 `/api`、`/auth` 到 8100。
- 登录：本地隔离库 `admin` / `123456`；验证码从 `/auth/code` SVG 解析。

| 路径 | 浏览器结果 | 证据 |
| --- | --- | --- |
| LDD 合法参数 | “参数校验通过”，显示 `PARAMETERS_VALID`、`batch_size=128`、`trigger_type=SIG`，未创建任务/确认凭据 | 页面文本；`tmp/stage9-browser/` |
| 未知 dataset | `PARAMETER_VALUE_INVALID`，提示支持 `cifar10, cifar100, gtsrb` | 页面文本 |
| 未知 trigger | `PARAMETER_VALUE_INVALID`，提示支持 `SIG, WaNet, badnet, blended, CL` | 页面文本 |
| 数字 dataset | `PARAMETER_TYPE_INVALID`，`dataset 必须是字符串` | 页面文本 |
| 布尔 dataset | `PARAMETER_TYPE_INVALID`，`dataset 必须是字符串` | 页面文本 |
| 对象 dataset | `PARAMETER_TYPE_INVALID`，`dataset 必须是字符串` | 页面文本 |
| 结果未采集 | 任务 `pending`，服务端未取得指标，不展示模型猜测数值，导出不可用 | 页面文本 |
| Worker 未知 | `executorConfiguration=worker_unknown`、`WORKER_CONFIGURATION_UNKNOWN`，页面显示“执行器 需 Worker 校验” | 页面文本 |
| 越界工具 | 页面清空结果并提示“当前场景不允许调用该工具”；同一请求直连 API 返回 HTTP 403 `ASSISTANT_TOOL_NOT_ALLOWED_FOR_SCENARIO` | 浏览器 + 直连状态 |
| 模型故障 | 假模型 503，页面显示“模型暂不可用，未执行工具”，状态 `model_unavailable` | 页面文本 |
| 确认交互 | `preview_start_task` 生成“待确认：启动任务”卡片与“确认执行”按钮，一次性凭据 #8；本轮未点击执行 | `tmp/stage9-browser/aha_bua_36388_1794914776474655000.png` |
| 任务 ID 精度 | structured 卡片显示 `2121793576624254978`，与服务端正文一致 | 页面文本 |

布局检查：

- 桌面 1280×900：`scrollWidth == clientWidth`，无横向溢出。
- 窄屏 800×900：助手双栏改为单栏，`scrollWidth == clientWidth`，无横向溢出；截图 `tmp/stage9-browser/aha_bua_29232_1794914291697177100.png`。
- 手机 390×844：侧栏隐藏、移动导航显示，单栏，`scrollWidth == clientWidth`；截图 `tmp/stage9-browser/aha_bua_29076_1794914302495880500.png`。

## 5. 三态口径

必须区分以下三种情况，不能用“参数校验通过”替代“任务一定可执行”：

| 状态 | 含义 | 用户可见表达 |
| --- | --- | --- |
| 工具执行失败 | 工具本身未完成（模型不可用、权限拒绝、场景不允许、服务异常） | `model_unavailable` / HTTP 403 / 明确错误消息；不得声称已校验或已执行 |
| 校验执行成功但参数无效 | 工具正常返回，但参数文本类型、枚举或范围不合法 | `PARAMETER_TYPE_INVALID` / `PARAMETER_VALUE_INVALID` / `PARAMETER_OUT_OF_RANGE`；说明“参数未通过，未创建任务” |
| 运行环境尚未验证 | 参数合法，但 API 进程无法确认 Worker、GPU 或执行器配置 | `worker_unknown` / `WORKER_CONFIGURATION_UNKNOWN`；说明“需 Worker 校验，不代表能启动” |

因此在预检场景中，`valid=true` 只代表配置策略通过；`executorConfiguration=worker_unknown` 仍必须作为警告展示。只有 Worker 侧实际校验并完成领取后，才能讨论任务是否可执行。

## 6. 测试与门禁

| 项 | 结果 |
| --- | --- |
| 聚焦测试 | 23/23 通过：`LddConfigurationPolicyTest` 7、`AssistantOutboundPrivacyTest` 4、`ReportSummaryToolTest` 1、`TaskSummaryToolTest` 3、`ValidateLddParametersToolTest` 2、`PreDefenseTaskCommandServiceTest` 6 |
| 全量后端门禁 | `mvn -B -f migration-backend/pom.xml verify -Pmysql-it`：**337 单测 + 123 IT，0 失败、0 错误、0 跳过** |
| 前端构建 | `npm run build` 成功（仅有既有 chunk size 警告） |
| 第一次全量失败原因 | 测试夹具仍用旧 `badnets`（策略/种子为 `badnet`），且本机 MySQL `max_connections=151` 被并发 IT 耗尽；已将夹具统一为 `badnet`，并将本机上限临时调至 500 后重跑 |
| 日志 | `tmp/stage9-focused-tests.log`、`tmp/stage9-full-verify-retry.log`、`tmp/stage9-frontend-build.log` |

本轮修改后重新打包 `migration-backend/target/security-model-backend-0.1.0-SNAPSHOT.jar` 并重启 8100 后端，浏览器证据使用新 jar。

## 7. 复现命令

```powershell
# 后端聚焦测试
mvn -B -f migration-backend/pom.xml test `
  "-Dtest=LddConfigurationPolicyTest,AssistantOutboundPrivacyTest,TaskSummaryToolTest,ReportSummaryToolTest,PreDefenseTaskCommandServiceTest,ValidateLddParametersToolTest"

# 全量门禁
$env:MYSQL_IT_PASSWORD='123456'; $env:REDIS_IT='true'; $env:RABBIT_IT='true'; $env:RABBIT_IT_PORT='5673'
mvn -B -f migration-backend/pom.xml verify -Pmysql-it

# 前端构建
cd migration-frontend; npm run build

# 脚本化假模型
python scripts/dev/assistant_browser_fake_model.py --port 8090
```

隔离后端启动参数必须包含隔离库、8100 端口、`rabbit/api`、本地假模型地址、非空 `api-key` 和至少 32 字节的 `SECURITY_MODEL_JWT_SECRET`。假模型请求体追加到 `tmp/browser-fixture/model-requests.jsonl`，不写入真实凭据。

## 8. 未执行边界

- 未调用真实外部模型；本轮“模型证据”与“模型故障”均由本地脚本化 OpenAI-compatible 假模型验证，不能写成真实模型验收通过。
- 未运行真实 LDD/GPU；确认卡片只验证出现，未点击“确认执行”，未领取 GPU 槽。
- 未迁移或操作原 `eladmin`；浏览器使用 `security_model_browser` 隔离克隆库。
- 未验证生产部署、长期故障压测、真实导出中断或跨机 Worker。
- R7 当时 `taskIdText` 是兼容性修正；阶段 1 已把 HTTP 边界的 `*Id` 字段统一为十进制字符串，并保留数字入参兼容，其他接口不再待决策，见 §9。
- 支持集合以种子 `badnet` 为准；历史测试夹具中的 `badnets` 已改为 `badnet`。未审计隔离库之外是否存在使用旧别名的任务配置；若存在，需单独做数据核对/迁移，不在本轮操作原 `eladmin` 范围。

## 9. 阶段 1 ID 与确认收口后续（2026-11-17）

本节记录 R7 收口之后新增的事实，覆盖本文 §8 中“未点击确认”的 R7 当时状态。

- HTTP 边界的 `*Id` 字段（含嵌套 `dataset`/`algorithm`）输出十进制字符串；助手工具入参同时接受 JSON 整数和 1–19 位十进制字符串，超长/非数字返回 `ASSISTANT_TOOL_ARGUMENTS_INVALID`。
- 聚焦 `AssistantConfirmationEndToEndIT` 25/25、`OwnershipEndToEndIT` 10/10、`OwnershipEndToEndCrossReadIT` 4/4，共 39 项通过；覆盖超大 ID 列表/详情/预览/确认/重复确认/越权、过期确认 410、超大导出 ID 查询与下载越权。全量门禁为 **343 单测 + 126 IT，0 失败/0 错误/0 跳过**。
- 浏览器在隔离库、本地假模型和短协议桩下实际点击“确认执行”并启动任务 `9007199254740993`，数据库状态为 `running`。重新打包后的页面点击同样发出 `POST /api/assistant/confirmations/confirm`，因当前用户已有 2 个 running 任务返回 400 `TASK_USER_QUOTA_EXCEEDED`，任务未被误启动。
- 重复确认返回 `confirmationReplayed=true`；过期凭据返回 410 `ASSISTANT_CONFIRMATION_EXPIRED`；另一用户确认返回 403 `ASSISTANT_CONFIRMATION_FORBIDDEN`。这些边界由隔离库直连 API 与聚焦 IT 覆盖，未在 UI 中逐项复现。
- 跨轮会话已明确限制：`summaryJson` 只用于会话列表/历史恢复/审计/配额与清理，不进入模型消息；前端“继续会话”只恢复 `sessionId` 绑定。
- 仍未运行真实 LDD/GPU、未调用真实外部模型、未执行部署环境验收。
