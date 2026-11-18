# 项目目标与阶段进度说明

更新时间：2026-11-18（真实 1 epoch GPU 验收：任务 `2122913324293771266` 经真实 API、真实 GPU 租约和真实 Python/LDD 子进程成功完成，2 分 25 秒、progress 100、GPU 已释放；此前真实 LDD 验收准备：隔离库 `security_model_ldd_20261118`、数据集 6 路径验证、LDD 算法 14、pending 任务不启动、环境预检脚本与清单；阶段 3 收尾整改：启停脚本身份与排空、liveness/readiness 分离、数据库备份恢复、前端生产静态托管、CI 针对性测试；此前 2026-11-17 阶段 3 运行监控按五组拆分：后端 `GET /api/observability/dispatch` 返回 Outbox / Worker / 消费积压 / GPU 租约明细，前端 `RuntimeMonitorView.vue` 改为五组卡片并区分“不可用/不适用”；新库 `security_model` 已从空库初始化 48 表并以 `DB_NAME=security_model` 启动验证登录、执行池、知识索引 23 文档/574 chunks 与 5 条引用检索；导出恢复用注入的 1 条 `running` + 1 条 `pending` 导出行实测 `scanned=2, recovered=2` 并写审计（故障注入，非真实导出中断）；`sql/eladmin.sql` 的 DROP 行为与文档“全部 IF NOT EXISTS”冲突已修正，新增 `scripts/dev/InitializeSecurityModelDatabase.ps1`。本机 `verify -Pmysql-it` 为 **294 单测 + 106 IT，0 失败/2 跳过**（仅 `RabbitDispatchBrokerIT` 未设 `RABBIT_IT`；Redis 向量/缓存与知识检索 IT 已随 `REDIS_IT=true` 执行），日志 `tmp/stage4-full-verify2.log`；阶段 4 提交 `2783d95`，CI run `44001570656` backend/python/frontend 全绿；功能提交 `f70711f`，CI run `43995845889` backend/python/frontend 全绿；收尾前端标签修复 `2d716a8`，run `43996551480` 同样全绿；阶段 4 工作台已改用 `GET /api/dashboard/overview` 真实聚合并移除 90.0%/4.7% 等演示常量，案例库保留演示占位但加显式警示与“演示”标签，算法详情改按 `algorithmId` 读取真实目录。阶段 5（2026-11-17）把 `security_model_vab_seed.sql` 改为由 `@vab_dataset_root` 注入路径、初始化脚本按 `-VabDatasetRoot` / `SECURITY_MODEL_VAB_DATASET_ROOT` 校验目录后再写数据集；未提供变量时只注册 `LDD (VaB)` 与默认超参模板，不伪造数据集行，重复执行不覆盖既有路径。新库实测 `dataset_count=6`、`parsed_dataset_count=5`（阶段 5 当时；评审收口后 1-4/6 已被历史样例基线重置为 `pending`，5 保持 `parsing`，当前 `parsed_dataset_count=0`）、`sys_algorithm` 14 行、`pre` 阶段 7 个，连续执行两次无新增行，任务创建/详情/删除 API 已走通；同时修复执行器未配置时 `EXECUTOR_NOT_CONFIGURED` 会把 GPU 槽位永久隔离的问题（无脚本时直接释放租约，新增 1 项单测）。本轮仍**未运行真实 LDD/GPU**，不把该失败路径写成执行链路通过。本机 `verify -Pmysql-it` 为 **298 单测 + 106 IT，0 失败/2 跳过**，日志 `tmp/stage5-full-verify.log`（阶段 5 基线）。2026-11-17 评审四项收口后为 **305 单测 + 111 IT，0 失败/2 跳过**（未设 `RABBIT_IT`），日志 `tmp/stage5-closeout-full-verify.log`，收口代码提交 `d025150`；干净库 `security_model_accept_20261117124258` 已用短协议桩完成配置缺失拒绝、权限、Assistant 预览/确认、成功/报告/导出、取消与 retry 预检，真实 LDD/GPU 未运行。2026-11-17 初始化链路再收口基线为 **306 单测 + 111 IT**（同前仅 Rabbit Broker 跳过），RAG/Agent 场景收口后为 **312 单测 + 113 IT，0 失败/0 错误/2 跳过**，新增 6 项场景/评估单测与 `DispatchConfigBoundaryIT` 2 项；`VabSeedPortabilityTest` 6/6、`VabSeedMySqlIT` 5/5。CI run `44033400651`（head `61a32ad`）backend/frontend/python/init-scripts 四个 job 全部 success，其中 `init-scripts` 在 Ubuntu + MySQL 8.4 上跑通保护与完整初始化幂等脚本。旧 `eladmin/`、`eladmin-web/` 已归档到 Tag `migration-r4-verified` 与 `legacy` 分支，并在当前开发分支的独立提交 `7e31b3f` 删除（本地 `main` 尚未合入）；干净 worktree 复跑 306 单测 + 111 IT、guard、完整初始化幂等和 jar 启动健康检查均通过（`tmp/archive-clean-verification.txt`）；归档提交 CI run `44035312196`（head `7e31b3f`）四个 job 全部 success。阶段 5 提交 `18a5a5c`，CI run `44013701508` backend/python/frontend 全绿。此前 2026-11-16 记录：R4 契约已修订并实现；六项评审缺陷已整改；真实 RabbitMQ 4.1.8 + Erlang 27.3.4.18 + 隔离 schema/vhost + 双 JVM + 短 Python 桩已实测 R4-01..R4-08，**R4 整体仍保留长期 DB 故障/多任务退避压测边界，不能写成生产部署通过**；2026-11-16 全量为 **287 单测 + 106 IT**，日志 `tmp/stage2-full-verify-new.log`；run `43846823071` backend/python/frontend 全绿）

2026-11-13 文档与实现迭代：R2 第四切片后实现 R3-A/R3-B 代码（持久调度意图、条件抢占、统一启动恢复、有界 pending 扫描、create/start/retry 请求幂等），并完成 R5 第一批（运行指标、日志关联、Redis 代际缓存、管理员观测接口、Actuator 最小暴露、真实统计页），本机实跑 127 项单测 + 41 项 MySQL IT + 1 项真实 Redis 缓存代际 IT；带意图的真实 JVM 重启、R3-04 崩溃演练、真实 Redis 宕机、生产库建表仍未完成，R3 仍待外部复审。文档中未执行的真实 LDD 与生产库迁移仍保持未验证状态。

当前迁移版已接入 Redis 资源目录缓存，并具备 MySQL 集成测试和对应 CI 工作流配置。
最近一次记录的本机 MySQL profile 验收覆盖原子领取、完成与恢复竞争、孤儿任务恢复边界，
测试使用临时隔离 schema。迁移后端以 MySQL 保存任务状态，通过有界 JVM 线程池调用 Python
LDD 进程；R4 的 RabbitMQ/Outbox/Worker 已用真实 Broker + 隔离 MySQL 双 JVM 执行主要场景，默认仍关闭，未执行项见 §5.17。

## 2026-11-18 真实 LDD 1 epoch 验收（当前事实）

真实 LDD 已通过 1 epoch 短周期验收：任务经真实 HTTP API、真实 GPU 租约和真实 `D:\Anaconda\python.exe` 子进程跑完。该结果是执行链路门禁，**不是论文正式实验结论**；正式 5 epochs 仍未启动。详见 [25-ldd-real-acceptance-checklist.md](25-ldd-real-acceptance-checklist.md)。

- 第一次真实 1 epoch（任务 `2122910425962569730`）在启动 Python 前失败，`PROCESS_RECORD_FAILED`：只设置了 `SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE`，未设置进程记录 guard `SECURITY_MODEL_EXECUTOR_WORKSPACE`，`AttemptProcessRegistry` 拒绝记录。该失败没有产生子进程；随后修正执行器清理路径并补回归测试，避免“子进程未启动却隔离 GPU 槽位”。
- 第二次真实 1 epoch：任务 `2122913324293771266`，run `af5f081a-6661-462c-b0bc-c70ea003597a`，attempt 1，参数 `num_epochs=1`、`batch_size=128`、`SIG`、`all2one`、`poisoned_portion=0.1`、`seed=123`。12:42:33 启动，12:44:58 任务实际结束；12:45:03 轮询观察到 `success`、progress 100；实际墙钟约 2 分 25 秒，LDD 自报运行 1.79 分钟。
- LDD 实际输出：注入 5000 张中毒图 + 45000 张干净图；`clean_loss_avg≈0.4564`、`poison_loss_avg≈0.0036`；12 个隔离比例下 `clean_purity` 为 100.00%（5%–40%）与 99.98%–99.99%（45%–60%）。这些指标来自 VaB 的已知中毒标签口径，单 epoch 只用于链路验收，不能作为论文效果结论。
- 产物：`F:\security-model-data\ldd-acceptance-20261118\results\20261118-124233-ldd-LDD-short-cycle-20261118-124232`，含 `execution.log`（1967 B）、`generated_LDD.py`（16212 B）、`0.1.pth`（42.69 MB）、`0.1.txt` 和 12 个 `selected_indices_*.npy`；结果目录总大小约 43.9 MB。
- 收尾核对：任务结束后没有残留 `python.exe`；`nvidia-smi` 显存 0 MiB、利用率 0%；`sys_gpu_lease.default-gpu-0` 的 lease/task/run/attempt 已清空，`released_at=12:44:57.730`，`blocked_at=NULL`；报告、日志、执行器状态与 dispatch 快照保存在 `tmp\ldd-short-run-real2-1epoch\run-summary.json`。
- 环境预检在加 guard 一致性检查后重跑：`status=ready_with_warnings`、`errors=0`、`warnings=1`（空闲内存约 2.3 GB），证据 `tmp\ldd-acceptance-environment-final.json`、`tmp\ldd-acceptance-environment-final.log`。
- 运行脚本已收紧：默认不启动，必须显式 `-Start`；复用 `-TaskId` 时先核对 `pending`、`datasetId`、`algorithmId` 和服务端 `customParams`；汇总分开记录 `task_status` 与 `acceptance`，任务成功但报告/产物/租约证据缺失时退出 `4`。假 HTTP 回归覆盖不启动、参数不一致、正常 success、报告缺失和非 pending，均不占 GPU。
- 正式 5 epochs 输入已冻结但未启动：任务 `2122901116713205761` 仍为 `pending`；冻结包 `F:\security-model-data\ldd-acceptance-20261118\formal-freeze-20261118`，含任务 JSON、CIFAR-10 批次清单/SHA256、数据库备份（788,998 B，SHA256 `5D3FC3D4...`）、1 epoch 产物归档（16 文件/46,347,827 B）、环境预检与 JAR 哈希。`hyper_id=NULL`，`hyperParameterFingerprint.available=false` 是未绑定模板，不是最终参数丢失。
- 全量门禁（本机，`REDIS_IT=true`、`RABBIT_IT=true`）：**351 单测 + 126 IT，0 失败/0 错误/0 跳过**，日志 `tmp\ldd-formal-full-verify.log`；Python 11/11，日志 `tmp\ldd-formal-python-tests.log`；前端生产构建通过（仍有 element chunk 947.92 kB 警告），日志 `tmp\ldd-formal-frontend-build.log`。
- 仍未完成：正式 5 epochs 论文实验及其运行窗口/配置冻结；取消、超时、重试的真实 GPU 行为；数据集 6 的样本统计仍为 NULL（仅路径可读验证，不能写成已解析 50000 样本）。2026-08-19 旧工程验证产物不替代论文正式实验。
- 已知问题：无 captcha 的 `/auth/login` 在 `uuid=null` 时 500 NPE；正常前端 `/auth/code` 流程不受影响，直接 API 必须先取验证码。本轮未修复。

## 2026-11-17 R7 场景联调收口（当前事实）

R7 助手已完成“参数策略—模型证据—隔离后端/真实前端/脚本化假模型”浏览器联调，详见 [23-r7-scenario-integration-closeout.md](23-r7-scenario-integration-closeout.md)。

- `dataset`/`trigger_type` 已要求文本类型并校验支持集合，错误码区分 `PARAMETER_REQUIRED`、`PARAMETER_TYPE_INVALID`、`PARAMETER_VALUE_INVALID`、`PARAMETER_OUT_OF_RANGE`；种子 `config_schema` 与 Java 集合一致。
- `AssistantModelEvidence` 已为校验与预检增加有限量、脱敏投影；本地假 HTTP 捕获确认 `valid/code/message/configFingerprint/normalizedParameters`、`executorConfiguration/blockers/warnings` 进入第二轮请求，且无绝对路径、`data_path` 或原始日志。
- 浏览器已实测合法参数、未知 dataset/trigger、数字/布尔/对象类型错误、结果未采集、Worker 未知、越界 403、模型故障与确认卡片；任务 ID 精度已改为 API 边界统一十进制字符串，入参兼容 JSON 整数与字符串，嵌套 `dataset/algorithm` ID 同样字符串化。浏览器在隔离库和本地假模型下实际点击确认并启动 `9007199254740993`；重新打包后的点击同样发出确认请求，因当前用户已有 2 个 running 任务返回 400 `TASK_USER_QUOTA_EXCEEDED`，未误启动任务。800px 与 390px 视口无横向溢出。
- 全量门禁为 **343 单测 + 126 IT，0 失败/0 错误/0 跳过**，聚焦确认/归属 IT 为 **39 项通过**，前端生产构建成功；日志 `tmp/stage10-full-verify-2.log`、`tmp/stage10-focused-tests-3.log`、`tmp/stage10-frontend-build.log`。
- 本轮仍未运行真实 LDD/GPU、未调用真实外部模型、未迁移或操作原 `eladmin`；确认卡片已在旧 jar 上实际点击执行，重复/过期/越权边界由隔离库直连 API 与聚焦 IT 覆盖，未在 UI 中逐项复现。参数校验通过不等于任务可执行，`worker_unknown` 仍按“运行环境尚未验证”展示。


R0 已完成一次以源码为准的事实审计（2026-11-13）：修正了 7 处与代码冲突的文档表述（清单见
[05-decisions.md](05-decisions.md) ADR-012），冻结了状态机、执行/结果协议与目录语义，并把
多用户归属、共享资源权限、历史数据迁移、GPU 并发列为待决事项。**本轮不实现授权、不接入
消息队列/RAG/Agent、不改动也不重跑 128 batch / 5 epochs 的既有 LDD 工程验证配置。**

## 1. 项目整体目标

本项目由早期网络安全竞赛项目重构而来，目标是形成“面向数据清洗的安全模型训练系统”。系统围绕图像训练数据的后门中毒风险，提供数据集管理、LDD 清洗任务、任务监控、参数快照、实验报告和清洗子集导出能力。

当前真实执行阶段只开放 LDD 训练前数据清洗；其他训练前对比算法、训练中防御和训练后防御
已经补齐页面展示和算法目录，但仍不进入真实任务执行范围。

## 2. 当前技术基线

当前运行基座为 Spring Boot 3.5、Java 17、Spring Security 6、Vue 3、Vite、Element Plus、
Pinia、MySQL、Redis 和 Python。R4 的 RabbitMQ/Outbox/Worker 已用真实 Broker + 隔离 MySQL 双 JVM 执行主要场景，默认 `dispatch.mode=db`，未启用迁移版运行链路，未执行项见 §5.17。旧 Spring Boot 2.7 +
Vue 2 工程继续作为业务回归和回滚基线，不参与迁移版主流程。

框架迁移已经完成当前 LDD 业务范围，迁移版保留任务协议和 Python 执行器契约，使算法运行时
可以独立演进。

## 3. 当前业务流程

```text
登记图像数据集
  -> 选择 LDD 和超参数
  -> 创建训练前清洗任务
  -> 校验资源和参数
  -> 生成执行参数快照
  -> 调用 VaB LDD
  -> 保存索引、图像、日志和结构化结果
  -> 查看进度和实验报告
  -> 按保留索引导出清洗后的 CIFAR-10 子集
```

## 4. 已完成

| 模块 | 当前状态 |
| --- | --- |
| SDD 与 README | 已建立系统范围、架构、执行器契约、运行手册、平台实现和项目状态文档。 |
| 首页与菜单 | 迁移版已提供工作台、数据集资源、算法资源、LDD 清洗任务，以及训练前、训练中、训练后三大防御入口。 |
| 数据集与资源管理 | 已保留数据集、模型、算法和超参数模板管理能力。 |
| LDD 算法接入 | 已通过 Python 执行器调用 VaB 外部 LDD，支持任务级目录和结构化结果。 |
| 任务校验 | 新后端原生校验数据集、算法阶段、LDD 实现路径、超参数模板匹配关系和自定义 JSON 参数。 |
| 任务命令 | 新后端原生接管任务创建、编辑、删除、详情、启动、失败重试和取消；LDD 通过 Python 执行器契约运行。 |
| 失败重试 | 失败任务支持重新进入待执行状态，并重新生成启动参数快照。 |
| 参数快照 | 每次启动/重试保存资源、配置、运行环境和时间信息；数据集文件默认保存 SHA-256 内容摘要。 |
| 任务进度 | 支持 pending、running、success、failed、canceled 状态展示、运行概览统计、任务名称搜索和轮询查看。 |
| 执行池观测 | 提供 `/api/task-progress/executor-status`，展示有界线程池的活跃线程、队列占用、完成数、饱和状态、接收状态和任务运行配额；前端任务中心自动刷新。 |
| 知识检索 | MySQL 文档/版本/chunk + ACL 前置检索，确定性中文切词/IDF 排序，引用定位到文档版本/章节/行号；无命中明确拒答，外部模型默认关闭。页面见 `/knowledge`。 |
| 执行日志 | 支持从任务结果登记的日志文件中安全读取内容；运行中的任务会轮询当前尝试目录下的标准输出和错误输出，并按 offset 增量读取，同时返回 Python 适配器的阶段、epoch、进度消息和心跳快照。 |
| 实验报告 | 已展示任务概览、执行尝试、参数快照、样本统计、LDD 指标、失败原因和产物信息。 |
| 实际运行环境 | LDD 结果记录适配器版本、Python、PyTorch、NumPy、CUDA、GPU 和关键 Python 依赖版本，报告页单独展示实际运行环境。 |
| 清洗子集导出 | 根据 LDD 输出的 `selected_indices_<ratio>.npy` 异步重建 CIFAR-10 清洗子集，支持进度、失败重试和 ZIP 下载；启动时 `ExportRecoveryRunner` 将 `pending`/`running` 收敛为 `EXPORT_NOT_STARTED`/`EXPORT_INTERRUPTED`，不自动重派发，需用户显式重试。 |
| 工程验证 | 已完成一次 CIFAR-10 / SIG / batch size 128 / 5 epochs 的 LDD 工程验证；不等同于论文正式实验。 |
| 任务工程约束 | 已增加任务名称搜索、非 pending 任务编辑保护、状态/进度范围校验、批量请求规模与空值校验、run_id/attempt 标识和重试目录隔离。 |
| 并发与事务一致性 | 已使用数据库条件更新保护任务领取；旧运行回调按 run_id 丢弃；外部执行器在事务提交后派发；线程池拒绝会收敛为可重试失败。 |
| 导出并发幂等 | 已通过 `(task_id, isolation_ratio)` 唯一键和原子插入保证并发提交只创建一条导出任务并只调度一次。 |
| 配置治理 | 已按“算法默认配置 -> 超参数模板 -> 任务自定义参数”合并，并对 LDD 白名单、类型、范围和必填项校验。 |
| 产物安全 | 已对 PNG、日志、索引和异步 ZIP 执行真实路径 containment 校验，避免符号链接绕出任务产物目录。 |
| 应用重启恢复 | 已实现训练任务恢复扫描：将无法恢复的 running 任务收敛为可重试失败。导出任务启动时只收敛为失败状态，不自动重新派发；需用户手动重试。 |
| 资源生命周期 | 已阻止删除仍被任务引用的数据集、算法和超参数模板，避免历史报告与导出链路失去依赖。 |
| 资源配额 | 已增加单机任务准入策略，限制运行中任务总数和单用户运行任务数，检查执行池接收状态，并将准入时配额快照写入每次执行记录。 |
| Python 进程控制 | 已将外部进程等待抽离为可测试组件，支持轮询进度快照、取消、超时强制终止和正常退出结果区分；取消/超时时同时回收外层适配器的子进程。 |
| 任务操作审计 | 已新增独立 `security_operation_audit` 表，记录任务创建、编辑、启动、重试、取消、删除及清洗子集导出动作；审计写入与任务命令保持事务一致。 |
| 演示空壳体系 | 已补齐三大防御阶段页面、阶段切换、算法卡片、输入输出说明和接入状态；除 LDD 外均明确标记为展示占位。 |
| 资源管理持久化 | 数据集和算法新增、编辑、停用已接入 MySQL，保存后刷新仍可见；资源操作写入审计，停用保留历史引用。 |
| 资源目录缓存 | 已接入 Redis 资源目录缓存，默认 TTL 5 分钟；Redis 异常自动回源 MySQL，资源变更主动失效缓存。 |
| 多用户归属 | **已实现（R1，2026-11-13）**：`sys_pre_defense.owner_user_id` 为唯一授权依据，`TaskAccessGuard` 统一拦截任务、报告、日志、产物、导出、下载与审计；列表/统计按当前用户过滤；跨用户读默认关闭。 |
| 数据集可用性 | **已补验证入口（R1）**：`POST /api/dataset/{id}/validate`（仅管理员）校验路径存在、可读、非空后把 `parse_status` 推进为 `success`；失败写 `failed` 与原因。 |
| 演示页面 | **当前**：工作台已接 `GET /api/dashboard/overview` 真实聚合（任务计数、近 7 日趋势、数据集/算法登记数、最近任务与执行池状态），攻击成功率/干净准确率/清洗保留率未接模型评估链路时显示“未采集”；案例库仍为前端硬编码演示占位（页面有“演示”警示与标签）；接口扩展预览仍为静态常量数组。运行监控已接真实任务统计、执行器线程池与 GPU 槽位接口，其中“运行中任务”取 MySQL `runningTaskCount`（`status=running`），线程池 `activeCount` 仅单独表示活动线程，不代表任务数。 |

