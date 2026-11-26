# 实验助手改版：实现、演示与验收记录

**交付日期**：2026-11-25  
**定位**：技术栈扩展与可复现演示，不把实验助手当作新的任务执行入口。  
**方案依据**：[28-experiment-assistant-development-plan.md](28-experiment-assistant-development-plan.md) 第 5、6 节。

## 1. 结论

上一轮“双报告对照主链路已跑通”只能作为技术演示，不能整体验收 EA-01 至 EA-07。本轮按评审反例收紧了证据链，完成了三项 P1 和两项 P2 修复，并用真实报告、隔离库、浏览器和聚焦测试重新验证。

- **P1-1 运行绑定**：报告入口、候选、会话、比较和取证统一携带 `taskId/runId/attempt`。所选运行与任务当前尝试不一致时，保留原选择、停止差值计算，并提示重新选择。
- **P1-2 草案基线**：草案从指定尝试的报告 `effective_config` 生成，不再从客户端 `customParams` 猜测；服务端最终配置预览逐字段比较，只有 `num_epochs` 变化才标记可复现。
- **P1-3 笔记证据**：保存时忽略客户端快照，由服务端校验运行引用并生成 `server_fact` 快照；查看、列表和 Markdown 导出按当前权限逐引用复核。
- **P2 子集状态**：区分样本达标、可提交导出、ZIP 可下载；复用业务层 CIFAR-10 范围、索引和文件检查。
- **P2 候选与引用**：候选选择用结构化 `selection` 传递原比例，不再从正文猜任务 ID 后固定 30%；引用可按 `targetPath` 打开对应报告。

本轮没有新增写任务能力：草案工具仍返回 `sideEffect=none`、`confirmationCreated=false`。唯一新增的 `pending` 任务由演示人员在现有“新建防御任务”页面点击创建，未自动启动或写调度。

`ee0cef7` 不是助手改版的交付坐标；本轮独立提交见第 8 节。

## 2. 环境与演示数据

### 2.1 运行环境

| 项目 | 本次值 |
| --- | --- |
| 仓库 | `F:\Study\postgraduate\毕业论文\security_model` |
| 分支 / 修复前 HEAD | `feature/r3-dispatch-idempotency` / `ee0cef7` |
| 后端 | `migration-backend`，端口 `8102`，使用最新修复 jar |
| 前端 | `migration-frontend/dist`，静态端口 `8015`，代理到 `8102` |
| 数据库 | 隔离 MySQL `127.0.0.1:3308`，库 `security_model_delivery_final` |
| 模型 | `assistant.model.enabled=false`，用于确定性规则与降级验证 |
| 浏览器 | 本机 Chromium，桌面视口和 CDP 390px 视口 |

启动方式沿用仓库既有隔离栈：先停 `8102` 再打包，避免运行中的 jar 无法 repackage；前端 `npm run build` 后由 `scripts\dev\frontend-static-server.mjs` 托管。

### 2.2 演示运行

| 角色 | 任务 | 运行 | 尝试 | 状态 |
| --- | --- | --- | --- | --- |
| 主运行（正式 5 epochs） | `2122901116713205761` | `37204a64-2b3d-4c72-8cb1-5b7bc2617dd9` | 1 | success |
| 对照运行（1 epoch） | `2122913324293771266` | `af5f081a-6661-462c-b0bc-c70ea003597a` | 1 | success |
| 失败夹具 | `2124000000000000002` | `ea-failed-run-0001` | 1 | failed |

实测数值：

| 保留比例 | 主运行纯度 | 主运行样本 | 对照纯度 | 对照样本 | 纯度差 | 样本差 |
| --- | --- | --- | --- | --- | --- | --- |
| 30% | 100.0% | 15000 | 100.0% | 15000 | 0 个百分点 | 0 |
| 60% | 99.99% | 30000 | 99.98% | 30000 | 0.01 个百分点 | 0 |

数据来源：

