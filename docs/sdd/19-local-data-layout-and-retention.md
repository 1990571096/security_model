# 本地数据目录布局与文件保留策略（R0 草案）

版本：2026-11-13；状态：**草案 / 待确认**。目录改为受控根目录属于 R1 实施项，本轮只冻结布局与保留规则，不改代码、不搬动现有文件。

适用范围：单机部署（RTX 2060 6GB / 内存 16GB）。数据集与产物都必须留在本机受控目录，不上传对象存储；分布式共享存储只在可选的 R4/R8 中评估。

## 1. 当前事实（代码锚点）

| 项 | 当前实现 | 锚点 |
| --- | --- | --- |
| 执行工作目录 | `${java.io.tmpdir}/security-model-executor`（Windows 即用户临时目录），可用 `security-model.executor.python.workspace` 覆盖 | `ExecutionFingerprintService` 构造参数默认值 |
| 任务目录 | `<workspace>/pre_defense-<taskId>/attempt-<n>/` | `PythonLddTaskExecutor.createContext()` |
| 任务目录内文件 | `request.json`、`result.json`、`stdout.log`、`stderr.log`、`progress.json` | 同上 |
| 算法产物根 | 默认 `<attempt>/artifacts`；若设置 `SECURITY_MODEL_VAB_RESULT_ROOT`，改为 `<该根>/<时间戳>-<算法>-<任务名>/` | `algorithm_entry.py` 的 `task_output_root()` |
| 产物内容 | `generated_<source>.py`、`execution.log`、`*.txt` 日志、`*.png`、`*.pth`、`selected_indices_<ratio>.npy` | 同上 |
| 数据集位置 | `sys_dataset.file_path` 保存的绝对路径（当前种子指向外部 VaB 工程 `.../VaB-main/dataset`） | `security_model_vab_seed.sql` |
| 数据集指纹 | 记录 `realPath`、类型、大小、修改时间；仅对**普通文件**且 ≤1GB 计算 SHA-256，目录不算内容摘要 | `ExecutionFingerprintService.fingerprint()` |
| 导出产物 | `<任务产物根>/clean-subset-exports/clean-subset-ratio-<r>.zip`、`.metadata.json`、`.export.log`，写入时先落 `.part` 再原子改名 | `CleanSubsetExportExecutor` |
| 保留/清理 | **不存在任何自动清理或保留期代码**；日志文件无大小上限 | 全仓库无对应实现 |
| 路径校验 | 产物预览、日志读取、索引、ZIP 下载均做 `toRealPath().startsWith(root)` containment | `PreDefenseReportController`、`PreDefenseLogQueryService`、`CleanSubsetExportService` |

由此产生的三个直接问题：

1. 默认产物根在**系统临时目录**。Windows 清理工具或磁盘压力可能删除它，历史实验报告随后无法打开产物；这也与“数据集和产物保存在受控本地目录”的交付要求不符。
2. 日志与 `stdout.log` 无上限。128 batch / 5 epochs 的 LDD 训练输出虽不大，但长期多任务会持续占用磁盘，且失败现场无法按任务限额治理。
3. 任务目录按 `taskId` 而非按用户组织，**归属只可能来自数据库**。这意味着 R1 的隔离必须在服务层完成，不能靠目录名或文件系统 ACL 兜底。

## 2. 目标目录布局（建议）

```text
SECURITY_MODEL_DATA_ROOT            (建议：F:\security-model-data，仓库之外)
├── datasets/                       # 只读共享，管理员登记
│   ├── cifar10-vab/                # 对应一个 sys_dataset.file_path
│   └── ...
├── workspace/                      # 执行工作目录（替代系统临时目录）
│   └── pre_defense-<taskId>/
│       └── attempt-<n>/            # request/result/stdout/stderr/progress
│           └── artifacts/          # 算法产物：日志、png、pth、selected_indices_*.npy
│               └── clean-subset-exports/   # ZIP + metadata + export.log
└── exports/                        # 可选：把导出 ZIP 移出任务目录，便于按保留期清理
```

约束：