## 5. 当前可演示能力

- 登录后进入安全训练工作台。
- 查看和维护图像数据集。
- 在算法库中查看 LDD 配置。
- 创建 LDD 训练前数据清洗任务。
- 配置批次大小、训练轮数、攻击方式和清洗参数。
- 启动任务并查看运行进度。
- 查看失败原因并执行失败任务重试。
- 查看某次执行的参数快照和 LDD 实验报告。
- 预览任务产物中的 PNG 图像。
- 按隔离比例提交导出任务，查看导出进度并下载由保留索引重建的 CIFAR-10 清洗子集。
- 在任务详情中查看操作人、动作、状态变化和操作说明。
- 浏览训练前、训练中、训练后三大防御页面及其算法方案卡片。
- 资源、任务、进度、报告、日志、导出和审计页面以接口返回为准；接口失败显示明确错误状态，不用样例数据伪装成功。

**历史边界（R0 校正，2026-11-13）**：以下页面在 R0 时曾是**前端硬编码演示内容**，不是真实数据；历史演示材料必须按日期说明。当前状态见上表及阶段 4 记录：工作台已改为真实接口，运行监控已接任务统计/执行器/GPU 槽位。下表只保留 R0 当时事实供追溯：

| 页面 | R0 当时演示内容 | 当前事实（2026-11-17） |
| --- | --- | --- |
| 工作台 `/dashboard` | 累计实验任务 28、平均攻击成功率 4.7%、近 7 日趋势、RTX 3090 节点 | 已接 `GET /api/dashboard/overview`；演示常量已移除，未采集指标显示“未采集” |
| 案例库 `/cases` | 案例编号、清洗规模、攻击成功率降幅 | 仍是 `demoData.js` 常量，页面已加“演示”警示与标签 |
| 运行监控 `/runtime-monitor` | GPU/CPU/内存利用率、节点心跳 | 已接真实任务统计、执行器线程池与 GPU 槽位接口；无硬件采集时不展示虚构利用率 |
| 接口扩展预览 `/training-defense` | 实验配置清单、生效状态、操作人 | 仍是 `TrainingDefenseView.vue` 内的常量数组，不对应数据库记录 |

因此“接口失败显示明确错误状态，不使用样例数据”适用于资源、任务、报告、导出、工作台这条真实链路；案例库和接口扩展预览仍是演示占位，不能据此声称平台具备案例管理或三阶段真实执行能力。

## 5.1 R0 事实审计结论（2026-11-13）

核对范围：`docs/sdd/01、03、05、07、09、10、11、16、17` + `migration-backend` 全部 Java 源码 +
`algorithm-runtime/vab/algorithm_entry.py` + `sql/` 建表与种子脚本 + `migration-frontend` 关键页面。

| 结论项 | 事实 | 证据 |
| --- | --- | --- |
| 真实算法范围 | 只有 LDD（`vab://pre-defense/ldd`），其它实现路径在启动前被拒绝 | `SUPPORTED_ALGORITHMS`、`validateCommand` |
| 执行模型 | API 进程内 `taskAsync` 线程池 + `ProcessBuilder` 子进程，无独立 Worker、无 MQ | `PythonLddTaskExecutor`、`TaskExecutorConfig` |
| 完成依据 | `result.json` 是唯一依据；缺失即失败（无“补最小成功”路径） | `validateResult`、`invoke()` |
| 进度依据 | `progress.json` 只是诊断，Java 只取 `progress` 字段并 clamp 到 0–99 | `updateProgressFromSnapshot` |
| 恢复语义 | 启动时把所有 `running` 收敛为 `EXECUTOR_RESTARTED`，隐含单实例 | `TaskRecoveryRunner` |
| 配额语义 | 4/用户 2 是 JVM 任务数，不限制 GPU；检查与领取不同事务 | `TaskExecutionQuotaPolicy` |
| GPU 串行 | 仅由适配器 `VAB_ROOT/.security-model-vab.lock` 排他锁保证；陈旧锁会永久阻塞 | `acquire_lock` |
| 产物位置 | 默认在系统临时目录 `<tmp>/security-model-executor/pre_defense-<id>/attempt-<n>` | `ExecutionFingerprintService` 默认值 |
| 归属 | 无 owner 列、无服务端授权；`create_by` 是用户名快照 | 见 18 号文档事实表 |
| 数据集登记 | 路径元数据登记，无上传、无验证推进 | `ResourceCommandService.saveDataset` |
| 演示数据（R0 当时） | 工作台/案例/监控为硬编码常量 | `DashboardView.vue`、`demoData.js`、`RuntimeMonitorView.vue`；阶段 4 后工作台与监控已改真实接口 |

设计契约与待决事项分别落在 [18-multiuser-ownership-and-resources.md](18-multiuser-ownership-and-resources.md)
与 [19-local-data-layout-and-retention.md](19-local-data-layout-and-retention.md)。

## 5.2 R1 实施记录（2026-11-13）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 归属键 | `sys_pre_defense` 新增 `owner_user_id` / `data_origin` / `owner_backfill_status`；创建任务写入 `owner_user_id` 与 `data_origin='user'` | `sql/security_model_ownership.sql`、`PreDefenseTaskCommandService.create` |
| 身份 | JWT 增加 `adm` 声明；过滤器把 `(userId, username, admin)` 作为 principal 注入。**评审后改为每次请求按 `userId` 读 `sys_user`**：账号禁用/删除则本次不认证，管理员降权后 `ROLE_ADMIN` 立即消失，令牌内 `adm` 不参与授权；旧 token 无 `userId`，任务写操作被拒 | `JwtTokenService`、`JwtAuthenticationFilter.resolve`、`SysUserMapper.findStateById` |
| 授权 | 读写统一走 `TaskAccessGuard`：写=仅 owner；读=owner，或管理员在开关开启时跨用户读；demo 行非跨用户管理员不可见，且**任何角色不可写**；无 owner 行任何人不可写 | `TaskAccessGuard` |
| 查询口径 | 列表、分页、运行中、统计均按 owner 过滤并排除 demo；统计响应新增 `scope` | `TaskProgressQueryService` |
| 共享资源 | 数据集/算法/超参写操作仅管理员，越权 403 `ADMIN_REQUIRED` | `ResourceCommandService`、`ApiExceptionHandler` |
| 数据集验证 | 新增管理员验证接口，失败不抛 500，写 `failed` 与原因 | `ResourceCommandService.verifyDataset` |
| 配额 | 单用户并发按 `owner_user_id` 计数，历史无 owner 行不占配额 | `TaskExecutionQuotaPolicy` |
| 迁移脚本 | 两段式 + 结构化决策表 `sys_pre_defense_ownership_decision`（`demo`/`assign`/`reject`）；删除用户名自动匹配与快照启发式；非法决策整批中止 | `sql/security_model_ownership.sql` |
| 未做 | 日志限额、attempt 级产物策略；真实 LDD 未重跑（GPU 租约见 §5.3） | 见 `18` §9 证据矩阵 |
| 接口审计 | 逐一核对任务、报告、日志、产物、导出、审计、统计、执行器状态、系统版本、共享资源入口，规则统一，无控制器可调用的无保护内部查询 | `18` §8 |

## 5.3 R2 第一切片实施记录（2026-11-13）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| GPU 单槽租约 | 新表 `sys_gpu_lease`（`slot_id` 主键，`lease_token`/`task_id`/`run_id`/`attempt`/`fencing_token`/`expires_at`）；默认为 `default-gpu-0` | `sql/security_model_gpu_lease.sql`、`GpuLeaseMapper` |
| 原子获取 | 单条条件 UPDATE（空闲或过期才可覆盖）并在同一事务内 `claimForExecution`；抢不到返回 `GPU_BUSY`，任一步失败都会回滚另一半 | `GpuLeaseService.acquire`、`PreDefenseTaskCommandService.launchTask` |
| 续租与 fencing | 租期 300s、60s 续租；每次获取 `fencing_token+1`，续租/释放以 `lease_token` 为条件；续租失败取消本地进程并置 `GPU_LEASE_LOST` | `GpuLeaseHeartbeat` |
| 释放 | 执行器 `finally` 按 token 释放；启动恢复按 `task_id+run_id` 释放，不会清掉新租约 | `PythonLddTaskExecutor`、`TaskRecoveryRunner` |
| 并发口径 | GPU 准入并发由数据库租约强制为 1；JVM 的 4/2 配额保留为拒绝/排队上限，不再当作 GPU 并发数 | 配置 `security-model.executor.gpu.*` |
| 导出重启收敛 | 启动时把 `pending`/`running` 导出置为 `EXPORT_NOT_STARTED`/`EXPORT_INTERRUPTED`，不自动重派发；超时先杀进程树 | `ExportRecoveryRunner`、`CleanSubsetExportExecutor` |
| 未做（第一切片时） | 适配器锁陈旧接管、失败码细分（已在 §5.4 第二切片补齐）；自动重试队列、日志与产物限额仍未做 | 留 R2 后续 / R3 |
| 验证 | 第一切片 62 项单元测试 + 29 项 MySQL IT（持久层 5、迁移 6、端到端 9+4、GPU 租约 5） | 见 §9 |

## 5.4 R2 第二切片实施记录（2026-11-13）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 适配器锁元数据 | 锁文件写 JSON：`pid/host/task_id/run_id/token/acquired_at`；旧裸 pid 文件仍可读 | `algorithm_entry.acquire_lock` |
| 陈旧接管 | 仅在持有 pid 不存活时接管；隔离移动后比对 token，不一致则恢复并返回 `GPU_LOCK_BUSY`；无 pid 的损坏文件才按 `SECURITY_MODEL_VAB_LOCK_STALE_SECONDS`（默认 21600）年龄判定 | `acquire_lock`、`_lock_is_stale` |
| 安全释放 | 只有磁盘锁 token 与本次运行一致才删除，慢进程不能删后继者的锁 | `release_lock` |
| 存活探测 | Windows `OpenProcess` + `GetExitCodeProcess`；POSIX `os.kill(pid,0)`；不使用会终止进程的 Windows `os.kill(pid,0)` | `_pid_alive` |
| 失败码细分 | `GPU_OOM`（OOM/CUBLAS/CUDNN 分配失败）、`CUDA_UNAVAILABLE`（无设备/驱动不符）、`GPU_LOCK_BUSY`（锁冲突），其余 `VAB_INTEGRATION_ERROR`；写入 `result.json.error_code` | `classify_runtime_failure`、`IntegrationError.error_code` |
| Java 兜底 | 子进程非零退出且无 `result.json` 时按 stderr 归类；有结果时以适配器写入的 `error_code` 为准 | `PythonLddTaskExecutor.classifyProcessFailure` |
| 不自动重试 | `GPU_OOM`/`CUDA_UNAVAILABLE` 明确不自动重试；`GPU_LOCK_BUSY` 仅提示稍后重试 | ADR-015 |
| 未做 | 自动重试队列、日志与 attempt 级产物限额、真实 LDD 实测 | R3 |
| 验证 | Java 69 项单元测试（新增 `PythonLddTaskExecutorFailureCodeTest` 7 项）+ 29 项 MySQL IT；适配器 `python -m unittest` 11 项 | §9 |

## 5.5 R2 第三切片实施记录（2026-11-13）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| attempt 产物边界 | `<workspace>/pre_defense-<taskId>/attempt-<n>/` 为一次执行的产物目录，重试递增不覆盖 | `PythonLddTaskExecutor.createContext` |
| 日志截断 | 超过 `max-log-bytes`（默认 20 MiB）只保留尾部并写截断标记；先分类后截断 | `ExecutorArtifactPolicy.trimLog` |
| SHA-256 清单 | 每次执行结束写 `manifest.json`（文件名/字节数/SHA-256），失败只 warn | `ExecutorArtifactPolicy.writeManifest` |
| 保留期 | `retention-days` 默认 0（关闭）；开启后只删“终态任务 + 非当前 attempt + 超期 + 位于 workspace 内”的目录 | `ExecutorArtifactRetentionJob` |
| 验证 | Java **85 项单元测试 + 30 项 MySQL IT** 全绿（产物 7、孤儿进程 6、隔离槽位 1、恢复用例） | §9 |
| 恢复护栏 | 启动前写 `process.json`（写失败拒绝启动为 `PROCESS_RECORD_FAILED`），启动后写 pid/command/startInstant；适配器写 `python-children.json`；恢复按 `NOT_STARTED/EXITED/REAPED/KILL_FAILED/UNKNOWN` 判定，只有前三者释放租约 | `AttemptProcessRegistry`、`TaskRecoveryRunner`（ADR-017） |
| 槽位隔离 | `KILL_FAILED/UNKNOWN` 置 `blocked_at/blocked_reason`；TTL 过期仍拒绝领取并返回 `GPU_LEASE_BLOCKED`；`GpuLeaseBlockedProbe` 每 60s 重探，确认退出才解除 | `sys_gpu_lease`、`GpuLeaseBlockedProbe`、`sql/security_model_gpu_lease_block.sql` |
| 未做 | 真实 LDD 产物实测；目录递归哈希；自动算法重试队列（D5，仍不在范围）；生产库建表；带意图的真实 JVM 重启与 R3-04 崩溃演练 | 见 §5.7、§9 |

<!-- knowledge:historical -->
## 5.6 R2 第四切片：恢复语义实现（2026-11-13，历史记录；复核修复见 §5.6.1）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 进程记录协议 | `schema_version=2`；`prepare` 写 `state:starting`，`record` 写 `state:running + pid/command/started_at`；两次写入均临时文件 + `ATOMIC_MOVE`；无记录、旧协议、损坏 JSON、`starting` 无 pid 一律 `UNKNOWN` | `AttemptProcessRegistry` |
| 身份与整树 | 启动时间缺失/不等、父死但已记录子进程存活 → `UNKNOWN`，不误杀；身份正向匹配才杀；`ExecutorProcessGuard` 同时检查 root、`descendants()`、`python-children.json`；记录缺失/损坏不当作空树，正常结束由适配器写空 `pids` 留证 | `AttemptProcessRegistry`、`ExecutorProcessGuard` |
| 释放语义 | 执行器 `finally` 整树确认退出才 `releaseQuietly`，否则 `markBlocked("EXECUTOR_TREE_ALIVE")`；记录写失败立即杀树并 `PROCESS_RECORD_FAILED`；中断/超时/导出一致 | `PythonLddTaskExecutor`、`CleanSubsetExportExecutor` |
| 启动就绪门 | 恢复循环完成后 `SchedulingReadinessGate.markReady()`；未就绪启动返回 `SCHEDULING_NOT_READY`，只读接口不拦截 | `TaskRecoveryRunner`、`PreDefenseTaskCommandService` |
| 隔离可见性 | `/api/task-progress/executor-status` 返回 `gpuSlot`（blocked/leased/idle/unknown、原因、隔离时间、最近探测时间、指引），不含 taskId/路径；前端任务中心展示 | `TaskProgressController`、`TaskCenterView.vue` |
| 保留期边界 | 删除前 `toRealPath()` 必须位于 `workspace.toRealPath()` 内且等于 `normalize()` 路径，拒绝 symlink/junction 与 `..` 越界 | `ExecutorArtifactRetentionJob.resolveSafe` |
| 验证（R2 收口时实测） | `mvn -B -f migration-backend/pom.xml test` → **104 项单元测试，0 失败，0 跳过**；`verify -Pmysql-it` → 同一单测集 + **33 项 MySQL IT 全绿**；前端 `npm run build` 通过；R3 实施后计数见 §5.7 | `tmp/r2-final-verify.log` |
| 崩溃演练 | `OrphanProcessCrashDrillTest`：独立 JVM 启动短 Python 父子并记录，强杀启动器后由恢复判定 `REAPED`，父子均死；单跑与全量均通过 | 同上 |
| 未做 | 真实 LDD/6GB 显存实测；生产库建表与迁移；日志运行期磁盘上限（当前仅归档截断） | 见 §7、§9 |

<!-- /knowledge:historical -->

## 5.6.1 R2/R3 复核修复记录（2026-11-14，待复审）

针对 `9d21281` 外部评审的 P1/P2，按 20 号任务书完成以下修复；本节只记录已落地代码与可复现证据，不代表外部复审已通过。

| 评审项 | 修复事实 | 证据 |
| --- | --- | --- |
| P1-1 执行器接受后记账失败释放 GPU | 正常派发与重放都拆成“执行器调用”和 `markSubmitted` 两个阶段；执行器接受后回写失败只记 warn，不释放租约、不失败任务；派发前 `AttemptProcessRegistry.markAccepted`，执行器拒绝/异常 `clearAccepted` | `TaskDispatchCoordinator`、`PythonLddTaskExecutor`；`TaskDispatchCoordinatorTest.replayAcceptedWorkIsNotFailedWhenBookkeepingFails` |
| P1-2 恢复扫描误杀/误释放 | pending/submitting 先核对意图与当前 `runId/attempt`；旧意图只 abandon；pending 过期先 `abandonPending` 条件抢占，只有赢家 release；本 JVM 已接受的 attempt 跳过探测；启动收敛同样跳过本地 accepted | `DispatchRecoveryCoordinator`、`TaskDispatchIntentMapper.abandonPending`；`DispatchRecoveryCoordinatorTest` 6 项 |
| P1-3 过期租约被当作空闲 | `acquire` 只允许 `lease_token IS NULL AND blocked_at IS NULL`；发现过期活跃租约时 `block("LEASE_EXPIRED")` 并返回 `GPU_LEASE_EXPIRED`；`renew` 增加 `expires_at >= now`；心跳改用本地租约快照，先取消进程再写失败记录，DB 异常也取消 | `GpuLeaseMapper`、`GpuLeaseService`、`GpuLeaseHeartbeat`；`GpuLeaseMySqlIT` 8 项、`GpuLeaseHeartbeatTest` 3 项 |
| P1-4 子进程记录缺失/身份不足 | 缺失/损坏/无 `pids` 的 `python-children.json` 返回 `UNKNOWN`，不再当空集合；只杀身份正向匹配的根进程及其可证明后代，记录中无法证明父子关系的 PID 不杀、存活则保持隔离；`write_children` 原子写失败会立即终止训练子进程 | `AttemptProcessRegistry`、`algorithm_entry.py`；`AttemptProcessRegistryTest` 13 项、`OrphanProcessCrashDrillTest` |
| P2 幂等提交失败/崩溃 | 业务事务回滚时注册 `afterCompletion` 清理 claim；过期删除只处理 `succeeded`，`processing` 不自动删除；超时处理中转 `failed` 并要求人工核对；前端 create/start/retry 在同一次逻辑请求重试时复用键 | `RequestIdempotencyService`、`IdempotencyRecordStore`、`IdempotencyRecoveryJob`、`RequestIdempotencyMapper`、`TaskCenterView.vue`；`RequestIdempotencyServiceTest` 7 项、`IdempotencyRecoveryJobTest`、`MySqlTaskPersistenceIT` |
| P2 重放前置校验 | `readRequest` 校验 `schema_version` 与 `request_hash`；重放校验快照 task/run/attempt、owner 非空且启用、demo 拒绝、数据集/算法启用、运行时指纹可用（空路径 `available=false`） | `TaskDispatchIntentService`、`TaskDispatchCoordinator`、`ExecutionFingerprintService`；`TaskDispatchIntentServiceTest` 2 项、`TaskDispatchCoordinatorTest` 8 项 |