- 正式 5 epochs：`F:\security-model-data\ldd-acceptance-20261118\formal-freeze-20261118\formal-run-20261118\report-final.json`
- 1 epoch：仓库 `tmp\ldd-short-run-real2-1epoch\run-summary.json` 的 `report.taskInfo` / `report.cleaningResult`
- 隔离库种子：`tmp\seed-experiment-assistant-demo.sql`
- 失败日志夹具：`tmp\ea-fixtures\failed-encoding\attempt-1\execution.log`

**数据边界**：这些任务是从真实报告导入隔离测试库的只读样本，不是生产库中的既有任务。报告只记录数据目录修改时间 `1750334641936`，没有内容哈希，因此界面和回答均把数据版本标为“未知”，不能把两次差异归因于训练轮数。

### 2.3 演示后的状态还原

- 主任务 `owner_user_id=1`、`create_by=admin` 已恢复。
- 主任务 `cleaning_result.effective_config.dataset` 已恢复为 `cifar10`。
- 主任务 `current_run_id` / `current_attempt` 已恢复为原运行。
- 主任务 `hyper_id` 在模板基线测试后已恢复为 `NULL`。
- 30% 导出 ZIP 在“文件失效”反例后已恢复。
- 新增反例数据保持原状：已撤权笔记 `2125399022133366786` 仍为 `revoked`；用户创建的 `pending` 任务 `2125401818991755265` 保留，用于复验表单最终配置。

## 3. 证据链修复

### 3.1 P1-1：对照绑定到具体运行

**实现**

- 新增 `ExperimentSelection`，`POST /api/assistant/chat` 支持 `experimentContext.runs[{taskId,runId,attempt}]` 和 `ratio`，最多 3 个运行。
- `AssistantSessionService` 持久化 `runs`、`primaryRunId`、`primaryAttempt`；只有 `status=ok` 的比较结果才覆盖已选运行，`stale` 不覆盖。
- `ExperimentComparisonTool` 读取报告时同时校验两层绑定：
  1. 报告运行是否等于任务当前 `currentRunId/currentAttempt`；
  2. 报告运行是否等于用户选择的 `runId/attempt`。
- 任一运行不满足时返回 `data.status=stale`、`deltas=[]`，并附 `RUN_BINDING_STALE` 或 `RUN_BINDING_MISMATCH`；`nextActions` 增加 `choose_run`。
- 回答开头明确“原选择保留，已停止计算差值，请重新选择”。
- 候选补齐时先绑定主运行，再列出候选；候选响应带 `selection {mode,ratio,missing,primaryTaskId,primaryRunId,primaryAttempt}`。
- `ReportSummaryTool`、`MyTaskListTool`、`TaskProgressQueryService.OwnTask` 均输出 `runId/attempt`。

**验证**

- 显式 60% 对照：`runs.bindingState=bound`，主 99.99%/30000、对照 99.98%/30000，差 0.01 个百分点/0 个样本；证据 `tmp\ea-review-compare-60.json`。
- 尝试变化反例：临时把主任务当前运行改为 `ea-stale-20261125` / attempt 2 后追问，回答保留原选择、`status=stale`、`deltas=[]`、`RUN_BINDING_STALE`；`GET /api/assistant/sessions/30` 仍保留 ratio 60 和原 runs。证据 `tmp\ea-review-stale-followup.json`。
- 刷新会话追问：`AssistantSessionServiceTest` 1 项通过，确认 stale 比较不覆盖原运行，重载会话后仍保留 60% 与原绑定。
- 恢复主任务运行后，后续 60% 和候选选择均重新正常计算。

### 3.2 P1-2：草案从真实生效配置生成

**实现**

- `DraftNextRunTool` 不再读取客户端 `customParams`，改为读取 `PreDefenseReportQueryService` 的 `cleaningResult.effective_config`；支持可选 `runId/attempt`。
- 新增错误码：`BASELINE_CONFIG_UNAVAILABLE`、`BASELINE_EPOCHS_MISSING`、`BASELINE_RUN_STALE`。
- 草案删除 `data_path` 后交给 `ValidateLddParametersTool`，把返回的 `normalizedParameters` 当作“新建表单服务端最终配置预览”，逐字段比较并输出：
  - `reproducible`
  - `deltaCheck.onlyNumEpochsChanged`
  - `deltaCheck.changedFields`
  - `deltaCheck.omittedEnvironmentFields`