- 所有根必须是**绝对路径**，且位于仓库与系统临时目录之外；相对路径或 `%TEMP%` 下的根在启动时直接拒绝（R1 实施，避免半配置状态）。
- 数据集目录建议保持**只读**（操作系统 ACL 只给运行账号读权限），平台只登记路径，不写入数据集目录。
- 任务目录保持 `taskId` 命名，不改为 `userId/taskId`：改名会破坏已有产物路径与历史报告；用户隔离由服务端 owner 校验保证（见 [18-multiuser-ownership-and-resources.md](18-multiuser-ownership-and-resources.md)）。
- 若启用 `SECURITY_MODEL_VAB_RESULT_ROOT`，该根必须落在 `SECURITY_MODEL_DATA_ROOT` 之下，避免出现两套互不相干的产物根。

## 3. 文件保留策略（草案）

| 对象 | 当前行为 | 建议保留期 | 清理前置条件 | 归属迭代 |
| --- | --- | --- | --- | --- |
| 数据集目录 | 不清理 | 永久（人工停用保留元数据） | 无任务引用且管理员显式确认 | 不自动清理 |
| 成功任务 attempt 目录 | 不清理 | 论文期内永久保留（溯源需要） | 仅管理员显式清理，且该 attempt 不是 `current_attempt` | R2 |
| 失败任务 attempt 目录 | 不清理 | 30 天 | 无运行中/待导出引用；保留最后一次失败现场供排查 | R2 |
| `stdout.log` / `stderr.log` / `execution.log` | 无上限 | 单文件上限（建议 50 MB）+ 超限截断并标记 `truncated` | 截断不丢最终 `result.json` | R2 |
| `progress.json` | 每 attempt 覆盖写 | 随 attempt 目录 | 只作诊断，不作完成依据 | R2 |
| 导出 ZIP | 不清理 | 30 天（可重新生成） | 导出任务不是 `pending/running`；ZIP 可由索引重建 | R2 |
| `selected_indices_*.npy` | 不清理 | 永久（体积小、报告依赖） | 随 attempt 目录一起处理 | R2 |
| 演示/历史数据行 | 不清理 | 标记 `data_origin='demo'` 保留 | 见 18 号文档迁移方案 | R1 |

磁盘预算口径（不写死容量，只写计量方式）：

- CIFAR-10 Python 批次约 150–170 MB；单任务的 `*.png`、`*.pth` 与日志体积需在 R2 首次实测后记录，不得凭经验写进文档。
- 清理策略必须以“实测占用 + 配额阈值”驱动，禁止在未测量时宣称能容纳多少任务。
- 清理必须是显式动作（管理员命令或带审计的定时任务），**不允许在应用启动时自动删除目录**：启动恢复只收敛数据库状态（`EXECUTOR_RESTARTED`），不碰文件。

## 4. 与进度/结果协议的关系

- 目录是协议的载体：`request.json`、`result.json`、`progress.json` 的路径由 Java 执行器决定，Python 侧只按参数写入（见 [07-executor-contract.md](07-executor-contract.md)）。
- 产物根必须由 `result.json.artifacts.path` 声明，下游（报告、预览、导出）只信任这个声明并做 containment；不允许前端传任意路径读取。
- 迁移工作目录前，已存在任务的 `cleaning_result.artifacts.path` 仍指向旧位置。建议 R1 迁移时**保留旧路径可读**（迁移脚本只补 owner 列，不改产物路径），并新增“产物根不可达”的明确状态，而不是让报告页报 500。

## 5. 风险

1. 把 workspace 从临时目录迁到受控根，会让历史任务的日志/产物读取路径失效；必须同时提供“旧路径只读兼容”或“迁移时复制”，不能直接切换。
2. 文件系统 ACL 与数据库 owner 是两套机制，容易只做一套。结论：**以数据库 owner 为唯一授权来源**，ACL 只作为纵深防御。
3. Windows 上仍被 Python 进程占用的文件不能被改名或删除，清理逻辑必须容忍 `WinError 5` 并跳过，不得因此把任务状态改坏。
4. `toRealPath()` containment 在符号链接场景正确，但对“根目录本身被替换”没有防护；清理与迁移动作必须绑定解析后的绝对路径，禁止对拼接字符串操作。