本机实测（2026-11-14）：`mvn -B -f migration-backend/pom.xml verify -Pmysql-it` → **137 项单元测试，0 失败/0 跳过；41 项 MySQL IT 全绿；1 项 Redis IT 因未设置 `REDIS_IT=true` 跳过**；`python -m unittest discover -s algorithm-runtime/vab/tests` → **11/11 通过**；`npm --prefix migration-frontend run build` → 通过，仍有大 chunk 警告。日志见 `tmp/r2-r3-fix-verify3.log`、`tmp/r2-r3-python-tests.log`、`tmp/r2-r3-frontend.log`。未跑真实 LDD、未写生产库。

仍未完成：真实 LDD/6GB 显存实测；生产库 `eladmin` 建表与迁移；带意图的真实 JVM 重启、R3-04 崩溃演练与真实库旧/新 run 竞争；Redis 宕机注入；日志运行期磁盘硬上限（当前仅归档截断）。R2/R3 本轮为“修复完成待复审”，不写“验收通过”。

## 5.7 R3 实施记录：持久调度与请求幂等（2026-11-13）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 调度意图 | 新增 `sys_task_dispatch_intent`（唯一 `task_id+run_id+attempt`，状态 `pending/submitting/submitted/abandoned`）；`request_json` 由 `DefenseTaskRequest` 序列化，`gpuLeaseToken` 被 `@JsonIgnore` 排除；同时保存 `request_hash` 与 `runtime_hash` | `TaskDispatchIntent`、`TaskDispatchIntentMapper`、`TaskDispatchIntentService` |
| 同事务与条件抢占 | `launchTask` 在领取事务内写 pending；提交后 `UPDATE ... WHERE status='pending'` 仅 1 行可执行；执行器接受后才 `markSubmitted`；接受后记账失败只告警，不失败任务 | `PreDefenseTaskCommandService`、`TaskDispatchCoordinator` |
| 单一恢复 | `TaskRecoveryRunner` 只委托 `DispatchRecoveryCoordinator`；pending 直接重放（不因无进程记录误失败），submitting/submitted 收敛为 `DISPATCH_UNCERTAIN`，无意图旧任务按 R2 证据失败；最后 `markReady` | `TaskRecoveryRunner`、`DispatchRecoveryCoordinator` |
| 重放校验 | 校验任务 `run_id/attempt`、归属账号仍启用、数据集/算法仍可用、运行时指纹未变；先释放本 run 旧租约，再 acquire 新 token；GPU_BUSY/BLOCKED、路径/指纹/身份失效均零执行 + abandoned | `TaskDispatchCoordinator` |
| 有界扫描 | 10s 间隔、初始 30s、每批 20、只扫超过 30s 的 pending；创建超过 5 分钟 → `DISPATCH_EXPIRED`；submitting 失联按进程证据释放或隔离后 `DISPATCH_UNCERTAIN`；submitted 不扫描 | `DispatchRecoveryCoordinator.scanPending`、`application.yml` |
| 请求幂等 | `sys_request_idempotency` 唯一 `(owner_user_id, operation, idempotency_key)`，24h 有效期；create/start/retry 支持可选 `Idempotency-Key`（1–128）；同体复用、异体 409、进行中 409、跨用户独立；成功记录随业务事务提交，失败在独立事务释放键；2026-11-14 增加提交回滚清理、processing 不自动删除、超时转 `failed` 待人工核对 | `RequestIdempotencyService`、`IdempotencyRecordStore`、`IdempotencyRecoveryJob`、`PreDefenseTaskController` |
| 前端 | create/start/retry 每次提交传入幂等键；2026-11-14 改为同一次逻辑请求失败重试复用同一键，成功后才清除；失败详情继续显示 `failure_reason` | `preDefense.js`、`task.js`、`TaskCenterView.vue` |
| 验证（本机实测） | `mvn verify -Pmysql-it` → **120 项单元测试，0 失败/0 跳过；40 项 MySQL IT 全绿**（`MySqlTaskPersistenceIT` 13 项）；前端 `npm run build` 通过 | `tmp/r3-final-verify.log`、`tmp/r3-frontend-build.log` |
| 未做 | 带意图的真实 JVM 重启重放一次、R3-04 短进程崩溃、生产库建表；R3-07 旧 run 迟到结果与 R3-05 取消/瞬时完成已补真实库条件更新测试（`MySqlTaskPersistenceIT` 16 项），但真实命令服务层全链路仍未补 | 见 20 §6.5 |

## 5.8 R5 实施记录：观测、缓存代际与最小暴露（2026-11-13）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 运行指标 | `TaskRuntimeMetrics` 每 30 秒从 MySQL 聚合非 demo 任务状态与陈旧 running 数，读取执行池和 GPU 槽位；标签仅 status/state/result/outcome，没有 taskId/runId/userId | `TaskRuntimeMetrics`、`application.yml` |
| 耗时与恢复计数 | 执行器按 success/failed/canceled/interrupted 记录耗时；启动恢复记录 completed/failed | `PythonLddTaskExecutor`、`DispatchRecoveryCoordinator` |
| 日志关联 | `RequestCorrelationFilter` 生成/校验 `X-Request-Id` 并写 MDC；执行线程写 taskId/runId/attempt；logback 统一 pattern | `RequestCorrelationFilter`、`logback-spring.xml` |
| 缓存代际 | 读路径先取 generation，miss 后加载 MySQL，只有 generation 未变才条件写回；提交后失效先增代际再删除；Redis 故障回源并计数 degraded | `ResourceCatalogCache`、`ResourceQueryService` |
| 管理端汇总 | `/api/observability/summary` 仅管理员，返回任务状态、陈旧进度、执行池、槽位、缓存计数；不返回 taskId/userId/路径 | `ObservabilitySummaryService`、`ObservabilityController` |
| Actuator 最小暴露 | `/actuator/health` 匿名且不展示详情；`/actuator/metrics`、`/actuator/info` 与 `/api/observability/summary` 仅 `ROLE_ADMIN`；匿名 401、普通用户 403、管理员 200 | `SecurityConfig`、`application.yml`、`OwnershipEndToEndIT` 真实 Security 链 |
| 前端统计 | 统计页改为读取真实 `statusStats` 并排除 demo；管理员可额外看到全局槽位/执行池/缓存；未采集的 ASR/GPU 利用率不再展示演示数字 | `StatisticsView.vue`、`task.js` |
| 文档 | 更新 06 迁移版启动/停止/备份/排障、08 Redis 与观测配置、05 ADR-019 | 06/08/05 |
| 验证（本机） | `mvn verify -Pmysql-it`（`REDIS_IT=true`）→ **127 项单元测试，0 失败/0 跳过；41 项 MySQL IT + 1 项真实 Redis IT 全绿**；前端 `npm run build` 通过；Redis 宕机未注入 | `tmp/r5-final-verify.log`、`tmp/r5-redis-it.log`、`tmp/r5-frontend-final.log` |
| 未做 | 生产库建表、真实 Redis 宕机/并发回填故障注入、MySQL 长时观测、CI 远端结果；统计页未展示真实 ASR（无采集源） | 见 20 §8、§9 |

## 5.9 R6 实施记录：有引用的知识检索（2026-11-14，外部模型联调阻塞）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 知识库 schema | 新增 `sys_knowledge_document` / `sys_knowledge_chunk`；`(source_key, version)` 与 `(document_id, chunk_index)` 唯一；检索只连接 `status='active'` | `sql/security_model_knowledge.sql`、`KnowledgeDocumentMapper` |
| ACL | 共享文档所有稳定认证用户可读；私有报告仅 `owner_user_id`；管理员跨用户读默认关闭，需显式开启 | `KnowledgeDocumentMapper.findSearchableForUser`、`KnowledgeRetrievalService` |
| 导入与版本 | 管理员显式触发；SDD 直接 `.md` 文件拒绝符号链接/越界，UTF-8 去 BOM 后先脱敏再 SHA-256；内容未变跳过；变化时同一事务写新版本+全部 chunk 再 retire 旧 active 版本 | `KnowledgeIndexService`、`KnowledgeDocumentWriter` |
| 分块与切词 | Markdown 保留标题/章节/1-based 行号；中文 Han 连续串展开单字+二元组，英文/数字按词，停用疑问词 | `MarkdownKnowledgeChunker`、`KnowledgeTokenizer` |
| 排序与拒答 | 标题 3/章节 2/正文 1，IDF 加权；`min-coverage=0.30`、`min-score=20`、topK=5；无命中返回 `unknown`，不调用模型 | `KnowledgeRanker`、`KnowledgeRetrievalService` |
| 引用回答 | `POST /api/knowledge/query` 返回 `status/answer/citations/modelStatus/modelMessage`；引用含 sourceKey/version/section/chunk/line/snippet/visibility；模型答案出现候选集外引用时整段丢弃并回退 | `KnowledgeQueryService`、`KnowledgePromptBuilder` |
| 外部模型 | 业务仅依赖 `KnowledgeAnswerModel` 端口；`provider` 选择一个适配器（默认 `openai-compatible`），新增供应商只增加实现并切换配置。默认 `enabled=false`；无 key 返回 `model_not_configured`；超时/429/5xx 最多重试 1 次；4xx/超时/空响应降级为 `retrieval_only`；请求体白名单不含 sourceKey/文档 ID/owner/路径/日志/样本/凭据 | `KnowledgeAnswerModel`、`OpenAiCompatibleKnowledgeAnswerModel` |
| API | `GET /api/knowledge/status`、`POST /api/knowledge/reindex`、`POST /api/knowledge/algorithms/reindex`、`POST /api/knowledge/tasks/{taskId}/index`（owner）、`DELETE /api/knowledge/documents/{documentId}`（管理员） | `KnowledgeController` |
| 前端 | 新增知识检索页、路由与菜单；展示回答/检索模式/未知、引用卡片、模型状态；管理员索引入口对 403 给出明确提示，不伪造成功 | `KnowledgeView.vue`、`src/api/knowledge.js`、`router/index.js`、`App.vue` |
| 验证（本机） | `mvn verify -Pmysql-it`（`REDIS_IT=true`）→ **174 项单元测试，0 失败/0 跳过；45 项 MySQL IT + 1 项真实 Redis IT 全绿**；Python 11/11；前端 `npm run build` 通过（element chunk 947.11 kB 警告仍在）；未跑真实 LDD | `tmp/r6-final-verify3.log`、`tmp/r6-python-tests.log`、`tmp/r6-frontend.log` |
| 未做/阻塞 | 外部模型供应商、费用上限、数据留存未获人类授权，不能真实外发；生产库 `eladmin` 未建表；真实语料规模、模型质量与 20 题之外的失败样例未评估 | 20 §9 |

## 5.10 R7 实施记录：受控实验助手（2026-11-14，2026-11-15 本机接入 DeepSeek）

| 变更 | 事实 | 证据 |
| --- | --- | --- |
| 助手 schema | 新增 `sys_assistant_confirmation`、`sys_assistant_session`、`sys_assistant_tool_audit`、`sys_assistant_usage_daily`；确认 token 只存 SHA-256，会话按用户 7 天保留 | `sql/security_model_assistant.sql` |
| 工具白名单 | 显式注册 4 个只读工具（参数说明、本人任务摘要、本人报告摘要、知识检索）和 5 个写工具（创建/启动/取消/重试/导出）；未知工具名直接拒绝，无 shell/任意 SQL/任意文件工具 | `AssistantToolRegistry`、`AssistantToolDescriptor` |
| 只读授权 | 任务和报告工具复用 `TaskAccessGuard.requireOwned`；知识工具复用 ACL-first 检索；跨用户直接 403 | `TaskSummaryTool`、`ReportSummaryTool`、`KnowledgeSearchTool` |
| 预览不写 | 写工具只做状态/参数校验并返回脱敏预览、风险说明、规范请求和上下文哈希；创建/启动/重试预览会重新解析数据集、算法默认配置、超参模板和最终合并参数，返回 `configFingerprint` 与 `finalParameters`；创建预览不会写库，启动预览不会领取 GPU | `CreateTaskPreviewTool`、`StartTaskPreviewTool`、`RetryTaskPreviewTool`、`TaskExecutionPlan` |
| 一次性确认 | 预览发 256-bit 随机 token，数据库仅存哈希，TTL 默认 300 秒；确认时重校验用户/token/过期/task-run-attempt-update_time/参数哈希；create 重新构造命令并比对 `expectedConfigFingerprint`，start/retry 用当前 `plan(task).fingerprint()` 生成上下文哈希；资源或参数变化返回 409 `ASSISTANT_CONFIRMATION_STALE`，不领取 GPU、不写调度意图、不派发；条件消费后才调用原命令服务 | `AssistantConfirmationService`、`AssistantConfirmationMapper`、`PreDefenseTaskCommandService` |
| 并发边界（评审 P1-1/P1-2） | 任务编辑/删除/启动/重试/取消先在事务内 `SELECT ... FOR UPDATE` 锁行；编辑只写白名单字段并带 `status='pending'` 条件；确认在哈希校验前锁定目标任务，锁内重读确认记录，锁持有到业务命令提交；已补“运行态不可被编辑回 pending”MySQL 测试、“预览后 run/算法默认值变化返回 409 且执行器零调用”HTTP+MySQL 测试；`TaskCommandServicePipelineIT` 扩至 6 项：双启动竞争、删除—启动、编辑—启动、取消/瞬时成功、执行器完成回调与并发取消；删除—启动/编辑—启动均断言不出现已删除任务执行或参数版本混杂 | `PreDefenseTaskMapper`、`PreDefenseTaskCommandService`、`TaskAccessGuard.requireOwnedForUpdate` |
| 出站数据（评审 P1-3/P2-6） | 报告索引只写入指标、错误码、选择类型和生效参数摘要，禁止原始结果/日志/路径；`KnowledgeSanitizer` 增加 `F:/`、中文/空格/嵌套 Unix 路径、JSON 键值凭据、手机号/身份证脱敏；模型只收到显式白名单证据 DTO（指标、引用片段、行号），不接收整个业务响应；参数证据按 `type/default/min/max/options` 结构化投影，真实 LDD schema 超过 400 字符时仍保留 `batch_size`/`num_epochs` 约束；总时限同时传入模型适配器并在模型返回后再次检查 | `KnowledgeIndexService.taskReportMarkdown`、`KnowledgeSanitizer`、`AssistantModelEvidence`、`AssistantChatService` |
| 进程与租约（评审 P1-4/P2-5） | 运行期记录 PID 只有在 parent 链能证明是当前 root 后代时才强杀，未认证存活 PID 保留槽位隔离；`python-children.json` 缺失/损坏不再当作空树，正常结束由适配器写空 `pids` 留证；过期租约不再在调用事务内写 `blocked`，改为事务完成后以 `REQUIRES_NEW` 独立提交，调用方回滚不会撤销隔离；启动失败同时清理本 JVM 本地租约 | `ExecutorProcessGuard`、`GpuLeaseService`、`PreDefenseTaskCommandService.launchTask`、`algorithm_entry.py` |
| 防重复与幂等 | 已消费 token 返回原结果并标记 `confirmationReplayed=true`；确认事务接入 R3 `RequestIdempotencyService`；命令失败回滚会恢复 pending，不产生半截写；并发同凭据创建确认已断言只产生 1 个任务，重复/并发竞态最多返回 409 而不重复执行 | `AssistantWriteExecutor`、`AssistantConfirmationEndToEndIT` |
| 配额与时限 | 每用户每日默认 50 次，数据库条件自增，超出 429；每次最多 6 次模型调用、5 次工具调用、60 秒；全局 4 个请求槽，单用户默认 1 个并发槽（可配 1–4），同用户阻塞不会占满全局容量；读工具与写预览已通过独立执行器接收剩余预算，超时取消后续工具/模型调用；Future 取消不保证底层 JDBC/Redis 已物理终止，写预览事务取消后的提交边界仍待故障注入 | `AssistantQuotaService`、`AssistantChatService`、`application.yml` |
| 模型端口 | 业务只依赖 `AssistantModel`；默认 `provider=disabled`，明确返回 `model_disabled`；本机 `deepseek` profile 已接真实 DeepSeek 适配器，生产默认仍关闭；最终请求体隐私由真实 ChatService + 适配器 + 假 HTTP 测试覆盖 | `AssistantModel`、`DeepSeekAssistantModel`、`DisabledAssistantModel` |
| 审计与会话 | 工具审计只存工具名/operator/target/resultCode/durationMs，不存 prompt、参数正文或凭据；会话只存问题、状态、工具名、确认 ID、引用 ID；用户可删除自己的会话 | `AssistantAuditService`、`AssistantSessionService`、`AssistantRetentionJob` |
| API/前端 | `POST /api/assistant/chat`、确认、工具目录、预览、会话列表/详情/删除、审计、状态；新增 `/assistant` 页面，展示模型不可用、工具结果、引用和待确认操作 | `AssistantController`、`AssistantView.vue`、`src/api/assistant.js` |
| 验证（本机） | 2026-11-14 基线为 211 单元 + 75 IT；2026-11-15 基于 `cde3173` 的审查整改后为 230 单元 + 81 IT；`930c934` 五项收口后为 231 + 89；`08f11c0` 后 P1/P2/R3 收口为 232 + 96；2026-11-16 R4 六项整改后为 **263 单元 + 100 IT**（`tmp/r4-final-verify.log`）；追加自然死信重放/DB 中断/旧队列/真实 Broker IT 后为 **267 单元 + 103 IT 全绿**（`tmp/r4-final-verify-new.log`，含 `RabbitDispatchBrokerIT` 2/2）；阶段 2 自动恢复与历史索引改动后为 **277 单元 + 104 IT 全绿**（`tmp/stage2-full-verify-final3.log`）；四项阻断修复后为 **287 单元 + 106 IT 全绿**（`tmp/stage2-full-verify-new.log`）；助手 HTTP+MySQL 确认/会话链路 22 项、真实命令服务链路 7 项、独立 JVM 崩溃/恢复/竞争 5 项，另新增真实执行器链路 5 项、R4 单测与 IT；前端 `npm run build` 历史通过；未跑真实 LDD | `tmp/stage2-full-verify-new.log`、`tmp/stage2-full-verify-final3.log`、`tmp/r4-final-verify-new.log`、`tmp/r4-final-verify.log`、`tmp/round5-r4-final-verify5.log`、`tmp/round3-r3-prod.log` |
| 未做/阻塞 | 生产库 `eladmin` 未执行助手/知识建表；确认流不是组织级双人审批；R3 已补完整 Spring 代理 + 真实执行器四类链路与真实崩溃子 JVM，但 Python 仍是短协议桩，不启动 LDD/GPU，崩溃子 JVM 是测试最小上下文；N6 历史远端 Java 17 CI 在 `bb4a79f` 的 run `43726078023` 通过；本轮 `cd5a6ae` 的 run `43846823071` backend/python/frontend 全绿；**R4 剩余指定真实验收已补**（延迟 confirm、双向停机切换、旧 dead 与新 attempt 竞争），长期 DB 故障/多任务退避仍为压力边界，整体不能写成生产部署通过；详见 `20 §6.8` | ADR-021、ADR-025、20 §6.8 |

## 5.11 DeepSeek Agent 与本地向量 RAG 演示（2026-11-15，本机联调，不是验收）

