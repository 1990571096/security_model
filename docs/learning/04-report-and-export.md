# 04 检查报告和导出

**目标**是分辨哪些东西来自执行器，哪些由后端用结果组装。

1. 在第一课的任务详情记录一个报告字段和当前进度。真实运行时再记录一份日志及其 offset。打开详情时 `openDetail` 加载报告、审计和日志。`refreshDetail` 在运行期间每 3 秒刷新任务详情和日志。任务进入终态或切换运行时自动刷新报告，也可以用“刷新报告”主动查询。短桩可能在打开详情前结束，可用源码追踪这些请求。[任务中心](../../migration-frontend/src/views/TaskCenterView.vue)
2. 顺着 `GET /api/pre-defense/report/{taskId}` 与 `GET /api/pre-defense/logs/{taskId}` 进入同一个 Controller。两条读取路径都需要验证任务可读性。找 `getReport` 用了哪些结构化数据，再找 `getLogs` 怎样处理已读 offset 和日志文件变化。[报告接口](../../migration-backend/src/main/java/com/securitymodel/interfaces/web/PreDefenseReportController.java) · [报告查询](../../migration-backend/src/main/java/com/securitymodel/application/report/PreDefenseReportQueryService.java) · [日志读取](../../migration-backend/src/main/java/com/securitymodel/application/report/PreDefenseLogQueryService.java)
3. 搜索 `exportSubset`，写下提交导出、轮询 `exportId`、下载三个步骤。在导出服务搜 `selection_kind` 和 `selection_files`，看看 `submit` 怎样确认可选比例。再进异步执行器，看它何时打开所选索引文件并校验数据根。短桩产物仅有 marker，可以用它推演提交阶段会在哪里提前返回。[导出服务](../../migration-backend/src/main/java/com/securitymodel/application/export/CleanSubsetExportService.java) · [导出执行器](../../migration-backend/src/main/java/com/securitymodel/application/export/CleanSubsetExportExecutor.java)
4. 在已有真实 LDD 产物的环境中，确认结果包含 `retained_clean_subset`、所选比例对应的文件和可用数据根，再用某个已有比例走下载。只有短桩时，打开测试看恢复和导出条件。

```powershell
mvn -B -f migration-backend/pom.xml '-Dtest=CleanSubsetExportServiceTest,CleanSubsetExportExecutorTest,ExportRecoveryRunnerTest' test
```

| 对象 | 标识 | 主要用途 |
| --- | --- | --- |
| LDD 任务 | `taskId` | 用户可操作的实验记录 |
| 运行产物 | `taskId`、`runId`、`attempt` | 保存某轮请求、进度、文件与结果 |
| 报告 | `taskId` | 展示后台当前结果、快照、指标及导出信息 |
| 日志 | 文件标识与 offset | 查看执行细节和失败信息 |
| 清洗 ZIP | `exportId` | 下载由另一条异步任务生成的数据 |

## 推演 ZIP 丢失

假设 `42` 已成功，隔离比例 `10` 已经生成过 ZIP，导出 ID 是 `901`。下面从下载推演到再次提交。测试中可以找到原始批次缺失的 `listsOnlyPresentIndexesAndRequiresAllTrainingBatches`，以及 ZIP 消失后两次提交只派发一次的 `reusesAvailableZipAndDispatchesOnlyOneRebuildWhenItDisappears`。[导出服务测试](../../migration-backend/src/test/java/com/securitymodel/application/export/CleanSubsetExportServiceTest.java)

| 操作 | 先预测，再看测试或源码 |
| --- | --- |
| 请求下载 `901`，ZIP 已不在磁盘 | 返回 `410 EXPORT_FILE_UNAVAILABLE` |
| 索引和原始批次仍在，再提交 `42` 的同一比例 | 复用 `901`，重置为 `pending` 并派发 |
| 此时另一个请求也提交同一比例 | 复用已经待执行的 `901` |
| 再查任务 `42` | 仍是 `success`，训练轮次保持不变 |

如果缺了源训练批次或筛选索引，先修复对应输入，再决定如何恢复导出。应用重启时，API 角色扫描开放导出，将遗留任务收敛为失败状态，后续可重提。

[下一课：可选组件与测试](05-extensions-and-tests.md)