- `formPath` 携带 `prefillRunId/prefillAttempt`。
- 前端 `TaskCenterView` 新增 `prefillHyperId`，回填使用草案或任务真实 `hyperId`；算法 watcher 在 `prefillHold` 期间保留该值，不再固定 `hyperId=0`。

**验证**

- 无模板基线：任务 `2122901116713205761` 的草案 `before=5`、`after=6`、`reproducible=true`、`onlyNumEpochsChanged=true`、`changedFields=["num_epochs"]`。
- **带模板基线反例**：临时把主任务 `hyper_id` 绑定到模板 15（`LDD (VaB) - CIFAR10 SIG`），草案返回 `hyperId=15`；服务端 `normalizedParameters` 只有 `num_epochs` 由 5 变 6，`validation.hyper.name` 为同一模板，`deltaCheck.omittedEnvironmentFields=["data_path"]`。证据 `tmp\ea-review-draft-template3.json`。
- 浏览器点击“打开参数草案”回填后，超参模板显示为 `15`，自定义参数 JSON 中 `num_epochs=6`；用户点击“创建任务”得到 `pending` 任务 `2125401818991755265`，库中 `hyper_id=15`、`custom_params.num_epochs=6`，其余字段与基线一致。
- 任务没有被助手自动提交；调用链无确认凭据、无调度写入。
- 首次实测曾发现问句“训练轮数 5，生成增加到 6 轮”被旧规则解析成 `5→5`；已改为优先解析“增加到/改为/调到/设为”后的目标数，再解析箭头和 `num_epochs=`，最后才是带“轮/epochs”的显式值。修复后 2 项解析测试通过。

### 3.3 P1-3：私有笔记绑定报告证据

**实现**

- 保存接口完全忽略客户端 `snapshot`，只接受 1–3 个 `runRefs`，且比例必须一致。
- 每个引用执行 `requireReadable`，读取报告并校验 `runId/attempt` 与请求一致、且为任务当前尝试；由服务端生成 `snapshotSource=server_fact` 快照。
- 两个以上运行复用 `ExperimentComparisonTool` 生成 `comparison`；比较非 `ok` 时拒绝保存；快照超过 20KB 报错。
- 查看、列表和 Markdown 导出调用 `revalidate`：逐引用重新鉴权、读报告、比对保存 run/attempt 与当前尝试。任一不可用时返回 `redacted=true` 空快照，并输出 `accessState=verified/partial/revoked`、`unavailableRefs`、`accessNotes`。
- 旧笔记没有 `snapshotSource=server_fact` 时标为 `legacy_client`，隐藏原快照并要求重新保存。

**验证**

- 客户端伪造 `snapshot.cleanPurityPercent=999`：响应 `noteId=2125398890386083842`、`snapshotSource=server_fact`、`accessState=verified`；服务端快照为 100.0/15000，不含 999。证据 `tmp\ea-review-note-create.json`。
- 保存后撤权：`test` 账号在任务可读时保存笔记 `2125399022133366786`，随后恢复主任务所有权；`test` 再读得到 `accessState=revoked`、`snapshot.redacted=true`、`unavailableRefs=1`，Markdown 不含 100/15000 指标。证据 `tmp\ea-review-note-revoked.json`、`tmp\ea-review-note-revoked-md.json`。
- 无权引用保存被拒绝：`ExperimentNoteServiceTest` 3 项通过，覆盖伪造指标被忽略、撤权后脱敏、保存时无权引用拒绝。
- 浏览器笔记抽屉：编辑 `2125398890386083842` 的标题为“EA review 编辑后的笔记”，备注改为“编辑后备注：确认 30% ZIP 可下载，再比较 60%。”，保存后列表立即显示新标题与备注。

### 3.4 P2：子集导出三状态

**实现**

- `CleanSubsetExportService.availability(task,ratio,export)` 复用既有 `describe` 资格检查和 `availableZip` 文件检查，输出：
  - `eligible/canSubmit/downloadReady`
  - `exportState/exportLabel`
  - `fileState/reason`