| 项 | 事实 | 证据 |
| --- | --- | --- |
| DeepSeek 适配器 | 新增 `DeepSeekAssistantModel`，实现 `AssistantModel`；`provider=deepseek` 时启用，工具 schema 转为 OpenAI `tools`，解析 `tool_calls`，普通回答解析 `choices[0].message.content`；401/超时/5xx 映射为稳定状态；Key 只进入 `Authorization` 头 | `DeepSeekAssistantModel`、`application-deepseek.yml` |
| 本地密钥 | `LocalApiKeyResolver` 支持 `api-key` 内联或 `api-key-file`；只读常规文件、上限 4096 bytes，失败返回空字符串，不打印内容；仓库 `.gitignore` 忽略 `secrets/` | `LocalApiKeyResolver`、`.gitignore` |
| DeepSeek 真实外发 | 本机 `POST https://api.deepseek.com/chat/completions` 最小调用返回 `OK`（12 tokens/约 671 ms）；function calling 请求返回 `get_task_status`，参数 `{"task_id":42}`（336 tokens/约 974 ms）；实际响应模型名为 `deepseek-flash` | 本机 PowerShell 冒烟，未写入仓库 |
| 应用内 Agent 链路 | 2026-11-15 启动 `deepseek` profile：`/api/assistant/chat` 调用 `get_parameter_guide` 返回 5 个算法/4 个数据集；创建预览返回 `pendingConfirmations`；确认后创建 `Agent演示任务`（`pending`、`owner_user_id=1`），未自动启动；按任务 ID 查询只返回本人任务 | 本机运行日志 `tmp/r11-app-vector5.log`、演示库 `security_model_demo` |
| 本地 embedding | `LocalEmbeddingModel` 将英文词和中文单字/二元/三元字符 n-gram 哈希到 256 维并 L2 归一化；完全离线、无新增依赖；是演示实现，不是 BGE 神经 embedding | `LocalEmbeddingModel`、`LocalEmbeddingModelTest` |
| Redis 向量库 | 使用 Redis 8 `vectorset` 的 `VADD`/`VSIM`，key `security_model:knowledge:vectors:v1`，元素 `c:<chunkId>`；`SETATTR` 写入 visibility/owner/documentId，`VSIM ... FILTER` 在候选阶段按 shared/owner 过滤，管理员跨用户读不加过滤；`change=0` 时 reindex 也会回填向量；旧版本向量按 document 删除；业务 `retire()` 成功后清理向量，失败可被 `KnowledgeVectorReconcileJob` 有界对账补偿 | `KnowledgeVectorStore`、`KnowledgeIndexService`、`KnowledgeVectorReconcileJob`、`KnowledgeVectorStoreRedisIT`、`KnowledgeRetrievalMySqlIT` |
| 混合检索 | 关键词 `KnowledgeRanker` 先按 `candidateLimit` 形成词法候选池，向量只合并进该池；`retrievalMode=hybrid`、`vectorHits` 只统计可读有效命中；词法候选为空时直接拒答，不是独立语义召回；Redis 命令失败/不支持时本轮返回 `lexical`，不再仅看配置 | `KnowledgeRetrievalService`、`KnowledgeQueryResponse`、`KnowledgeHybridEvaluationTest` |
| 应用内 RAG | `POST /api/knowledge/reindex` 写入 408 个 chunk 向量（`VCARD=408`）；`/api/knowledge/query` 真实返回 `retrievalMode=hybrid`、`vectorHits=40`，并调用 DeepSeek 生成带 `[C1]` 引用回答 | 本机验证，日志 `tmp/r11-app-vector5.log` |
| 验证 | 全量 `mvn verify -Pmysql-it`（`MYSQL_IT_PASSWORD`、`REDIS_IT=true`、`RABBIT_IT=true`）最新为 **327 项单元测试 + 123 项 MySQL/Redis/RabbitMQ 集成测试**，0 失败/0 跳过，BUILD SUCCESS（`tmp/stage8-full-verify.log`；此前 312+113 见 `tmp/stage6-full-verify.log`，初始化收口基线 306+111 见 `tmp/stage5-init-full-verify.log`，干净 worktree 复验见 `tmp/archive-clean-full-verify.log`）；此前 287+106 为 2026-11-16 四项阻断修复基线（`tmp/stage2-full-verify-new.log`），277+104 为阶段 2 收尾基线，267+103 为 R4 六项整改基线；前端 `npm run build` 历史通过（element chunk 警告仍在） | `tmp/stage8-full-verify.log`、`tmp/stage6-full-verify.log`、`tmp/stage5-init-full-verify.log`、`tmp/archive-clean-full-verify.log`、`tmp/stage2-full-verify-new.log` |
| 未做/边界 | 仅演示库 `security_model_demo` 验证，生产库 `eladmin` 未迁移；本地 embedding 是可替换的哈希实现，未接 BGE/ONNX；DeepSeek 每日预算/token 上限未形成生产配置；未跑真实 LDD；R3 子 JVM 用测试最小上下文与短 Python 桩，R6 故障用替身异常模拟，R7 阻塞用触发器模拟，Ubuntu Java 17 远端 CI 已在 `bb4a79f` 通过（run `43726078023`） | 20 §9/§10、ADR-021/ADR-023/ADR-024 |

<!-- knowledge:historical -->
## 5.12 独立审查整改（2026-11-15 历史记录；当时未整体验收）

| 项 | 事实 | 证据 |
| --- | --- | --- |
| P1 外发路径 | 本轮修正口径：`KnowledgeSanitizer` 不再豁免 `/api/` 等自由文本前缀，任意绝对多段路径（含含空格路径）替换为 `[已过滤路径]`；`/secret.txt`、`/api/private/samples.png`、`/custom dir/private sample.png` 均已覆盖。旧“API 路由前缀保留”的表述作废 | `KnowledgeSanitizerTest`、`AssistantOutboundPrivacyTest` |
| P1 最终请求体 | 真实 `AssistantChatService` + 真实 `DeepSeekAssistantModel` + 本地假 `HttpServer` 连续两次请求：第一次返回工具调用，第二次返回最终回答；断言两次 body 都不含路径、API Key、`Authorization`，且出现 `[已过滤路径]` | `AssistantOutboundPrivacyTest`、`tmp/review-current-targeted2.log` |
| P2 向量 ACL | `VADD ... SETATTR` 写入 `{"visibility","owner","documentId"}`，`VSIM ... FILTER` 在候选阶段限定 shared/owner；真实 Redis IT 覆盖 50 条 owner=8 高相似私有片段不挤占 user=7、admin 跨用户读、`removeDocument` 后不再命中 | `KnowledgeVectorStoreRedisIT`、`tmp/review-current-vector.log` |
| P2 混合检索 | 本轮修正为：先按 `candidateLimit` 生成词法池，词法池为空直接拒答，向量只合并进词法池；20 题完整覆盖可答 10 + 未知 5 + 越权/注入 5，可答 top5 10/10，私有 `task_report:99` 向量不能注入共享结果 | `KnowledgeHybridEvaluationTest`、`tmp/review-current-targeted2.log` |
| P2 工具超时 | 工具线程传播登录身份；`AssistantTool.execute(arguments, timeoutMillis)` 接收剩余预算；写预览在工具返回后、insert 前再次检查 deadline，超时不 insert；MyBatis-Plus 有效键与 5s 超时由 `SqlSessionFactory` 断言；底层 JDBC/Redis 物理终止与迟到 insert 仍未证明 | `AssistantChatServiceTest`、`AssistantPreviewServiceTest`、`AssistantConfirmationEndToEndIT`、`application.yml` |
| P1 R3 证据 | `CrashWindowChildMain` 写入真实 `request_json`/SHA-256/runtime_hash；新增真实 `TaskDispatchCoordinator` 重放测试，执行器收到一次、意图 `submitted`、任务 `running`、第二次恢复不重跑；修复 `DefenseTaskRequest`/`DefenseResourceRef` 缺少 Jackson 构造器导致的 `DISPATCH_SNAPSHOT_INVALID` | `MySqlTaskPersistenceIT.independentJvmPendingIntentRunsRealReplayCoordinatorOnce` |
| P2 监控口径 | `executorStatus.runningTaskCount` 改为 `taskProgressQueryService.statistics().runningCount`（MySQL `status=running`），线程池 `activeCount` 仍单独表示活动线程；运行监控页显示 `runningTaskCount` | `TaskProgressController`、`RuntimeMonitorView.vue` |
| 边界（当时） | 2026-11-15 `cde3173` 时 executor 仍是测试替身，未证明真实 Python 子进程接收、真实 GPU 租约表领取和双恢复者跨 JVM 竞争；Redis `VADD` 的整数回复由 `executeIntegerCommand` 处理，只忽略 Spring `ByteArrayOutput` 的已知解码限制，真实写入仍由 `VCARD/VSIM` 验证；哈希 embedding 不是神经语义。该 R3 边界已由 §5.15 的 `R3ProductionExecutorChainIT` 补齐；GPU/LDD 真实训练仍未执行 | 20 §10、ADR-022、ADR-025 |


## 5.13 `cde3173` 后五项边界修复（2026-11-15 历史记录；当时未整体验收）

| 项 | 事实 | 证据 |
| --- | --- | --- |
| P1 工具线程身份 | `AssistantChatService.toolExecutor` 改为 `DelegatingSecurityContextExecutorService` 包装的 4 线程 + 16 队列；工具线程调用真实 `CurrentUserService.requireStableIdentity()` 能读到请求用户；HTTP 测试 alice 任务 700、bob 任务 300 连续交替两次，工具结果 taskId 不串号 | `AssistantChatServiceTest.toolThreadReceivesAuthenticatedIdentity`、`AssistantConfirmationEndToEndIT.chatToolsKeepCallerIdentityAcrossPoolThreadReuse` |
| P1 最终外发体 | 新增 `ABSOLUTE_FILE_PATH`（允许路径含空格）并在 `UNIX_PATH` 前执行；`GENERIC_UNIX_PATH` 取消 `/api` 等前缀豁免，自由文本中所有绝对多段路径统一替换为 `[已过滤路径]`；真实 ChatService + 真实适配器 + 假 HTTP 连续两次请求，断言工具执行后的最终回答 body 也不含 `/datasets/private`、`/训练数据`、`/secret.txt`、`/api/private`、`/custom dir` | `KnowledgeSanitizer.java`、`KnowledgeSanitizerTest`、`AssistantOutboundPrivacyTest` |
| P2 SQL 超时与写预览 | `application.yml` 改为 MyBatis-Plus 有效键 `mybatis-plus.configuration.default-statement-timeout`；`AssistantConfirmationEndToEndIT` 从 `SqlSessionFactory` 读到的有效超时为 5；`AssistantPreviewService.preview(..., timeoutMillis)` 在工具返回后、`insert` 前再次检查 deadline，超时抛 `ASSISTANT_TOOL_TIMEOUT` 且不保存确认记录；用户可见文案改为“后续工具与模型调用不会继续；底层操作可能仍在收尾” | `application.yml`、`AssistantPreviewService.java`、`AssistantPreviewServiceTest`、`AssistantConfirmationEndToEndIT.myBatisPlusStatementTimeoutIsApplied` |
| P2 业务退役清理向量 | `KnowledgeIndexService.retire()` 在 `writer.retire` 成功后调用 `removeVectorsQuietly`；MySQL 仍是事实源，向量清理 best effort，失败只记日志；真实 Redis IT mock writer 返回 true 后调用真实 `retire(2L)`，断言 Redis 搜索不再命中 chunk 2 | `KnowledgeIndexService.java`、`KnowledgeVectorStoreRedisIT.retireBusinessEntryCleansItsVectors` |
| P2 候选范围与 20 题 | `KnowledgeRetrievalService` 先以 `candidateLimit` 生成 `lexicalPool`；词法池为空直接返回空；向量只允许合并进 `lexicalPool`，不从全部 SQL 可见候选合并；`vectorHitCount` 只统计 `row != null && score >= 0.28` 的可读命中；`KnowledgeHybridEvaluationTest` 补 5 个越权/注入题，形成可答 10 + 未知 5 + 越权/注入 5 = 20 题，并构造共享 corpus + 私有 `task_report:99` 向量，断言不会被注入 | `KnowledgeRetrievalService.java`、`KnowledgeHybridEvaluationTest` |
| 边界（当时） | `cde3173` 时的边界（insert 阻塞未证明、退役无补偿、R3 仍替身/同 JVM、无扩展名目录未覆盖）已由 §5.14/§5.15 补齐；R7 触发器是可控阻塞而非真实行锁，R6 故障是替身异常而非停 Redis。R3 已在 §5.15 使用完整 Spring 异步代理 + 真实 `PythonLddTaskExecutor` + 短协议桩，但仍未启动 LDD/GPU；R3/R6/R7 不能整体判定完成 | 20 §9/§10、ADR-023/ADR-024/ADR-025 |

## 5.14 `930c934` 后五项收口（2026-11-15 历史记录；当时未整体验收）

| 验收 ID | 实现方法 | 测试方法 | 实际依赖 | 证据 | 剩余限制 |
| --- | --- | --- | --- | --- | --- |
| P1 路径脱敏 | `KnowledgeSanitizer` 四层路径规则：带扩展名文件、无扩展名多段目录、单层目录、通用多段路径；目录段限定字母/数字/汉字/点/下划线/连字符，允许段内空格；替换顺序在 Windows/UNC 之后、PII 之前 | `KnowledgeSanitizerTest.redactsSingleAndSpacedDirectoriesWithoutExtensions` 断言 `/secrets`、`/custom dir/private directory`、`/数据目录`、`/2026/reports`；`AssistantOutboundPrivacyTest` 用真实 ChatService + DeepSeek 适配器 + 假 HTTP 连续两次请求，检查首轮工具请求和最终回答 body | 真实 `AssistantChatService`、真实适配器、本地假 HTTP、真实 `KnowledgeSanitizer` | 当时 `tmp/round2-p1-final.log`；当时全量 231+89，最新 232+96 与单字符/数字/隐藏目录证据见 §5.15 | 自由文本仍可能有未枚举形态；策略是保守过滤，不做路径存在性判断；不能替代 DLP |
| R3-01 服务层事务回滚 | 在真实 `RequestIdempotencyService.execute` 包裹的真实 `TaskCommandService.start` 中，于租约领取/task claim 后、`createPending` 前对 `ExecutionFingerprintService.buildRuntimeSnapshot` 注入 `IllegalStateException` | `TaskCommandServicePipelineIT.launchFailureRollsBackClaimLeaseIntentAndIdempotencyRecord` 断言任务回 `pending`、`current_run_id=null`、槽位无 token、intent 0、幂等 0、执行器零调用 | 真实 Spring 事务、真实任务 Mapper、真实 `sys_gpu_lease`、真实 `sys_request_idempotency`（含 `expires_at`）、真实协调器；执行器/资源目录为替身 | `tmp/round2-r3-01.log`；全类 7 项 `tmp/round2-r3-pipeline.log` | 只证明服务层事务；未证明数据库连接中断/提交阶段失败 |
| R3-02 新 JVM 恢复 | `R3RecoveryChildMain` 用真实 MyBatis + `UnpooledDataSource` 构建 mapper，真实 `GpuLeaseService`、`TaskDispatchIntentService`、`TaskDispatchCoordinator`；父进程写 running+pending，新 JVM `releaseByRun+acquire` 后 `replayPending`，执行短 Python 桩并 `markSubmitted` | `MySqlTaskPersistenceIT.newJvmRecoversPendingIntentWithRealLeaseAndPythonStub` 断言任务 running、intent submitted、租约 run_id 一致且 token 非空、`sys_r3_execution_evidence` 1 条、`exit_code=0`、`python_pid>0`、子进程输出 `RECOVERED=true` | 真实 MySQL 表/租约、真实 Python 进程、真实 MyBatis；runtime 指纹/用户/资源 Mapper 为测试子类/代理 | `tmp/round2-r3-02.log` | 不是完整 Spring 上下文；执行器是协议桩，不启动 LDD/GPU；未覆盖 KILL_FAILED |
| R3-03 双派发竞争 | 同一 pending intent、同一真实租约行，两个新 JVM 同时 `replay`；条件 `pending→submitting` 只有一个成功 | `MySqlTaskPersistenceIT.twoNewJvmsRaceSamePendingIntentOnlyOneDispatches` 断言一个 `RECOVERED=true`、一个 `false`，intent 最终 submitted，evidence 仅 1 条，租约 token 非空 | 真实 MySQL 条件更新、两个真实 JVM、两个短 Python 桩 | `tmp/round2-r3-03.log` | 是同一台机器上的双 JVM，不是多实例网络分区；未测时钟偏移 |
| R3-04 接受后记账前崩溃 | 父进程写 running+submitting intent、真实长睡眠 Python、`process.json` 但故意无 `python-children.json`；新 JVM `recoverAtStartup` 扫描得到 `UNKNOWN`，`markBlocked`+`failAndAbandon`，不重放 | `MySqlTaskPersistenceIT.acceptedBeforeBookkeepingCrashKeepsSlotQuarantinedWhileProcessUnconfirmed` 断言任务 failed、intent abandoned、`blocked_at` 非空、`lease_token` 仍为旧 token、evidence 0、Python 仍存活 | 真实 MySQL、真实 `DispatchRecoveryCoordinator`、真实长睡眠 Python 进程、真实 `AttemptProcessRegistry` 文件 | `tmp/round2-r3-04.log` | “进程树未确认”用缺 `python-children.json` 制造；未覆盖杀进程失败/KILL_FAILED 的实际路径 |
| R7 insert 超时 | 三个 `preview` 重载加事务；insert 后再检查 deadline，跨过则抛 `ASSISTANT_TOOL_TIMEOUT` 并回滚；文案明确“确认记录未保存” | 真实 MySQL `BEFORE INSERT` 触发器 `SLEEP(0.8)`，100ms 预算调用真实 `AssistantPreviewService.preview`；断言超时、确认表 0 行、工具审计 1 条 FAILED、任务仍 running | 真实 MySQL 触发器、真实 `AssistantPreviewService`、真实确认/审计表 | `tmp/round2-r7-a.log` | 触发器是可控阻塞，不是真实行锁/表锁等待；只覆盖 `preview` 插入路径 |
| R7 错误 token | 确认服务重校验 token；篡改首字符返回 403 `ASSISTANT_CONFIRMATION_INVALID`，写 `TOKEN_MISMATCH` 审计，确认记录保持 pending | `AssistantConfirmationEndToEndIT.tamperedTokenCannotConfirmAndIsAudited` | 真实 HTTP、真实 MySQL 确认/审计表、真实服务 | `tmp/round2-r7-a.log` | 未覆盖 token 哈希碰撞/数据库泄露后的离线重放 |
| R7 并发身份隔离 | `AssistantChatService` 工具线程用 `DelegatingSecurityContextExecutorService`；alice/bob 同时调用 `/api/assistant/chat`，各自查询任务 700/300 | `AssistantConfirmationEndToEndIT.concurrentAliceAndBobChatsKeepIdentityIsolated` 断言响应各自 taskId 正确 | 真实 HTTP、真实线程池、真实 `CurrentUserService`、真实任务查询 | `tmp/round2-r7-b.log` | 只覆盖单进程线程复用，不是多实例会话 |
| R6 退役补偿 | `removeDocument` 返回 boolean 并逐 VREM 汇总；`reconcileRetiredVectors(limit)` 对 `findRetired` 有界扫描重新清理；`KnowledgeVectorReconcileJob` 300s/60s/50 有界对账 | `KnowledgeRetrievalMySqlIT.retireSurvivesRedisFailureThenReconcilesResidualVectors`：真实 MySQL + 真实 Redis 8；`FlakyVectorStore` 首次抛 `simulated-redis-outage`，断言退役立即生效、Redis 残留、恢复后清掉、重复对账安全 | 真实 MySQL schema、真实 `KnowledgeDocumentWriter`、真实 `StringRedisTemplate`、真实 Redis 8 | `tmp/round2-r6.log` | 故障是替身抛异常，不是实际停 Redis 进程；补偿是有界扫描不是 outbox，重复扫描会重复尝试但幂等；MySQL 始终是事实源 |
| N6 Java 17/CI | Maven `maven.compiler.release=17`；CI 为 Ubuntu + Temurin 17 + MySQL 8.4 + Redis 8.0 | 当时（`930c934`）本机 Temurin 17.0.21 下 `mvn verify -Pmysql-it` 通过（231 单测 + 89 IT）；本机 JDK20 同计数通过；远端 `930c934` 的 Migration Quality 曾因既有测试硬编码 `cmd`/`ping -n` 与 `PythonProcessWaiter` 销毁后未等待退出而失败（run `43686578054`）；跨平台修复后推送提交 `106e4d3` 触发的新 run `43707281672` 已全部 success。2026-11-15 收口提交 `bb4a79f` 的 run `43726078023` backend/frontend/python 全绿；最新 232 + 96 见 §5.15 | 本机 JDK17 `tmp/round3-full-verify-jdk17-final.log`、JDK20 `tmp/round3-full-verify-final.log`；GitHub Actions run `43726078023`；`tmp/ci-bb4a79f-backend.log` | 远端 CI 已通过，但生产部署的 Java 17 运行时仍以部署环境为准；本机 Windows 与 Ubuntu 环境差异不能由本机结果替代 |


<!-- /knowledge:historical -->

## 5.15 `08f11c0` 后 P1/P2/R3 收口（2026-11-15，本机 + 远端 CI）

| 验收 ID | 实现方法 | 测试方法 | 实际依赖 | 证据 | 剩余限制 |
| --- | --- | --- | --- | --- | --- |
| P1 单段绝对路径脱敏 | `ABSOLUTE_DIRECTORY_PATH` 中间段空格收紧为 `{0,1}`，避免跨 token 吞并正文；`ABSOLUTE_SINGLE_SEGMENT` 首字符允许字母/数字/下划线/点/汉字，后续允许字母/数字/点/横线、最多两个空格 token；替换顺序保持 file → directory → single → UNIX → generic | `KnowledgeSanitizerTest.redactsSingleCharNumericHiddenAndSpacedDirectories` 断言 `/z`、`/123`、`/.git`、`/.config/cache`、`/custom dir`、`/single 字 目录` 被替换，`and/or`、`1/x`、`3/4` 保留；`AssistantOutboundPrivacyTest` 两轮最终 body 断言 `/z`、`/123`、`/.git`、`/custom dir` 不存在 | 真实 `AssistantChatService`、真实适配器、本地假 HTTP、真实 `KnowledgeSanitizer` | `tmp/round3-p1.log`（9 + 1） | 仍是正则形态过滤，不能穷举自由文本隐私形态；不替代 DLP |
| P2 退役向量清理饥饿 | `KnowledgeDocument` 新增 `vectorCleanupStatus/Attempts/NextAt/UpdatedAt`；退役置 `pending` 且立即到期；`findRetiredForVectorCleanup(now, limit)` 只取 `retired + pending/failed + 到期`，按 `COALESCE(next_at, updated_at), document_id` 公平排序；成功 `clean` 清空 `next_at`，失败 `failed` 并 `60s << min(6, attempts)` 退避（上限 3600s）；单条失败不阻塞其他行 | `KnowledgeRetrievalMySqlIT.retiredVectorCleanupAdvancesPastFailuresAndSurvivesRestart`：真实 MySQL + Redis，5 条退役、批量 2，首批 doc1 失败/doc2 成功，后续批次取 doc3/doc4/doc5，重启后拨回 `next_at` 可继续，最后再跑返回 0；`retireSurvivesRedisFailureThenReconcilesResidualVectors` 继续通过 | 真实 MySQL schema、真实 Redis 8、真实 `KnowledgeDocumentMapper`/`KnowledgeIndexService` | `tmp/round3-p2.log`（6/6） | 退避参数当前固定在代码中，未暴露配置键；对账是进程内定时任务 + 数据库状态，不是 outbox |
| P2 清理迁移脚本 | 基础表加四列与索引；增量脚本用 `information_schema` 动态补列/建索引/回填 retired 行；回滚脚本动态删索引/删列；索引存在性按列计数 `>0` 判断，兼容多列索引 | `KnowledgeVectorCleanupMigrationIT.upgradeIsRepeatableBackfillsRetiredRowsAndRollsBackCleanly`：隔离 schema 升级两次、回滚两次、再升级，断言列/索引存在与消失、retired 行 `pending` 且 `next_at` 非空 | 真实 MySQL 随机隔离 schema | `tmp/round3-p2-migration.log`（1/1） | 只在隔离库执行；生产 `eladmin` 未迁移；回滚要求停机且无活跃任务 |
| R3 生产执行器真实链路 | `R3ProductionExecutorChainIT` 使用完整 `@SpringBootTest`、真实 `@Async("taskAsync")` 代理、真实 `PythonLddTaskExecutor`、真实事务/Mapper/租约/协调器/意图与幂等表；仅 `ResourceQueryService` 为 `@MockBean`，Python 短协议桩不跑 LDD/不占 GPU | 5 项：正常完成（success/progress 100/intent submitted/租约释放/子进程退出）、取消（canceled/intent submitted-abandoned/子进程退出/租约释放）、超时（failed/TASK_TIMEOUT/子进程退出/租约释放）、非法结果（failed/RESULT_PROTOCOL_ERROR/无 success/子进程退出/租约释放）、真实崩溃窗口 | 真实 Spring 上下文、真实异步代理、真实执行器、真实 MySQL 隔离 schema、真实 Python 协议桩 | `tmp/round3-r3-prod.log`（5/5） | 短协议桩不运行 LDD/GPU；不是真实算法训练验收 |
| R3 真实崩溃窗口 | `R3ProductionCrashChildMain` 用真实 MyBatis 任务/意图/租约、真实执行器、真实协调器；测试子类在 `markSubmitted` 写 `crash-ready-920` 后阻塞；父测试等 marker 与 `process.json` 后 `destroyForcibly` 杀子 JVM；`R3RecoveryChildMain` 识别 `submitting`，杀孤儿、释放租约、task failed、intent abandoned | `R3ProductionExecutorChainIT.crashAfterAcceptBeforeBookkeepingRecoversWithoutReplayOrReassigningTheSlot` 断言 Python pid 已死、租约 token 为空、`executions.log` 仅 1 行 | 真实 MySQL、真实派发协调器、真实执行器、真实 Python 子进程、独立 JVM 恢复 | `tmp/round3-r3-prod.log` | 崩溃子 JVM 是测试最小上下文 + 手动异步包装，不是完整 Spring 容器；单机进程崩溃，不是网络分区/多实例 |
| N6 门禁 | 2026-11-16 四项阻断修复后 Temurin JDK17.0.21 跑全量 `mvn verify -Pmysql-it`（`REDIS_IT=true`、`RABBIT_IT=true`） | 本机 **287 单测 + 106 IT**，BUILD SUCCESS（`tmp/stage2-full-verify-new.log`）；含 `RabbitDispatchBrokerIT` 2/2、`WorkerConsumerLifecycleTest` 8/8、`DispatchMessageHandlerTest` 12/12、`RabbitDispatchTransportTest` 5/5、`MySqlTaskPersistenceIT` 28/28、`KnowledgeRetrievalMySqlIT` 8/8；Python 11/11；前端构建历史通过；此前 277+104 为阶段 2 收尾版，267+103 为 R4 六项整改版，263+100 为旧代码，232+96 为更早基线（`bb4a79f` run `43726078023`） | 本机 Temurin 17.0.21、MySQL/Redis/RabbitMQ、Node；GitHub Actions | `tmp/stage2-full-verify-new.log`、`tmp/stage2-full-verify-final3.log`、`tmp/round5-r4-python-tests2.log` | 前端仍有 >500 kB chunk 警告；生产库 `eladmin` 未迁移；green CI 不等于 R4 全部故障场景通过 |

### 5.15.1 远端 CI 回归与修复（2026-11-15）

- `5e4efd4` 的 run `43712479071`：frontend、python success，backend failure。232 项单测中 2 项知识评估用例在 Ubuntu/LF 环境把一条未知题误命中 `sdd:01-system-scope.md:6`，IDF 加权覆盖率 `0.25044` 恰好越过旧阈值 `0.25`；日志 `tmp/ci-5e4efd4-backend.log`。
- 修复不是把问题文本写进语料：ADR-025 只记录“一条未知题”的覆盖率事实，不复制原问题；同时把 `min-coverage` 默认值从 `0.25` 收紧到 `0.30`，并同步 `application.yml`、`KnowledgeRetrievalService`、两份评估测试与 `05/06/09` 文档。固定评估仍为可答 10/10、未知 0 命中。
- 同轮修复 `R3ProductionExecutorChainIT.cancelStopsTheRealChildAndDoesNotLeaveARecoverableIntent` 的测试竞态：DB 状态先到 `canceled` 时子进程可能仍在退出，改为最多等 20 秒确认 pid 消失后再断言，未放宽生产端“确认退出才释放槽位”的语义。
- 推送提交 `bb4a79f` 的 run `43726078023`：backend 232 单测 + 96 IT、frontend、python 全绿；日志 `tmp/ci-bb4a79f-backend.log`。该结果证明隔离环境 CI，不等于生产库迁移或真实 LDD/GPU 验收。

### 5.17 R4 真实 Broker、双 JVM 与短 Python 桩验收（2026-11-16）

| 项 | 当前事实 | 证据/位置 |
|---|---|---|
| 环境 | 官方 Erlang OTP 27.3.4.18 安装至 `C:\Program Files\Erlang OTP`；RabbitMQ 4.1.8 解压到 `tmp\rabbitmq-4.1.8\rabbitmq_server-4.1.8`；节点 `r4test@localhost`，AMQP `127.0.0.1:5673`，管理 `127.0.0.1:15672`；隔离 schema/vhost `security_model_r4_20261116a`；账号仅本地使用，凭据不入 Git/日志 | `tmp\r4-secrets.json`、`tmp\r4-rabbitmq\config\rabbitmq.conf`、`tmp\r4-rabbit-start2.log` |
| 数据库 | 隔离 schema 应用 47 张表，含 Outbox/消费/意图/租约；`R4.PrepareDatabase.ps1` 通过；未改 `eladmin` | `tmp\r4-db-prepare6.log` |
| 双 JVM | API `18100`、Worker `18101`，各自 `/actuator/health` UP；Worker 取得 MySQL `GET_LOCK` 后恢复、恢复完成才启动消费容器；API `RABBITMQ_DYNAMIC=false` 仅用于“不可路由”测试，不自动重建已删除队列 | `tmp\r4-stack-start5.log`、`tmp\r4-stack\worker.log` |
| R4-01 | 正常任务 `2122209791151980545` success/progress 100；Outbox published attempts 1；consumption terminal/success；GPU lease released；Python 1 次。停 Broker 后任务 `2122210212130078722` Outbox 先 failed（Connection refused），重启 Broker 后 Relay 重试至 published（attempts 3），任务 success | `tmp\r4-stack\api.log`、`tmp\r4-stack\worker.log`、`tmp\r4-stack\taskid.txt`、`tmp\r4-stack\taskid-brokerdown.txt` |
| R4-02 | 管理 API 重发同一 payload，`routed=true`，Python 启动次数不增，consumption 保持 terminal/success；删除 worker 队列且 API 关闭 RabbitAdmin 自动声明后，任务 `2122214846991208449` 发布得到 `basic.return NO_ROUTE`，Outbox 立即 dead、task failed、intent abandoned，**未进入 published**；本地 AMQP 代理把 Broker `Basic.Ack` 延迟 7 秒（应用 confirm 超时 5 秒），任务 `2122269157720920066` 的 Outbox 连续 3 次 `RABBIT_PUBLISH_FAILED` 后 dead，task 保持 running，未把超时写成成功 | `tmp\r4-stack\workspace\r4-python-invocations.jsonl`、`tmp\r4-stack\api.log`、`tmp\r4-confirm-proxy.log`、`tmp\r4-02-late-dead-row.txt`、`tmp\r4-02-late-api-lines.txt`、`tmp\r4-stack\taskid-unroutable6.txt` |
| R4-03 | Worker 执行 sleep 300 秒任务 `2122215986243866625`，强杀 Worker PID 14424 后 Python PID 4736 仍存活；重启 Worker 执行恢复，日志 `Killed orphaned executor process tree ... pid=4736`；任务 failed、intent abandoned、consumption terminal `DISPATCH_UNCERTAIN_RECOVERY`、无新 attempt、租约行 0 | `tmp\r4-stack\worker.log`、`tmp\r4-stack\taskid-orphan.txt` |
| R4-04 | 人工占用 `default-gpu-0` 后任务 `2122216323369439234` 三次 5/30/120 重试耗尽；task failed、intent abandoned、consumption terminal failed、Outbox dead、DLQ 1。发布 `not-json` 毒消息后 Worker 拒绝，DLQ 增至 2 | `tmp\r4-stack\taskid-gpubusy.txt`、`tmp\r4-stack\worker.log`、Rabbit 管理 API |
| R4-04（自动恢复补充） | Worker 经 `13306` TCP 代理连 MySQL，任务 `2122257618284634114` 的 sleep 短桩启动后切断代理：`17:17:13` 归属连接失效、`17:17:18` 停止消费并关闭调度门；`17:17:25` 恢复代理但**未重启 Worker**，`17:17:28` 自动重取 `GET_LOCK`、重跑恢复扫描，因本地 accepted=1 持续拒绝开消费（`17:17:28/33/38/43` 四次门控），任务 `17:17:46` success、accepted 清零后 `17:17:48` 重跑一次恢复并启动消费容器。任务 success、run `ca09e4d5-cd98-418f-adb1-241228c4834b`、attempt 1、progress 100、intent submitted、Outbox published 1、consumption terminal `TASK_SUCCESS`、Python 1 次 | `tmp\r4-db-auto-recover3-worker.txt`、`tmp\r4-db-auto-recover3-db.txt`、`tmp\r4-stack\workspace\r4-python-invocations.jsonl` |
| R4-05 | 任务 `2122210811173199873` 启动 2 秒后取消：task canceled、consumption terminal/canceled、GPU lease released、Python PID 32272 不存在、Python 1 次 | `tmp\r4-stack\taskid-cancel.txt` |
| R4-06 | 独立 JVM `mode=db role=worker` 启动 20 秒内退出码 1，异常 `db 模式只允许 role=api|all`；30 秒 sleep 任务运行期间 `R4.DrainCheck.ps1` 以退出码 1 拒绝切换；停机把 Worker 队列切到 `.v2` 后，旧队列 `security-model.dispatch.worker` 留 1 条、0 consumer，新 `.v2` 0 条、1 consumer。**完整停机切换**：`db` API 任务 `2122266301773209601` success → 停 API（停止接收）→ drain `unpublished=0 publishing=0 processing=0 running=0` → `rabbit` API+Worker 启动 → 任务 `2122266483373920257` success（Outbox published 1、consumption terminal/success、Python 1 次）→ 停 API、drain → 停 Worker → 切回 `db` API → 任务 `2122266707249192962` success；两个方向均无第二消费账本/双派发 | `tmp\r4-06-drain-db.txt`、`tmp\r4-06-drain-rabbit.txt`、`tmp\r4-06-db-task.json`、`tmp\r4-06-rabbit-task.json`、`tmp\r4-06-db-back-task.json`、`tmp\r4-06-db-rows.txt`、`tmp\r4-mode-reject2.log`、`tmp\r4-drain-refusal2.log`、`tmp\r4-old-queue-isolation.json` |
| R4-07 | Worker 执行 sleep 任务 `2122210676426948609` 时重启 API；Worker 不中断，任务 success、Outbox published 1、consumption terminal success、Python 1 次 | `tmp\r4-stack\taskid-apirestart.txt` |
| R4-08 | 对已 failed 任务死信 `abe21c55...` 重放，admin 返回 `DLQ_REPLAY_REQUIRES_NEW_ATTEMPT`；`test` 用户同一路径返回 HTTP 403；任务 `2122226296115777538` 在 Broker 停止时 Outbox `c871a5ac...` 自然耗尽为 dead（task running/intent pending），重启 Broker 后重放为 `a7696410...`，任务 success、intent submitted、consumption terminal `TASK_SUCCESS`、Python 1 次；并发任务 `2122231820257357826` 自然 dead 后同时 replay + cancel，replay 返回 `f310da09...`、cancel 成功，终态 canceled、consumption terminal、Python 0 次、审计 4 行。**旧 dead 与新 attempt 真实竞争**：任务 `2122268541581815809` 的 attempt 1 因 `RABBIT_UNROUTABLE` 得到 dead `74532340...`、task failed；重试创建 attempt 2 并运行（sleep 30s）后调用重放，HTTP 400 `DLQ_REPLAY_IDENTITY_MISMATCH`，attempt 2 最终 success、consumption terminal/success，旧 dead 保持 dead，Python 仅 attempt 2 一次 | `tmp\r4-natural-dead-db1.txt`、`tmp\r4-natural-dead-replay.json`、`tmp\r4-natural-dead-db2.txt`、`tmp\r4-race-results.json`、`tmp\r4-race-db-dead.txt`、`tmp\r4-race-db-after.txt`、`tmp\r4-race-audit.txt`、`tmp\r4-08-unroutable3-task.json`、`tmp\r4-08-replay-response.json`、`tmp\r4-08-new-attempt-rows.txt`、`tmp\r4-08-new-attempt-final.json` |
| 未执行/边界 | R4-02 confirm 延迟已用真实 AMQP 代理注入（7s > 5s，3 次失败 dead、task 保持 running）；R4-06 完整停机切换已执行 `db→rabbit→db` 双向；R4-08 旧 dead 与新 attempt 真实 HTTP/Broker 竞争已执行（HTTP 400 identity mismatch、Python 仅新 attempt 一次）；仍缺 R4-04 长期 DB 故障/多任务退避压力、默认 db 模式全量回归的远端独立复现；本轮提交 `ee10704`/`cd5a6ae`，run `43846823071` 全绿 | `tmp\r4-confirm-proxy.log`、`tmp\r4-06-*`、`tmp\r4-08-*`、`20` §6.8、`21` §16 |

### 5.18 阶段 5 评审四项收口批次（2026-11-17）