- `ExperimentSubsetTool` 注入该服务，每档同时输出 `eligibleForExport/canSubmitExport/downloadReady/fileState/exportReason`；`data` 汇总 `eligibleExportCount/downloadReadyCount`。
- 回答明确“样本达标 ≠ ZIP 已生成”。

**验证**

- 40% 无导出记录：`eligibleForExport=true`、`canSubmitExport=true`、`downloadReady=false`、`exportState=not_requested`。
- 30% 真实导出成功：`exportId=2`，`downloadReady=true`、`fileState=readable`、`exportState=success`、`canSubmitExport=false`。
- 删除对应 ZIP 后：`downloadReady=false`、`fileState=missing_or_invalid`、`exportState=success`、`canSubmitExport=true`，提示“导出记录成功但 ZIP 已失效，可重新生成”；随后恢复 ZIP。
- CIFAR-100 反例：临时把 `effective_config.dataset` 改为 `cifar100`，所有档位 `eligibleForExport=false`、`canSubmitExport=false`、`ready=0`，提示“当前数据集 cifar100 不在导出支持范围（仅 CIFAR-10）”。证据 `tmp\ea-review-cifar100-new.json`；随后恢复 `cifar10`。
- 真实下载：`exportId=2` 下载 45,275,996 B，ZIP 15004 entries，含 `metadata.json`、`selected_indices.npy`、`selected_indices.csv`、`images/*.png`；样例首图 `images/00041368_label_1.png` 3091 B。副本 `tmp\ea-review-export-30.zip`。

### 3.5 P2：候选保留原比例与引用跳转

**实现**

- `AssistantView.chooseTask` 接收整个 turn 和任务对象，读取 `evidence.taskList.selection`；比例优先使用结构化 `selection.ratio`，其次才回退到原问题百分比。
- 主运行和基线都带 `runId/attempt`，不再从正文提取主任务 ID 后固定 30%。
- 引用条目按 `citation.targetPath` 增加“打开对应报告”按钮。
- `TaskCenterView.openExperimentAssistant` 入口 URL 带 `runId/attempt`。

**验证**

- 桌面和 CDP 390px 视口均输入“对比任务 #2122901116713205761，隔离比例 60%”，点击 `LDD short-cycle ... #2122913324293771266` 后，回答仍按 60% 对齐，未回退到 30%。
- 展开“任务 #2122901116713205761 报告”后点击“打开对应报告”，跳转 `/tasks?taskId=2122901116713205761`，任务详情与报告区域可见。
- 390px 视口下 `document.documentElement.scrollWidth=390`，助手、候选列表、引用和任务详情均无横向溢出。

## 4. 可复制路径与实测结果