| 项 | 当前事实 | 证据/位置 |
|---|---|---|
| P1 初始化空库保护 | `InitializeSecurityModelDatabase.ps1` 统计目标 schema 的 `information_schema.tables`（含视图），只有 `sys_dev_init_marker.schema_name` 匹配才算可信 marker；`markerRecordCount=0 && existingTableCount>0` 直接拒绝，不执行带 `DROP TABLE` 的 `sql/eladmin.sql`。本批次初版保护测试随机 schema 只建 `sys_user`，运行复制后只替换 schema 字面量的真实脚本，断言非零退出并输出“没有可信初始化标记”，测试后删除 schema；§5.19 已扩展为两张表 + 哨兵行/表数不变 | `scripts/dev/InitializeSecurityModelDatabase.ps1`、`scripts/dev/Test-InitializeSecurityModelDatabaseGuard.ps1`；本机通过 |
| P2 数据集不再伪造 success | `security_model_vab_seed.sql` 数据集改为 `pending`，`sample_count/feature_count/class_count/parse_time` 全 `NULL`；说明写明 50000/3072/10 只是预期值，管理员执行 `POST /api/dataset/{id}/validate` 后才进可用列表。初始化脚本在提供 VaB 根时校验目录存在、可读、非空及 `cifar-10-batches-py` 下 `batches.meta`/`data_batch_1..5`/`test_batch` 均非空。`security_model_dataset_verification_baseline.sql` 把 `sql/eladmin.sql` 历史样例与阶段 5 目录登记重置为 pending；管理员真实验证后不覆盖 | `sql/security_model_vab_seed.sql`、`sql/security_model_dataset_verification_baseline.sql`、`scripts/dev/InitializeSecurityModelDatabase.ps1`、`06` §初始化顺序 |
| P2 种子纯 additive | 删除数据集/算法/模板/旧 VS 算法启用状态的全部 `UPDATE`；只对缺失行 `INSERT`，重复执行不改路径、参数、启用状态、更新时间。旧 VS 算法不再被种子禁用。需要更新系统默认值时走显式迁移/重置，不在初始化中隐式覆盖 | `sql/security_model_vab_seed.sql`、`VabSeedPortabilityTest` 5 项、`VabSeedMySqlIT` 5 项 |
| legacy running 基线（已被 §5.19 收紧） | 干净库验收发现 `sql/eladmin.sql` 自带 2 条 2025 demo 任务为 `running` 但没有 run/attempt；启动恢复 `failLegacy` 返回 `UNKNOWN`，把 GPU 槽位标 blocked 并关闭调度门。本批次基线仅按 `status='running' AND current_run_id IS NULL AND current_attempt IS NULL` 收敛；§5.19 进一步收紧为精确种子 ID 3/4 + 名称 + `create_by=test`，其他行不动并输出审核清单 | `sql/security_model_legacy_running_task_baseline.sql`、`VabSeedMySqlIT`（现测试名见 §5.19） |
| P2 执行配置名与预检 | `DefenseTaskExecutor.isConfigured()` 默认 true；`PythonLddTaskExecutor` 以 `StringUtils.hasText(pythonScript)` 覆写，异常文案统一为 `security-model.executor.python.script`（环境变量 `SECURITY_MODEL_EXECUTOR_PYTHON_SCRIPT`），不再出现短名 `SECURITY_MODEL_PYTHON_SCRIPT`。db 模式 `start` 在 `nextAttempt`/租约/快照/意图之前检查，`retry` 在 `resetForRetry` 之前检查；Assistant 确认复用同一命令服务；恢复 `replayPending` 在 `markSubmitting`/重取租约前检查；Rabbit Worker `consume` 在 claim 前检查；`dispatchAfterCommit` 增加防御性检查。Rabbit API 不检查自身 Python 配置，由 Worker 承担 | `DefenseTaskExecutor`、`PythonLddTaskExecutor`、`TaskCommandException`、`ApiExceptionHandler`、`PreDefenseTaskCommandService`、`TaskDispatchCoordinator`、`07-executor-contract` |
| 测试 | 定向单测 `PreDefenseTaskCommandServiceTest` 6 + `TaskDispatchCoordinatorTest` 13 + `VabSeedPortabilityTest` 5 = **24 项，0 失败/0 错误**（`tmp/stage5-closeout-targeted-tests.log`）；真实 MySQL `VabSeedMySqlIT` **5/5**（`tmp/stage5-closeout-mysql-it.log`），覆盖无 root 只注册算法/模板、重复幂等、数据集 pending/NULL、换 root 不覆盖、运维修改后重跑保留、历史样例重置后真实验证不覆盖、legacy running 收敛与真实 run 行保护。测试用 `EncodedResource(..., UTF_8)` 读取中文 SQL，避免 Windows JVM 默认编码把 `解析成功%` 读坏 | `PreDefenseTaskCommandServiceTest`、`TaskDispatchCoordinatorTest`、`VabSeedPortabilityTest`、`VabSeedMySqlIT` |
| 全量门禁 | 2026-11-17 本机 `verify -Pmysql-it`（`MYSQL_IT_PASSWORD`、`REDIS_IT=true`）：**305 单测 + 111 IT，0 失败/0 错误/2 跳过（仅 `RabbitDispatchBrokerIT` 未设 `RABBIT_IT`）**，BUILD SUCCESS（`tmp/stage5-closeout-full-verify.log`）；前端 `npm run build` 13.08s 通过（element chunk 947.92 kB 警告仍在，`tmp/stage5-closeout-frontend.log`）；Python 11/11（`tmp/stage5-closeout-python-tests.log`）；真实非空库保护脚本通过，schema `security_model_guardtest_63086054b743`（`tmp/stage5-closeout-guard.log`）。为让新增门禁不误伤测试，给 `TaskCommandServicePipelineIT`、`AssistantConfirmationEndToEndIT`、`MySqlTaskPersistenceIT` 的 executor Mockito 桩补 `isConfigured=true`；同时修正 `DashboardOverviewServiceTest` 用整段响应字符串匹配“4.7”导致毫秒级时钟误报的脆弱断言 | `tmp/stage5-closeout-full-verify.log`、`tmp/stage5-closeout-targeted-it.log`、`tmp/stage5-closeout-targeted-tests.log`、`tmp/stage5-closeout-frontend.log`、`tmp/stage5-closeout-python-tests.log` |
| 干净库业务验收 | 随机 schema `security_model_accept_20261117124258` 用真实初始化脚本建 48 表，18103 以 db/api 启动。无脚本时普通 API `start` 400 `EXECUTOR_NOT_CONFIGURED`，任务仍 pending、无 run/attempt/快照/意图/租约；Assistant `preview_start_task` 200 → 确认 400 同码、任务仍 pending；failed 任务 `retry` 400 同码且 status/attempt/progress/run 不变；`test` 用户对 admin 任务详情/start 均 403。短协议桩成功任务 `2122553484081897474` success/attempt 1，报告 200、导出 ratio 75 success、下载 165 bytes、artifact preview 200；取消任务 `2122553662822162433` 观察到 running 后 canceled、GPU idle、租约已释放。**短桩与合成产物，不是真实 LDD/GPU** | `tmp/accept-closeout/*`、`tmp/stage5-closeout-noscript.log`、`stage5-closeout-stub-success.log`、`stage5-closeout-cancel.log`、`stage5-closeout-failed.log`、`stage5-closeout-retry.log` |
| 未执行/边界 | 真实 LDD/GPU 未运行；`start/retry/Assistant 确认` 的配置拒绝已做真实 HTTP+MySQL，**恢复派发与 Rabbit Worker 的同一规则只在单测覆盖，干净库未做端到端故障注入**；生产库 `eladmin` 未改、`sql/eladmin.sql` 未改；新增案例库表未做；旧工程归档未做，按用户要求独立提交。收口代码提交 `d025150`，本次文档提交随同推送，CI 结果见 §9 | `06` §7、`07`、`10`、`19`；全量门禁见 §9 |

### 5.19 初始化链路幂等与保护收口（2026-11-17）

| 项 | 当前事实 | 证据/位置 |
|---|---|---|
| legacy running 精确化 | 基线只处理 `sql/eladmin.sql` 种子任务：`task_id=3` + `MNIST数据预处理`、`task_id=4` + `文本数据清洗任务`，且 `create_by='test'`、无 `current_run_id/current_attempt`。置 `failed/progress=0/EXECUTOR_RESTARTED`，`failure_reason` 明确“是否重试需人工审核”，不再写“可安全重试”。其他 running 无上下文行不改，初始化输出 `WARNING` 审核清单 | `sql/security_model_legacy_running_task_baseline.sql`、`InitializeSecurityModelDatabase.ps1`、`VabSeedMySqlIT.onlySeedLegacyRunningRowsAreFailedAndUnknownRowsAreUntouched` |
| MVP 种子 additive | `security_model_mvp_seed.sql` 删除 builtin 算法禁用、`321` 算法/模型废弃、菜单显隐共 5 条 `UPDATE`，只保留缺失才插入。历史行为移入不自动执行的 `sql/security_model_mvp_legacy_convergence.sql`；builtin 目录仍展示，`PreDefenseTaskCommandService` 仅接受 `vab://pre-defense/ldd`，创建/启动非 LDD 算法直接拒绝 | `security_model_mvp_seed.sql`、`security_model_mvp_legacy_convergence.sql`、`PreDefenseTaskCommandService.resolveCommand`、`VabSeedPortabilityTest.mvpSeedIsAdditiveAndHistoricalChangesAreExplicit` |
| MySQL 连接参数统一 | 初始化脚本新增 `-DbHost`/`-Port`/`-MysqlExe`，所有 SQL 调用与 `SOURCE` 都使用同一连接；保护测试把 host/port/exe 透传给真实脚本。拒绝后断言表数、`sys_user` 哨兵行、`sys_sentinel` 哨兵行和 marker 表数量均未变化 | `InitializeSecurityModelDatabase.ps1`、`Test-InitializeSecurityModelDatabaseGuard.ps1`；本机 schema `security_model_guardtest_0674098df61b`，`tmp/stage5-init-guard.log` |
| 完整初始化幂等 | 新增 `Test-InitializeSecurityModelDatabaseIdempotency.ps1`：隔离 schema 完整初始化两次，比较 `sys_dataset/sys_algorithm/sys_hyper/sys_pre_defense/sys_model/sys_menu/sys_dev_init_marker` 全部业务行（含 `update_time`）逐字一致；再修改数据集路径/统计、LDD 算法与模板参数/启用状态、builtin 启用状态、菜单显隐，插入未知 running 行，第三次初始化后上述修改和哨兵行全部保留，未知行仍 `running` 且只在审核输出出现 | 本机通过，schema `security_model_initidem_5424c303f986`、48 表；`tmp/stage5-init-idempotency.log`；脚本 `scripts/dev/Test-InitializeSecurityModelDatabaseIdempotency.ps1` |
| 测试/门禁 | `VabSeedPortabilityTest` **6/6**（新增 MVP additive 与精确 legacy 断言，`tmp/stage5-init-portability-test.log`）；`VabSeedMySqlIT` **5/5**（legacy 用例改为精确种子 + 未知行不动，`tmp/stage5-init-mysql-it.log`）；全量 `verify -Pmysql-it`（`REDIS_IT=true`）**306 单测 + 111 IT，0 失败/0 错误/2 跳过（仅 Rabbit Broker）**，BUILD SUCCESS，`tmp/stage5-init-full-verify.log` | 同左 |
| CI | workflow 触发范围加入 `scripts/dev/**`，新增 `init-scripts` job（MySQL 8.4 service + pwsh），依次运行保护测试与完整初始化幂等测试；run `44033400651`（head `61a32ad`）backend/frontend/python/init-scripts **四个 job 全部 success**。此前两次失败均为 Ubuntu 下 PowerShell 对长拒绝消息折行/插入管道符导致断言读不到 `已有 2 个表或视图`，已改为压缩空白后匹配 | `.github/workflows/migration-quality.yml`、`Test-InitializeSecurityModelDatabaseGuard.ps1` |
| 边界 | `isConfigured()` 仍只判断脚本配置非空，不检查脚本文件是否存在或解释器可用；脚本存在/解释器错误仍由实际进程启动失败收敛。真实 LDD/GPU 未运行；未知历史行只输出审核清单，不自动重试。旧 `eladmin/`、`eladmin-web/` 已归档到 Tag `migration-r4-verified` 与 `legacy` 分支，并在当前开发分支以独立提交删除；本地 `main` 尚未合入；`sql/eladmin.sql` 保留 | `DefenseTaskExecutor`/`PythonLddTaskExecutor`、`06` §7、`10`、`22` §5 |

### 5.20 旧工程归档与干净 worktree 验证（2026-11-17）

| 项 | 结果 | 证据 |
|---|---|---|
| 归档锚点 | annotated Tag `migration-r4-verified` 指向 `4dd8005`（初始化链路 CI 全绿后的文档提交） | Tag 已推送远端 |
| 归档分支 | `legacy` 分支指向 `4dd8005`，包含 `eladmin/`、`eladmin-web/` 完整 685 个源码文件 | 已推送；可按 Tag/分支检出 |
| 当前开发分支删除 | 独立提交 `7e31b3f` 在当前开发分支删除两个目录，693 files changed、73102 deletions（含 8 个文档更新）；本地 `main` 尚未合入，合并前主分支历史仍保留；`sql/eladmin.sql` 保留 | `git show --stat 7e31b3f` |
| 干净 worktree | 在 `7e31b3f` 创建 detached worktree：当前开发分支下 `eladmin`/`eladmin-web` 均不存在，`sql/eladmin.sql` 存在；本地 `main` 尚未合入该归档提交 | `tmp/archive-clean-verification.txt` |
| 构建与测试 | 干净 worktree 全量 `verify -Pmysql-it`：**306 单测 + 111 IT，0 失败/0 错误/2 跳过（仅 Rabbit Broker）**，BUILD SUCCESS，2:26 | `tmp/archive-clean-full-verify.log` |
| 初始化保护/幂等 | 干净 worktree 上 guard 通过（schema `security_model_guardtest_ad4633a1a709`，2 表/哨兵不变）；完整初始化幂等通过（schema `security_model_initidem_9a026f4797e1`，48 表，两次逐字一致、修改保留、未知行不动并审计） | `tmp/archive-clean-guard.log`、`tmp/archive-clean-idempotency.log` |
| 启动说明 | 用隔离 schema `security_model_archive_start_2004c3dd` 初始化 48 表后启动打包 jar，`DB_NAME` 指向该 schema、端口 18199；`/actuator/health` 返回 `UP`，随后停止并删除 schema | `tmp/archive-clean-init-start.log`、`tmp/archive-clean-start.out.log` |
| 文档依赖 | README、项目解析文档、02/06/11/14/22 已移除旧构建命令，统一说明 Tag/分支回退；CI 不引用旧目录 | 本提交文档 diff |
| 归档提交 CI | run `44035312196`（head `7e31b3f`）backend/frontend/python/init-scripts **四个 job 全部 success**；`init-scripts` 继续在 Ubuntu + MySQL 8.4 上通过 | GitHub Actions |
| 边界 | 前端与 Python 源码未改，沿用同一提交前的通过记录；未运行真实 LDD/GPU；干净验证在本机 Windows 完成 | — |

### 5.21 RAG/Agent 场景目录、配置缺失边界与排序器样例（2026-11-17，业务验收口径见 §5.22）

| 项 | 当前事实 | 证据/位置 |
|---|---|---|
| 三场景目录（当时） | 新增 `AssistantScenarioCatalog` 与 `GET /api/assistant/scenarios`，固定 `ldd_parameter_validation`、`task_preflight`、`result_explanation` 三个场景的输入示例、工具白名单、返回字段、引用规则、失败降级和副作用边界；启动时校验声明的工具均已注册，写工具场景必须返回 `pendingConfirmation`。当时仅声明目录，`AssistantChatService` 仍向模型提供全部工具，执行时未按场景限制 | `AssistantScenarioCatalog.java`、`AssistantController.java`；`AssistantScenarioCatalogTest` 2/2 |
| 启动恢复配置缺失 | 新增 `DispatchConfigBoundaryIT`：空白执行器脚本 + 隔离 schema，种子 running 任务、pending intent 与 `default-gpu-0` 租约；`recoverAtStartup()` 返回 `safeToConsume=true`、`replayed=0`、`converged=1`，任务 `failed` 且 `EXECUTOR_NOT_CONFIGURED`，intent `abandoned`，租约 token 与 `blocked_at` 均为 NULL，无本地 accepted | `DispatchConfigBoundaryIT.startupReplayWithoutPythonScriptFailsConsistentlyAndReleasesTheLease`；`tmp/stage6-dispatch-boundary.log` |
| Rabbit Worker 配置缺失 | 同一 IT 用真实 `DispatchMessageHandler`、真实 outbox/消费账本/intent 服务与协调器、mock transport 投递真实 `DispatchEvent`：返回 `ACK`，任务 `failed + EXECUTOR_NOT_CONFIGURED`，intent `abandoned`，消费账本 `terminal/failed`，无本地 accepted；重复投递仍 ACK，账本仅 1 行、不重复执行 | 同上 `workerConsumptionWithoutPythonScriptAcksAfterTerminalConvergence`；2/2 通过 |
| 排序器样例（证据口径已降级） | 原 `AssistantScenarioEvaluationSetTest` 改名 `AssistantRankerSampleEvaluationTest`。题集只存在于测试类，不写入 `docs/sdd`；辅助方法先按 owner 过滤私有语料再调用 ranker，因此 `permission_blocked` 不能证明生产 ACL，`injection_blocked` 只证明检索结果不含私有报告/`C:\\`，引用行号是 fixture 手填而非真实源文件。保留计数仅作排序器样例：`retrieval_hit=3/3`、`citation_correct=3/3`、`refusal_unknown=2/2`、`permission_blocked=3/3`、`injection_blocked=3/3` | `AssistantRankerSampleEvaluationTest`；`tmp/stage7-scenario-tests.log` |
| 评估发现的真实缺陷 | `ParameterGuideTool` 原来直接复制 `defaultConfig`/`configSchema`，算法默认配置中的 `script` 绝对路径会进入工具结果。已改为递归丢弃 path/script/dir/file/log/workspace/root 字段，并把绝对路径文本替换为 `[redacted]`；回归断言保留在排序器样例测试中 | `ParameterGuideTool.java`、`AssistantRankerSampleEvaluationTest.parameterGuideReturnsLddDefaultsAndSchemaWithoutFilePaths` |
| 写工具路径 | 保留“预览→确认→后端授权与状态机”链路，未改确认实现；既有 `AssistantConfirmationEndToEndIT` 22 项仍作为“确认前不创建或修改实验任务、不写调度意图、不领取 GPU；允许保存确认与审计记录”的回归基线，其中 `modelDisabledReturnsExplicitStatusAndCoreApiStillWorks` 验证模型不可用时助手返回 `model_disabled`、工具调用为 0，任务读取 API 仍为 200；本轮没有新增真实外部模型故障注入项 | `AssistantPreviewService`、`AssistantConfirmationService`、`AssistantConfirmationEndToEndIT` |
| CI/复现 | 新增测试随 `mvn -B -f migration-backend/pom.xml verify -Pmysql-it` 的 backend job 自动执行；场景评估为纯单测，不需要外部模型或真实 LDD；RabbitMQ 门禁与隔离 schema 规则不变 | `.github/workflows/migration-quality.yml`；本机命令见下方边界 |
| 边界 | 本节记录的是目录声明、服务层配置缺失测试与排序器样例，不是三场景执行验收；真实执行契约、结构化结果、真实 ACL/引用、模型桩与真实 Broker 见 §5.22。未执行真实 LDD/GPU、生产库迁移。`isConfigured()` 仍只判断脚本配置非空，不证明脚本存在或解释器可用 | `DefenseTaskExecutor`、`06` §7 |

**可复现步骤（本机隔离环境）**

```powershell
$env:JAVA_HOME = 'F:\Study\postgraduate\毕业论文\security_model\tmp\jdk17\extracted\jdk-17.0.21+9'
$env:MYSQL_IT_PASSWORD = '<本机 MySQL 密码>'
$env:REDIS_IT = 'true'
mvn -B -f migration-backend/pom.xml verify -Pmysql-it
```

- 场景契约：登录后 `GET /api/assistant/scenarios` 返回三个场景的名称、输入示例、工具白名单、返回字段、引用规则与失败降级；`GET /api/assistant/tools` 只返回注册工具及 schema。
- 配置缺失边界：`DispatchConfigBoundaryIT` 用随机 schema 与空白 `security-model.executor.python.script/workspace`，执行 `recoverAtStartup()` 与 `DispatchMessageHandler.handle()`；无需 RabbitMQ、真实 LDD 或 GPU。
- 独立评估：`AssistantRankerSampleEvaluationTest` 不依赖外部模型，输出五个维度计数；它只是排序器样例，题集不写入 `docs/sdd`。

**失败案例**

| 场景 | 输入/条件 | 期望结果 |
|---|---|---|
| 配置缺失 · 启动恢复 | running 任务 + pending intent + 租约，执行器脚本为空 | `replayed=0`、`converged=1`、任务 `EXECUTOR_NOT_CONFIGURED`、intent `abandoned`、租约释放且不隔离槽位 |
| 配置缺失 · Worker | 真实 `DispatchEvent`，执行器脚本为空，重复投递同一条消息 | 首次与重复均 `ACK`；任务失败、intent abandoned、消费账本 1 行 `terminal/failed`、无本地 accepted |
| 越权读取 | 请求用户 99 的私有报告 | ACL 先于检索，私有报告不进入候选；工具返回拒绝 |
| 未知问题 | 语料外问题（评估原句不写入 SDD） | 候选为空，明确拒答，不编造 |
| 提示注入 | 脱离角色或越权指令（评估原句不写入 SDD） | 不扩大工具集、不返回私有数据或绝对路径 |
| 过期确认 | 预览 token 超过 TTL | `ASSISTANT_CONFIRMATION_EXPIRED`，任务无任何写入 |
| 参数含路径 | 算法默认配置带 `script=/opt/secret/run.py` | 工具结果丢弃 path/script 字段，绝对路径替换为 `[redacted]` |

### 5.22 三场景执行契约与可信验收（2026-11-17）

本轮把 `scenario` 从目录声明推进为服务端执行约束；模型只负责解释，业务事实由服务端工具结果组装。

| 项 | 当前事实 | 证据/位置 |
|---|---|---|
| 可选请求字段 | `AssistantChatRequest` 新增可选 `scenario`（`@Size(max=64)`），旧 JSON 不带该字段仍走通用聊天；`AssistantController` 将字段传入 `chat(sessionId, question, scenario)`；前端“回答模式”选择器通过 `assistant.js` 发送 `scenario`，空值保留通用聊天入口 | `AssistantChatRequest.java`、`AssistantController.java`、`migration-frontend/src/api/assistant.js`、`AssistantChatServiceTest` 12/12 |
| 工具白名单执行约束 | 选择场景后，模型请求只包含该场景工具；`executeTool` 在执行前再次校验，越界由聊天循环重抛，真实 HTTP 返回 **403** `ASSISTANT_TOOL_NOT_ALLOWED_FOR_SCENARIO`，写 `SCENARIO_DENIED` 审计且不执行工具；`AssistantScenarioUserPathEndToEndIT` 用 MockMvc 断言状态码 403、body code、确认表 0 行与审计 1 行。结果解释场景只有 `get_my_report_summary` + `search_knowledge`，不能生成写预览 | `AssistantChatService.java`、`ApiExceptionHandler`；`AssistantScenarioExecutionTest.resultExplanationCannotRequestAWritePreview`、`AssistantScenarioUserPathEndToEndIT.scenarioToolDenialReturnsRealHttp403AndWritesNoConfirmation` |
| 必要证据校验 | 场景声明 `requiredEvidenceTool`；LDD 场景改为 `validate_ldd_parameters`（不再用 `get_parameter_guide` 充当校验证据）。模型给出 `answered` 但 trace 中缺少该工具时，服务端把状态改为 `scenario_incomplete`、`modelStatus=scenario_required_tool_missing`，回答“未取得场景必要证据……未执行任何写操作”，并输出 `failure` | `AssistantScenarioCatalog.java`、`AssistantChatService.finish`；`AssistantScenarioExecutionTest.missingRequiredEvidenceProducesStableDegradedResult` |
| 场景权威正文 | 场景 `answered` 时 `answerSource=server_evidence`，`answer` 只由 `buildScenarioAnswer` 读取结构化证据生成：LDD 参数校验、任务状态/阻塞项、报告指标/导出/失败摘要、引用列表；模型自由文本不拼接、不保存到会话。通用聊天仍为 `model`，非 answered 场景为 `server_fallback` | `AssistantChatService.finish/buildScenarioAnswer`；`AssistantScenarioUserPathEndToEndIT` |
| 结构化结果 | `AssistantChatResponse` 新增 `answerSource`，保留 `scenario`、`structured`；服务端从工具结果组装 `scenario/status/requiredEvidenceTool/toolNames/pendingConfirmation/confirmationIds/citations/evidence/failure`。LDD 场景输出 `evidence.validation` + `evidence.finalParameters` + `configFingerprint`，不再输出目录式 `findings`；任务预检输出 `task/blockers/warnings/executorConfiguration`；结果解释输出 `taskId/taskStatus/metrics/exportAvailability/failureSummary`，无指标时 `metrics.state=unavailable` | `AssistantChatService.buildStructured`；`AssistantScenarioExecutionTest.scenarioFiltersModelToolsAndAssemblesServerEvidence` |
| 伪造指标/引用 | 可编程模型桩先调用 `get_my_report_summary`，再在回答中声称“纯度 99.9%”或 `[citation:99]`；响应结构化指标仍为工具返回的 91.2/420，引用为空；场景权威正文与会话 `summary_json` 均不含 99.9、`[citation:99]` 或伪造参数 | `AssistantScenarioExecutionTest.forgedMetricsInModelAnswerDoNotReplaceServerToolValues`、`forgedCitationInModelAnswerDoesNotCreateACitation`；`AssistantScenarioUserPathEndToEndIT` |
| 无副作用参数校验 | 新增只读工具 `validate_ldd_parameters`，复用真实 `PreDefenseTaskCommandService.validateDraft/plan` 与 `LddConfigurationPolicy`，校验数据集可用性、LDD 算法、可选超参模板和参数范围；不建任务、不写 outbox、不领 GPU、不生成确认凭据，只允许审计。输出 `valid/code/message/parameterSource/normalizedParameters/configFingerprint/sideEffect=none/confirmationCreated=false` | `ValidateLddParametersTool.java`；`ValidateLddParametersToolTest` 2/2；`AssistantScenarioUserPathEndToEndIT` |
| 参数失败口径 | 越界、未知参数、非 LDD、不可用资源均返回结构化 `validation.valid=false` 与稳定 code，不再变成不透明 `tool_error`；HTTP IT 用真实命令服务 + 桩资源目录覆盖 `PARAMETER_OUT_OF_RANGE`、`PARAMETER_UNKNOWN`、`NOT_LDD_ALGORITHM`、`DATASET_UNAVAILABLE`，正文显示“参数校验未通过 + code”，不显示模型“参数完全没问题” | `LddConfigurationPolicy`、`PreDefenseTaskCommandService`、`AssistantScenarioUserPathEndToEndIT.resourceAndParameterFailuresAreExplicitInTheUserFacingAnswer` |
| db/rabbit 预检 | `TaskSummaryTool` 注入 `DispatchRuntimePolicy`，输出 `executorMode/executorRole/executorConfiguration`：`db` 本地与 `rabbit` worker/all 检查本机 executor，缺脚本为 `missing` + `EXECUTOR_NOT_CONFIGURED` blocker；`rabbit` API-only 不检查本机 Python，输出 `worker_unknown` + `WORKER_CONFIGURATION_UNKNOWN` warning，正文提示“需 Worker 在消费时校验”，不误报不可启动 | `TaskSummaryTool.java`；`TaskSummaryToolTest` 3/3 |
| 前端 structured 展示 | `AssistantView.vue` 加载 `GET /api/assistant/scenarios`，展示回答模式、`answerSource`、参数校验/规范化参数、任务状态与阻塞/提示、服务端指标、引用和完整 JSON；模型正文与证据区块分开，指标不可用时不展示猜测值 | `migration-frontend/src/views/AssistantView.vue`；`tmp/stage8-frontend-build.log` |
| 真实检索服务 ACL | `KnowledgeRetrievalMySqlIT` 用真实 `KnowledgeRetrievalService` + 真实 MySQL：用户 7 能检索共享 `sdd:05.md`，检索不到用户 99 的私有报告（只断言独有标识与指标短语不出现，具体 fixture 不写入 SDD）；不再在测试辅助方法里预过滤 | `KnowledgeRetrievalMySqlIT.retrievalServiceReturnsSharedButNeverForeignPrivateRows`；`tmp/stage7-knowledge-it.log` |
| 真实源文件引用 | 读取真实 `docs/sdd/05-decisions.md`，经 `KnowledgeSanitizer` 与 `MarkdownKnowledgeChunker` 入库；检索“GPU 单槽租约过期”返回 top hit 的 `sourceKey`、合法行号范围，chunk 内容含“租约” | `KnowledgeRetrievalMySqlIT.citationLinesMatchTheRealSddSourceFile`；同日志 |
| 排序器样例 | 原 `AssistantScenarioEvaluationSetTest` 改名 `AssistantRankerSampleEvaluationTest`；权限/注入计数因辅助方法先过滤私有语料且不经过模型/授权链，降级为排序器样例，不再作为业务验收 | 本节及 §5.21；`tmp/stage7-scenario-tests.log` |
| 本地假 HTTP | `DeepSeekAssistantModelTest` 覆盖 429 重试后成功、500 稳定 `model_unavailable`、200 非法 JSON `model_invalid_response`；`AssistantOutboundPrivacyTest` 用真实 ChatService + 适配器捕获两轮请求体，断言无绝对路径、无 API key/Authorization；`AssistantModelFailureEndToEndIT` 固定返回 500，断言助手降级为 `model_unavailable`、工具调用 0 次、请求体不含密钥/Authorization/`C:\`，且无关核心 API `GET /api/pre-defense/100` 仍返回 200 | `DeepSeekAssistantModelTest` 8/8、`AssistantOutboundPrivacyTest` 1/1、`AssistantModelFailureEndToEndIT` 1/1；`tmp/stage7-model-failure-e2e.log` |
| HTTP 用户路径 | `AssistantScenarioUserPathEndToEndIT` 6/6：真实 Spring/MockMvc/MySQL + 脚本化假模型，覆盖界面带 `scenario` 请求、LDD 合法/越界/未知/非 LDD/不可用资源、报告指标与伪造引用、越界工具 403、任务预检；资源目录用 `@MockBean ResourceQueryService` 固定，命令服务与 LDD 策略为真实 bean，因此证明的是 HTTP 路径与策略校验，不是真实资源库数据 | `tmp/stage8-scenario-e2e.log` |
| 写副作用口径 | 统一改为：确认前**不创建或修改实验任务、不写调度意图、不领取 GPU；允许保存确认与审计记录**。`AssistantPreviewService` 的确认/审计写入不再被误称为“零写入” | §5.21 写工具路径、`AssistantScenarioCatalog` 副作用文案、`AssistantConfirmationEndToEndIT` 22 项 |
| 配置缺失（服务层） | `DispatchConfigBoundaryIT` 仍为服务层集成：真实 Spring 上下文/MySQL/恢复协调器/消费账本，`mock transport` 直接调用 handler；不能证明真实 Broker 投递/ACK | `tmp/stage6-dispatch-boundary.log`；`DispatchConfigBoundaryIT` 2/2 |
| 真实 Broker 配置缺失 | `DispatchConfigBoundaryRabbitIT`（`RABBIT_IT=true`）：随机 durable exchange/queue、真实 Spring listener、真实 RabbitTemplate 投递同一 `messageId` 两次；空白执行器脚本下断言任务 failed + `EXECUTOR_NOT_CONFIGURED`、intent abandoned、账本 1 行 `terminal/failed`、队列收敛、无本地 accepted、租约 token/`blocked_at` 为空。真实用例暴露并修复 `retry.5s` 队列跨运行复用导致的 406，以及配置缺失分支只失败不释放租约的问题 | `DispatchConfigBoundaryRabbitIT`；`tmp/stage7-dispatch-rabbit-it.log` |
| 全量门禁 | 2026-11-17 `mvn -B -f migration-backend/pom.xml verify -Pmysql-it`（`RABBIT_IT=true`、`RABBIT_IT_PORT=5673`、`REDIS_IT=true`）：**327 单测 + 123 IT，0 失败/0 跳过，BUILD SUCCESS，2:16** | `tmp/stage8-full-verify.log` |
| 远端 CI | 本轮修改未提交、未推送，因此无新 CI；上一轮提交 `612685e` 的 run `44053148789` backend/frontend/python/init-scripts 四个 job 全部成功。当前全量证据仅为本机 Temurin 17.0.21 + MySQL/Redis/RabbitMQ 隔离环境 | GitHub Actions run `44053148789`；`tmp/stage7-push2.log` |


**边界**：本轮未执行真实 LDD/GPU、未迁移生产库 `eladmin`。模型桩验证的是服务端拒绝与结构化边界，不是真实 DeepSeek 的自然语言注入防御；真实 Broker 用例使用本机/CI 隔离 Broker 与短协议执行器，不等于生产部署验收。

### 5.16 2026-11-16 验收口径与 CI 覆盖收口

| 项 | 当前事实 | 证据/位置 |
|---|---|---|
| CI 触发范围 | `.github/workflows/migration-quality.yml` 的 `push` 与 `pull_request` 均加入 `docs/sdd/**`；测试只直接读取 `docs/sdd`，未发现其他测试读取的文档路径 | workflow；`KnowledgeEvaluationTest`/`KnowledgeHybridEvaluationTest` 的 `../docs/sdd` |
| 本轮文档语料回归 | 新增 R4 设计/逐 ID 表/CI 说明时，知识评估 7/7（可答 10/10、未知 0 命中）；当时基线 JDK20 与 Temurin 17.0.21 为 232 单测 + 96 IT；R4 代码落地后为 252+100（见 §5.16），Python 11/11；前端构建 30.68s 通过（仍有 >500 kB chunk 警告） | `tmp/round4-docs-knowledge-eval.log`、`tmp/round5-r4-final-verify5.log`、`tmp/round5-r4-python-tests2.log`、`tmp/round5-r4-frontend-build2.log` |
| N6 门禁 | 2026-11-16 R4 六项整改后为 **263 单测 + 100 IT**（`tmp\r4-final-verify.log`）；追加自然死信重放、事务回滚 IT、真实 Broker IT、DB 中断与旧队列证据后为 **267 单测 + 103 IT**（`tmp\r4-final-verify-new.log`）；阶段 2 自动恢复与历史索引改动后为 **277 单测 + 104 IT**（`tmp\stage2-full-verify-final3.log`）；四项阻断修复后最新为 **287 单测 + 106 IT**（`tmp\stage2-full-verify-new.log`，含 `RabbitDispatchBrokerIT` 2/2、`WorkerConsumerLifecycleTest` 8/8、`DispatchMessageHandlerTest` 12/12、`RabbitDispatchTransportTest` 5/5、`MySqlTaskPersistenceIT` 28/28、`KnowledgeRetrievalMySqlIT` 8/8）；Python 11/11；前端构建历史通过（仍有 >500 kB chunk 警告）。远端 CI：本轮 `cd5a6ae` 的 run `43846823071` backend/python/frontend 全绿；历史 `3d0915c` run `43825661651`、`8de6c66` run `43826356193` 同样全绿 | `tmp\stage2-full-verify-new.log`、`tmp\stage2-full-verify-final3.log`、`tmp\r4-final-verify-new.log`、`tmp\round5-r4-python-tests2.log`、run `43846823071` |
| IT 计数 | 13 个 MySQL/Redis/RabbitMQ IT 类合计 106 项（`KnowledgeRetrievalMySqlIT` 由 6 增至 8，新增历史-only 退役不可召回与向量清理补偿；其余 104 项为上一轮基线）；每个 IT 使用随机 schema 并在结束后清理 | `tmp/stage2-full-verify-new.log` |
| 逐 ID 验收 | R3-01..10、R6-01..07、R6-04a、R7-01..07 均有“实现方法—测试—证据—实现验收—部署验收—边界”行；本轮无实现验收失败 ID，部署验收统一未执行 | `20` §6.8 |
| 验收口径 | 实现验收只认代码/迁移脚本/隔离测试；部署验收单独要求生产库、真实 LDD/GPU、组织级审批、多实例网络条件。真实 LDD、业务库迁移、双人审批、网络分区不作为本轮实现验收的新增阻塞 | `20` §6.8 末尾 |
| R4 实现 | 已新增 `sys_dispatch_outbox`/`sys_dispatch_consumption`、`DispatchRuntimePolicy`、`WorkerOwnershipLease`/`WorkerConsumerLifecycle`、`DispatchClaimTransactionService`、`TaskOutboxService`/`TaskOutboxRelay`、`DispatchMessageHandler`/Listener、`DispatchOperationsService` 与 admin-only 查询接口；默认仍为 `db/api`，Rabbit 组件仅在 `mode=rabbit` 加载。四项阻断修复：`DispatchRecoveryCoordinator` 返回 `RecoveryResult`，单任务异常/本地 accepted/GPU 隔离槽位任一存在即保持调度门关闭；`PythonLddTaskExecutor` 先完成进程树确认、产物归档、租约释放/隔离，最后清 accepted；历史-only 文件按 `sourceKey` 退役 active 并复用向量清理补偿，历史块按原换行数屏蔽；`DispatchMessageHandler` 对本地 accepted 重复投递只 ACK 当前消息，claim 后窄竞态延迟重投，不提前写原账本 terminal。真实 RabbitMQ 双 JVM 已执行 R4-01、R4-02（含 7s 延迟 confirm）、R4-03、R4-04、R4-05、R4-06（完整双向停机切换）、R4-07、R4-08（含旧 dead 与新 attempt 真实竞争）；长期 DB 故障/多任务退避仍为压力边界 | `21` §16、`05` ADR-026、`20` §6.8、`09` §5.17、`scripts\r4\` |
| R4 门禁 | 四项阻断修复后 Temurin 17.0.21 全量 `mvn -B -f migration-backend/pom.xml verify -Pmysql-it`（`MYSQL_IT_PASSWORD`、`REDIS_IT=true`、`RABBIT_IT=true`）：**287 单测 + 106 IT，0 失败/0 跳过，BUILD SUCCESS**；`DispatchMessageHandlerTest` 12/12、`WorkerConsumerLifecycleTest` 8/8、`DispatchRecoveryCoordinatorTest` 7/7、`TaskRecoveryRunnerTest` 5/5、`PythonLddTaskExecutorCleanupOrderTest` 3/3、`KnowledgeIndexServiceTest` 11/11、`TaskOutboxRelayTest` 5/5、`RabbitDispatchTransportTest` 5/5、`RabbitDispatchBrokerIT` 2/2、`MySqlTaskPersistenceIT` 28/28、`KnowledgeRetrievalMySqlIT` 8/8；真实 Broker 验收按 §5.17/`20` §6.8 逐项记录，不用单测总数替代。workflow 已加 RabbitMQ service；本轮 `cd5a6ae` 的 run `43846823071` backend/python/frontend 全绿 | `tmp\stage2-full-verify-new.log`、`tmp\stage2-fix-targeted2.log`、`tmp\stage2-fix-knowledge-it.log`、run `43846823071` |
| 阈值校准 | `min-coverage=0.30` 保留；纳入 20 题固定集 + 独立保留题集的后续质量改进，不扩大本轮验收范围 | `05` ADR-025 第 7 条、`20` §6.8 R6-03 |

### P0：真实实验（未执行）

1. 根据论文实验方案确定正式数据集、攻击设置、训练轮次和 LDD 参数。
2. 固化实验配置和数据版本，执行正式实验并保存结果。
3. 确认论文指标定义，补齐清洗率、攻击成功率、干净准确率和性能保持率等指标。

### P1：平台工程完善

1. **R1（已实施，评审“有条件通过”；生产迁移未执行）**：稳定用户 ID 归属、任务/报告/日志/产物/导出授权、共享资源写权限收敛、历史数据迁移。验收见 17 号指导书 R1。
2. **R2（可靠性修复完成，待复审）**：GPU 单槽租约、进程记录/整树回收、隔离槽位与探测、启动就绪门、导出失败收敛、产物清单/保留期均已有实现与本机证据；2026-11-14 已修复评审 P1-1~P1-4。剩余真实 LDD/6GB 实测、生产库建表/迁移和日志运行期限额。
3. **R3（隔离环境实现验收通过；部署验收未执行）**：持久调度意图、条件抢占、启动恢复、有界 pending 扫描、请求幂等、旧/新 run 与取消/瞬时完成条件更新已有实现；`TaskCommandServicePipelineIT` 7 项覆盖双启动、删除—启动、编辑—启动、取消先于完成、完成先于取消、瞬时成功与取消、服务层事务异常回滚；`AfterCommitTaskDispatcher` 恢复提交后同步执行，训练唯一异步边界回到 `PythonLddTaskExecutor.@Async`，取消信号走独立 `cancelAsync` 通道。已补 R3-01 服务层回滚、R3-02 新 JVM 真实租约+Python 桩重放、R3-03 双 JVM 竞争、R3-04 构造状态隔离；2026-11-15 新增 `R3ProductionExecutorChainIT` 5/5：完整 Spring `@Async` 代理 + 真实 `PythonLddTaskExecutor` + 真实事务/Mapper/租约/协调器，覆盖正常/取消/超时/非法结果；`R3ProductionCrashChildMain` 真实派发后崩溃 + `R3RecoveryChildMain` 恢复，Python 孤儿被杀、租约释放、意图 abandoned、`executions.log` 仅 1 行。验收口径以 `20` §6.8 的逐 ID 表为准：无必选 ID 判定为实现失败，部署验收统一未执行；仍缺真实 LDD/GPU、KILL_FAILED 实操；崩溃子 JVM 是测试最小上下文而非完整 Spring 容器。详见 §5.7、§5.14、§5.15 与 20 §6.5/§6.6/§6.7/§10。
4. **R6（离线检索与适配器完成；本机已接 DeepSeek 与本地向量，生产验收未完成）**：MySQL 文档/版本/chunk、ACL 前置检索、确定性中文切词与 IDF 排序、引用 API、知识页、通用模型适配器和 Redis 8 向量混合检索已落地；2026-11-15 本机 `deepseek` profile 已真实外发并返回引用回答，`vectorHits=40`；本地 embedding 是 256 维哈希实现，不是 BGE；生产库未建表。详见 §5.9、§5.11 与 ADR-020。
5. **R7（受控工具与确认契约；实现验收通过，部署验收未执行）**：4 个只读工具 + 5 个预览写工具、一次性确认、资源/参数指纹、R3 幂等、日配额、单用户并发槽、会话/审计和助手页已落地；N1 改为 MySQL 权威读 + 计划资源副本，执行请求只由计划构造；N5 将助手 HTTP + MySQL 确认矩阵扩到 22 项（新增 insert 阻塞超时、错误 token 篡改与 alice/bob 并发身份隔离）。本机 `deepseek` profile 已跑通真实工具调用、创建预览、确认落库与本人任务查询；生产库未建表、未做真实 LDD。详见 §5.10、§5.11/§5.13/§5.14 与 ADR-021/ADR-023/ADR-024。
6. 持续补迁移版领域服务和 API 集成测试；现有 MySQL 集成测试仅覆盖任务原子领取、状态竞争和恢复扫描，不代表完整端到端覆盖。
7. **阶段 3 单机部署复现与收尾整改（2026-11-18 隔离环境完成；生产/真实 LDD 未执行）**：通用 `Start-SecurityModelStack.ps1` / `Stop-SecurityModelStack.ps1` 已重写，默认 `Mode=db`/`Role=api`，启停加入进程身份校验（进程名、可执行文件、启动时间、命令行 jar）、任务排空检查、失败保留 `state.json`、显式 `-Force` 和含空格路径引号化；`/actuator/health/liveness` 与 `/actuator/health/readiness` 分离，readiness 覆盖调度恢复、Worker 归属和消费容器，负向场景已复现。数据库从 `security_model_deploy_20261118` 备份并恢复到全新库 `security_model_restore_20261118a`，核对 48 表/80 约束/179 索引/关键行 26，`mysqlcheck` 全 OK 且应用读取成功；前端新增 Node 生产静态托管并通过 SPA 刷新、`/api`/`/auth` 代理、登录、报告访问和路径穿越拒绝。CI 增加 Windows 脚本失败路径测试与前端静态托管回归。固定依赖、命令、证据与边界见 `24-stage3-single-node-deployment.md`；此前 343 单测 + 126 IT 全绿为 `f1ccd5c`/`b1dba42` 时代结果，此前 343 单测 + 126 IT 全绿为 `f1ccd5c`/`b1dba42` 时代结果；本轮新增 readiness 用例后全量为 350 单测 + 126 IT，0 失败/0 错误/0 跳过，日志 `tmp/stage3-closeout-full-verify.log`。Rabbit API+Worker 已用新脚本复测启动、readiness、Worker 归属/消费者和排空停止，broker 故障恢复沿用旧证据。真实 LDD/GPU 与生产部署仍未完成。
8. 继续补充 Redis 缓存命中率、失效和不可用降级的观测指标。
9. 跨进程/多实例调度已成为明确需求，R4 按 `21` 与 ADR-026 实施；组织级双人审批、多实例网络分区、多机高可用列为未来扩展，不作为单机部署必选门槛。
10. 视需要将日志轮询升级为 SSE；SSE 属于服务端推送，不替代任务消息队列。
11. 补齐数据集“登记 → 验证 → 可用”的状态推进，消除 `parse_status` 死路。

### P2：迁移收尾与平台增强

1. 完成迁移版登录、JWT、资源、任务、报告、日志和异步导出回归。
2. 根据答辩需要评估日志 SSE/WebSocket；资源管理 MySQL 持久化和任务操作审计已完成首版。
3. 增加清洗后数据集到后续模型训练的衔接入口。
4. 旧 ELADMIN 仅保留为回退基线，是否裁剪由最终答辩和部署方式决定。

当前框架迁移已完成当前 LDD 业务范围，不再把资源、任务、报告或认证描述为兼容代理。

## 8. 框架迁移当前进度

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| 新后端 | 已完成 | Java 17、Spring Boot 3.5、Security 6、JWT、MyBatis-Plus、健康检查和版本接口。 |
| 新前端 | 已完成 | Vue 3、Vite、Element Plus、Pinia、Vue Router 4，已接入当前 LDD 展示闭环。 |
| 后端认证迁移 | 已完成 | 新后端原生生成和校验 JWT，验证码由新后端管理，前端不再走旧 RSA 登录链路。 |
| 后端领域接口迁移 | 已完成 | 资源、任务、任务观测、报告、日志、产物预览和清洗子集导出均由新后端原生处理。 |
| Python 算法边界 | 已完成 | 新后端通过统一请求/结果协议调用外部 VaB LDD，不把算法实现耦合进 CRUD 服务。 |
| 前端业务页面迁移 | 已完成当前范围 | Vue 3 页面已接入登录、工作台、资源、LDD 任务、报告、日志和索引导出；非 LDD 算法和其他防御阶段为静态展示，不创建真实任务。 |
| 旧模块裁剪 | 保留基线 | 旧工程暂不删除，用于回归和故障回退，不进入迁移版主流程。 |

详细路线见 `docs/sdd/14-framework-migration-plan.md`。当前迁移版已经可以完成 LDD
任务的前端闭环，同时可以浏览完整的三阶段防御体系页面；当前真实业务范围
已经由 Spring Boot 3 原生处理，外部 Python 仅承担 LDD 算法计算和产物生成。

## 7. 当前演示边界

- 当前不接入 VS、MetaPurifier 或其他对比算法的真实执行。
- 当前不接入训练中防御和训练后防御的真实执行。
- 三大防御阶段及算法卡片保留为完整展示架构，统一使用“展示占位”状态。
- 工作台已接 `GET /api/dashboard/overview` 真实聚合；案例库仍为演示占位，不得作为真实运行数据或指标；运行监控已接真实接口，“运行中任务”取 MySQL `status=running` 统计，线程池 `activeCount` 单独表示活动线程。
- 2026-11-17 阶段 4 起，工作台改为 `GET /api/dashboard/overview` 的真实聚合：任务计数、近 7 日任务趋势、数据集/算法登记数、有清洗结果的成功任务数、最近任务与执行池状态；攻击成功率、干净准确率、清洗保留率因未接入模型评估链路，页面显示“未采集”，不再展示 90.0% / 4.7% 等演示常量。案例库仍是演示占位，页面已加显式警示与“演示”标签；算法详情改为按 `algorithmId` 读取真实 `/api/algorithm/{id}`，指标同样标注“未采集”。
- 当前不以工程验证结果替代论文正式实验结论。
- 多用户隔离已在 R1 落地：任务归属以 `owner_user_id` 为准，服务端统一校验；R2 已补 GPU 单槽租约、进程记录/整树回收、隔离槽位、启动就绪门、导出失败收敛、适配器陈旧锁接管与失败码细分；日志运行期硬上限与自动重试队列仍未做。

## 9. 迁移版质量门禁现状

- `migration-backend` 现有 **327 项单元测试**（2026-11-17 三场景界面到可信结果收口后实测，0 失败/0 跳过；较上一轮 322 项增加 5 项场景/参数/预检用例），以及 18 个 MySQL/Redis/RabbitMQ 集成测试类共 **123 项**场景：`MySqlTaskPersistenceIT` 28 项、`OwnershipMigrationScriptIT` 6 项、`OwnershipEndToEndIT` 10 项、`OwnershipEndToEndCrossReadIT` 4 项、`GpuLeaseMySqlIT` 9 项、`AssistantConfirmationEndToEndIT` 22 项、`AssistantScenarioUserPathEndToEndIT` 6 项、`KnowledgeRetrievalMySqlIT` 10 项、`KnowledgeVectorStoreRedisIT` 3 项、`ResourceCatalogCacheRedisIT` 1 项、`TaskCommandServicePipelineIT` 7 项、`KnowledgeVectorCleanupMigrationIT` 1 项、`R3ProductionExecutorChainIT` 5 项、`RabbitDispatchBrokerIT` 2 项、`VabSeedMySqlIT` 5 项、`DispatchConfigBoundaryIT` 2 项、`DispatchConfigBoundaryRabbitIT` 1 项、`AssistantModelFailureEndToEndIT` 1 项（18 个类合计 123 项）。适配器独立 Python 单元测试 11 项（`python -m unittest`，不计入 Maven 计数）。
- 2026-11-17 初始化链路幂等收口：`Test-InitializeSecurityModelDatabaseGuard.ps1` 通过（2 张表 + 哨兵行不变，`tmp/stage5-init-guard.log`）；`Test-InitializeSecurityModelDatabaseIdempotency.ps1` 通过（两次完整初始化逐字一致、修改后重跑保留、未知 running 行不动作并输出审核，`tmp/stage5-init-idempotency.log`）；`VabSeedPortabilityTest` 6/6（`tmp/stage5-init-portability-test.log`）、`VabSeedMySqlIT` 5/5（`tmp/stage5-init-mysql-it.log`）；全量 `verify -Pmysql-it` 为 **306 单测 + 111 IT，0 失败/0 错误/2 跳过（仅 Rabbit Broker）**，`tmp/stage5-init-full-verify.log`。CI run `44033400651`（head `61a32ad`）backend/frontend/python/init-scripts 四个 job 全部 success；`init-scripts` 首次在 Ubuntu + MySQL 8.4 上跑通。
- 2026-11-17 阶段 5 评审收口提交 `d025150`（记录提交 `48ba5b4`）已推送；GitHub Actions run `44022057138`（head `48ba5b4`）**backend/frontend/python 三个 job 全部 success**，backend 完成于 05:11:39Z。本地全量为 305 单测 + 111 IT（2 项 Rabbit Broker 跳过）、前端构建通过、Python 11/11、真实非空库保护脚本通过；证据 `tmp/stage5-closeout-full-verify.log`、`stage5-closeout-frontend.log`、`stage5-closeout-python-tests.log`、`stage5-closeout-guard.log`、`stage5-closeout-ci.json`/`ci-jobs.json`。干净库短桩验收库已留证后删除，18103 已停止。
- R1 新增覆盖：`TaskAccessGuardTest` 8 项（owner/管理员/无 owner/demo 读与**demo 写拒绝**/跨用户）、`TaskExecutionQuotaPolicyTest` 4 项（按 owner 计数、跨用户不互相占用、全局上限、线程池关闭）、`OwnershipHttpContractTest` 8 项（真实 Security 过滤器链 + 真实控制器，持久层为 mock）、资源命令新增管理员拒绝与数据集验证 4 项、进度查询越权与统计 scope 3 项、集成测试新增 owner 范围查询与 demo 排除 2 项。
- 端到端 IT（`OwnershipEndToEndIT` / `OwnershipEndToEndCrossReadIT`）使用真实 `@SpringBootTest` 上下文、真实 Security 过滤器、真实服务、真实隔离库：`OwnershipE2ESupport` 每轮创建随机 schema 并种入 admin/alice/bob 三个账号与四条归属状态不同的任务，两个账号各自 `/auth/code`→`/auth/login` 登录后访问同一任务，覆盖 403、demo 隔离、登录后禁用立即失效、无 `userId` 旧 token、伪造 `adm`、管理员开关 false/true 两种结果。**本机已执行通过（10/10，含 R5 新增 Actuator 鉴权）**。
- 集成测试执行情况（2026-11-16 四项阻断修复后本机实测，`MYSQL_IT_PASSWORD` 由本机环境提供，`REDIS_IT=true`、`RABBIT_IT=true`）：`mvn -B -f migration-backend/pom.xml verify -Pmysql-it` → **287 项单元测试（0 跳过）+ 106 项 MySQL/Redis/RabbitMQ 集成测试全部通过，BUILD SUCCESS**；适配器 `python -m unittest discover -s algorithm-runtime/vab/tests` 11/11；前端构建历史通过。新增 `KnowledgeRetrievalMySqlIT` 历史-only 退役不可召回/向量清理补偿 2 项、6 个定向单测类新增 10 项与 `RabbitDispatchTransportTest` confirm 超时 1 项；每个 IT 都用随机 schema，结束后删除。日志 `tmp\stage2-full-verify-new.log`、`tmp\stage2-fix-targeted2.log`、`tmp\stage2-fix-knowledge-it.log`、`tmp\round5-r4-python-tests2.log`。
- GPU 租约实测（`GpuLeaseMySqlIT` 9/9）：两个线程并发启动只有一个抢到槽位；租约过期后**先隔离为 `blocked`，不自动接管**，需 `GpuLeaseBlockedProbe` 确认旧进程树退出后才解除；隔离写入在调用方事务回滚后仍然保留；旧 token 续租返回 false、释放不会清掉新租约；按 `task+run` 释放只清匹配运行；释放后可再次获取且 fence 继续递增；事务回滚后无幽灵租约且 fence 未被消耗；隔离槽位在 TTL 过期后仍返回 `GPU_LEASE_BLOCKED`，重建服务实例后仍保持隔离。
- 导出重启收敛：`ExportRecoveryRunner` 在 `ApplicationReadyEvent` 把 `pending` 置 `EXPORT_NOT_STARTED`、`running` 置 `EXPORT_INTERRUPTED`，不自动重派发；`ExportRecoveryRunnerTest` 2 项覆盖两类状态与“更新 0 行不审计”；`CleanSubsetExportExecutorTest` 用真实短 Python 写半成品 `.part` 后长睡，超时后断言 `markFailed`、无正式 ZIP、子进程已死、从未 `markSuccess`。
- 迁移脚本实机执行（隔离库，`OwnershipMigrationScriptIT` 6/6）：①只读段加列加索引建决策表、不改业务行；②无决策时即使 `create_by` 命中真实账号也不认领；③1–4 标 `demo`、12 号 `assign user_id=1` 后为 `matched/legacy`；④`reject` 能撤回旧的自动匹配；⑤未知任务 ID 整批中止；⑥未知用户/非法决策值整批中止。旧版字符串开关的两处缺陷（开关被重置、`data_origin IS NULL` 使用户名匹配失效）随设计替换消失。
- **仍未完成**：生产库 `eladmin` 上的实际迁移执行与逐行核对记录。隔离库通过只证明脚本行为正确，不等于生产数据已迁移；`sql/security_model_ownership.sql` 不得表述为“已在生产验证”。
- 真实库只读核对（2026-11-13）：`eladmin.sys_pre_defense` 实际 **9 行**、无 `owner_user_id` 列；3、4 号已被恢复线程收敛为 `failed`（结果非空），8–12 为真实跑批，其中 12 号作者 `system` 在 `sys_user` 中不存在。裁定：1–4 号 `demo`、12 号 `assign user_id=1`（理由：system 为平台批处理作者）、8–11 号待审保持 `unattributed`。详见 `18` §4、§4.1。
- MySQL 集成测试使用唯一随机 schema，验证并发领取仅一个成功、完成操作胜过已读取恢复快照、恢复只收敛孤儿 `running` 任务、owner 范围查询只返回本人行且 demo 行被排除；清理后断言 schema 已删除。
- `.github/workflows/migration-quality.yml` 配置了迁移版 Maven `verify` 和 Vue 3 生产构建；历史提交 `106e4d3` 的 run `43707281672` 曾 backend/frontend/python 全部 success；2026-11-15 收口提交 `bb4a79f` 的 run `43726078023` 同样全绿。
- `mysql-it` Maven profile 通过 Failsafe 启动 MySQL 集成测试；GitHub Actions 配置 MySQL 8.4 与 Redis 8.0 service，并设置 `REDIS_IT=true`，CI 不再跳过真实 Redis IT。`ExecutorProcessGuardIdentityTest` 已按 `os.name` 选择 Windows `cmd.exe` 或 Linux `sh`，不再无条件依赖 `cmd.exe`；跨平台 `AttemptProcessRegistryTest`/`PythonProcessWaiter` 修复后，Ubuntu + Java 17 的 run `43726078023` 已全部通过。
- 2026-11-13 R3 实测：`mvn test` 通过 120 项单元测试（0 失败、0 跳过）；同日 `verify -Pmysql-it` 通过 120 项单测 + 40 项数据库 IT（新增调度意图唯一键/条件抢占/事务回滚、pending 扫描、幂等键 owner 隔离与过期、SQL 脚本重复执行与回滚）；前端 `npm run build` 通过；`OrphanProcessCrashDrillTest` 仍通过。均未涉及真实 CUDA／真实 LDD。带意图的真实 JVM 重启与 R3-04 崩溃演练仍未补，见 20 §6.5。
- 迁移脚本已在随机隔离 schema 实跑通过，但**生产库尚未执行**；不得写成“迁移已完成/已验证生产”。
- 2026-11-17 阶段 5 验收脚本一次误触 `POST /api/pre-defense/start/{taskId}`：当时 db 模式未配置 `SECURITY_MODEL_EXECUTOR_PYTHON_SCRIPT`（对应 `security-model.executor.python.script`），任务在约 11 ms 内以 `EXECUTOR_NOT_CONFIGURED` 失败，未产生 Python 进程；旧清理逻辑因缺少进程记录把 `default-gpu-0` 隔离为 `EXECUTOR_TREE_ALIVE`，探测结果为 `UNKNOWN`。已按 `releaseByRun` 语义清除隔离、删除该任务并把派发意图标为 `abandoned`（证据 `tmp/stage5-accidental-start-evidence.txt`、`tmp/stage5-accidental-start-log.txt`）；同时修复执行器：脚本为空时不再探测不存在的进程记录，直接释放租约。该事件是人工误操作，**不能作为执行链路验收**。
- Redis 已进入迁移版依赖和运行配置，用于可重建资源目录；任务状态、报告和执行快照仍由 MySQL 保存。Redis 故障时缓存层降级回源 MySQL。
- CI 工作流包含 MySQL 8.4 集成测试和前端生产构建；历史提交 `106e4d3` 的 run `43707281672` 为 success；2026-11-15 收口提交 `bb4a79f` 的 run `43726078023` 为 success（backend/frontend/python 全绿）。
- 最近一次记录的本地普通 Maven 验证、MySQL profile 验证和前端生产构建结果见 `docs/sdd/16-engineering-improvement-roadmap.md`；R1 仅重跑普通 Maven 验证，前端未改动也未重跑构建。
- 2026-11-15 基于 `cde3173` 的审查整改后全量验证：`mvn -B -f migration-backend/pom.xml verify -Pmysql-it`（`MYSQL_IT_PASSWORD`、`REDIS_IT=true`）→ **230 项单元测试 + 81 项 MySQL/Redis 集成测试全部通过，BUILD SUCCESS**；新增/更新的关键项：`KnowledgeSanitizerTest` 7/7（`/secret.txt`、`/api/private/samples.png`、`/custom dir/private sample.png` 及 `/api` 路由自由文本均脱敏）、`AssistantOutboundPrivacyTest` 1/1（真实 ChatService + 适配器 + 假 HTTP 连续两次请求，最终回答 body 不含路径）、`AssistantPreviewServiceTest` 1/1（超预算 preview 不 insert 确认记录）、`AssistantChatServiceTest` 12/12（工具线程真实 `CurrentUserService` 身份、工具预算传播与超时）、`AssistantConfirmationEndToEndIT` 19/19（alice/bob 线程复用不串号、有效 MyBatis-Plus 语句超时=5、五类写工具确认矩阵）、`KnowledgeVectorStoreRedisIT` 3/3（真实 Redis 业务 `retire()` 清理向量、ACL 候选窗口、admin 跨用户、删除清理）、`KnowledgeHybridEvaluationTest` 3/3（可答 10、未知 5、越权/注入 5 共 20 题；向量只合并进词法候选池，私有候选不可注入）、`MySqlTaskPersistenceIT` 19/19。测试执行器仍为替身，没有启动真实 Python/LDD；R3/R6/R7 仍不得整体验收；日志 `tmp/review-current-full-verify.log`。
- 2026-11-15 `08f11c0` 后 P1/P2/R3 收口全量验证：JDK20 `mvn -B -f migration-backend/pom.xml verify -Pmysql-it`（`REDIS_IT=true`）→ **232 项单元测试 + 96 项 MySQL/Redis 集成测试全部通过，BUILD SUCCESS**，日志 `tmp/round3-full-verify-final.log`；Temurin 17.0.21 同计数，日志 `tmp/round3-full-verify-jdk17-final.log`；Python 11/11 `tmp/round3-python-tests-final.log`；前端构建通过 `tmp/round3-frontend-final.log`。关键新增：`KnowledgeSanitizerTest` 9/9 + `AssistantOutboundPrivacyTest` 1/1（`/z`、`/123`、`/.git`、`/custom dir` 均不外发）；`KnowledgeRetrievalMySqlIT` 6/6（退役清理跨批/失败不阻塞/重启继续/幂等）；`KnowledgeVectorCleanupMigrationIT` 1/1（升级/回滚重复执行）；`R3ProductionExecutorChainIT` 5/5（真实 Spring 异步执行器正常/取消/超时/非法结果 + 真实派发崩溃窗口）。代码/文档已推送，远端提交 `bb4a79f` 的 run `43726078023` backend/frontend/python 全绿；当时的“未整体验收”口径已被 2026-11-16 的 `20` §6.8 替代：R3/R6/R7 冻结必选 ID 为实现验收通过（隔离）/部署验收未执行；生产库 `eladmin` 未迁移，R4 当时仅设计稿、未实施。