| 目的 | 可复制输入 / 操作 | 实测结果 | 证据 |
| --- | --- | --- | --- |
| 显式 60% 对照 | `对比任务 #2122901116713205761，隔离比例 60%。` | 主 99.99/30000、对照 99.98/30000，差 0.01/0，绑定 `bound` | `tmp\ea-review-compare-60.json` |
| 尝试变化追问 | 将主任务当前运行临时改为 `ea-stale-20261125` / attempt 2 后追问 `只看 30%` | `status=stale`、`deltas=[]`、保留原选择、提示重选 | `tmp\ea-review-stale-followup.json` |
| 会话刷新 | `GET /api/assistant/sessions/30` | ratio 60、原 runs 仍在 | `tmp\ea-session-tests.log` |
| 无模板草案 | `基于任务 #2122901116713205761 的训练轮数 5，生成增加到 6 轮的下一轮草案` | `5→6`，只有 `num_epochs` 变化 | `tmp\ea-review-draft2.json` |
| 带模板草案 | 主任务临时绑定模板 15 后重复上一问 | `hyperId=15`，最终配置只有 `num_epochs` 变化 | `tmp\ea-review-draft-template3.json` |
| 草案回填并提交 | 点击“打开参数草案” → 修改任务名 → “创建任务” | `pending` 任务 `2125401818991755265`，`hyper_id=15`，`num_epochs=6` | 浏览器 + 隔离库查询 |
| 客户端伪造指标 | `POST /api/experiment-notes` 带 `snapshot.cleanPurityPercent=999` | 服务端快照 100.0/15000，不含 999 | `tmp\ea-review-note-create.json` |
| 保存后撤权 | 恢复任务所有权后 `test` 读笔记和 Markdown | `revoked`、`redacted=true`、无指标 | `tmp\ea-review-note-revoked*.json` |
| 无导出记录 | 40% 子集筛选 | `eligible=true`、`canSubmit=true`、`downloadReady=false` | `tmp\ea-review-subset.json` |
| 真实 ZIP | 30% 导出成功并下载 | 45,275,996 B，15004 entries | `tmp\ea-review-export-final.json`、`tmp\ea-review-export-30.zip` |
| ZIP 失效 | 临时删除导出 ZIP 后重查 | `downloadReady=false`、`fileState=missing_or_invalid`、可重新生成 | `tmp\ea-review-export-invalid2.json` |
| CIFAR-100 | `至少 2 万张保留样本，哪些子集可以导出？` | 全部不可导出，提示仅支持 CIFAR-10 | `tmp\ea-review-cifar100-new.json` |
| 候选保留 60% | 60% 提问后点击基线候选 | 回答仍按 60% 对齐 | 浏览器桌面 + 390px |
| 引用跳转 | 展开引用 → “打开对应报告” | 打开 `/tasks?taskId=2122901116713205761` | 浏览器桌面 + 390px |
| 笔记改名/备注 | 笔记抽屉 → 编辑 → 保存 | 标题、备注即时更新 | 浏览器桌面 |

## 5. EA 逐项结果

| ID | 结果 | 已执行证据 | 未执行或限制 |
| --- | --- | --- | --- |
| EA-01 | **通过** | 60% 和 30% 对照值与真实报告一致；显式 `runs`、候选、会话和报告均绑定 `taskId/runId/attempt`；尝试变化返回 `stale`、`deltas=[]` 并保留原选择；刷新会话后仍保留 60% 与原绑定 | 数据版本仍只有目录时间，无内容哈希；下游准确率/攻击成功率未采集 |
| EA-02 | **通过** | 样本达标、可提交、可下载三状态分开；CIFAR-100 不可导出提示真实；30% 导出真实 ZIP 可下载（45,275,996 B / 15004 entries）；成功记录对应 ZIP 失效可重生成 | 只验证真实 CIFAR-10 30% 档位；未逐个档位执行导出 |
| EA-03 | **通过** | 主/对照同数据集、同种子、同保留比例；训练轮数 5 vs 1；2 条 `DATA_VERSION_CONTENT_UNKNOWN` 缺口；多变量单测通过 | 缺指标任务只由单测覆盖，未在浏览器导入第二份缺指标任务 |
| EA-04 | **通过** | 显式双运行、60% 候选、会话持久化、尝试变化和刷新会话追问均验证；候选响应携带结构化 selection；无唯一指代仍返回候选 | “加上上次那份”仍按当前已选基线解释，不新增第三个运行 |
| EA-05 | **通过** | 编码故障夹具 `2124000000000000002` 的阶段、泛化错误码、证据缺口和原始行不泄露均保持；相关诊断与解析测试通过 | 配置缺失与证据冲突仅由既有诊断测试覆盖 |
| EA-06 | **通过** | 草案从指定尝试 `effective_config` 生成；模板 15 基线只改 `num_epochs`；浏览器回填真实 `hyperId`，用户提交得到 `pending`；笔记改名、备注编辑已走通 | 缺少基线时的三个错误码由单测覆盖；未在浏览器制造缺基线任务 |
| EA-07 | **通过** | `test` 读管理员任务 403 `ACCESS_DENIED`；读管理员笔记 404；保存后撤权笔记返回 `revoked` 且 Markdown 无指标；伪造 999 指标被服务端忽略 | 权限撤销只测了单次复权；未做多用户并发撤销 |
| EA-08 | **未执行** | — | 可选观察项，未做与手工流程的耗时/页面切换对照 |

## 6. 测试与门禁

### 6.1 聚焦测试

本轮最后修改后通过的聚焦测试：

- `AssistantChatServiceTest` 12 项
- `DraftNextRunToolTest` 3 项
- `ExperimentComparisonToolTest` 5 项
- `ExperimentSubsetToolTest` 1 项
- `TaskFailureDiagnosisTest` 6 项
- `CleanSubsetExportServiceAvailabilityTest` 1 项
- `ExperimentNoteServiceTest` 3 项
- `AssistantSessionServiceTest` 1 项
- `ExperimentAssistantEpochParsingTest` 2 项

合计 34 项。日志：`tmp\ea-review-focused-tests.log`、`tmp\ea-note-tests.log`、`tmp\ea-session-tests.log`、`tmp\ea-epoch-test.log`。

### 6.2 后端完整门禁

```text
mvn -B -f migration-backend/pom.xml verify -Pmysql-it
隔离 MySQL：127.0.0.1:3308 / security_model_delivery_final
```

```text
单元测试：381，失败 0，错误 0，跳过 0
隔离 MySQL 集成：136，失败 0，错误 0，跳过 10
结果：BUILD SUCCESS
```

跳过的 10 项为 RabbitMQ / Redis 相关集成测试；本轮未启动这两个外部组件。Maven 进程退出码 0。日志：`tmp\ea-backend-verify-20261125-rerun.log`。

### 6.3 前端

```text
migration-frontend: npm run build
构建成功；AssistantView、TaskCenterView 新产物由 8015 托管
```

日志：`tmp\ea-frontend-build.log`。

### 6.4 浏览器

- 桌面：报告 → 实验助手 → 60% 对照 → 候选 → 引用跳转 → 笔记编辑 → 草案回填 → 用户创建 pending。
- 390px：同一助手主链路、候选、引用和报告页；页面宽度与滚动宽度均为 390，无横向溢出。

## 7. 已知限制

1. 演示任务由真实报告导入隔离库，不能据此声称生产库已有这些任务。
2. 数据版本只有目录修改时间，没有内容哈希；“同一数据版本”不能确认。
3. 单任务内 30% vs 60% 的横向对照由 `select_isolation_subset` 展示；跨任务差值只接受 2–3 个运行。
4. 模型自然语言表现未用真实外部模型验收；本次为 `assistant.model.enabled=false` 的确定性降级演示。
5. 旧客户端快照笔记不迁移，统一标 `legacy_client` 并隐藏原快照，需用户重新保存。
6. 390px 通过 CDP 设备指标覆盖验证，不是真实手机硬件或移动浏览器。
7. EA-08 未执行。

## 8. 交付坐标与本轮边界

- 独立提交：本轮 `feat(assistant): 收紧实验助手证据链与用户流程`；精确哈希见最终交付答复或 `git log -1 --oneline`。`ee0cef7` 不是助手改版坐标。
- 提交范围只包含实验助手改版相关后端、前端、测试和本文档；工作区中既有无关改动不纳入该提交。
- 本轮未执行 `reset`、`clean`、`checkout`，未改写既有本地改动。
- 已实测：P1-1/P1-2/P1-3、P2 子集三状态、候选 60%、引用跳转、笔记编辑、真实 ZIP 下载、390px 主链路。
- 未执行：真实 GPU 重跑、真实手机硬件、EA-08 对照；RabbitMQ/Redis 相关集成测试如门禁报告跳过，则以门禁报告为准。

## 9. 交接检查

- 隔离库新增了 `pending` 草案任务 `2125401818991755265`、旧草案任务 `2125161109211090946`、真实导出记录和两份笔记；这些只在本机 `3308` 验证库中存在。
- `2125399022133366786` 为撤权反例，保持 `revoked`；`2125398890386083842` 为服务端事实笔记，已编辑标题和备注。
- 复现前先确认 `8015` 静态服务、`8102` API、`3308` MySQL 对应同一套库和口令。
- 若需重跑模板基线测试，临时设置主任务 `hyper_id=15` 后必须恢复为 `NULL`，并确认 `effective_config.dataset` 仍为 `cifar10`。
