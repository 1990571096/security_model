# 面向数据清洗的安全模型训练系统

本项目由早期网络安全竞赛项目重构而来，目标是形成一个面向数据清洗与安全模型训练的可视化平台。当前已完成 Spring Boot 3 + Vue 3 全量迁移后的 LDD 训练前数据清洗、结果报告和清洗子集导出闭环，并补齐训练前、训练中、训练后三大防御体系的可视化演示页面；旧 ELADMIN 工程仅作为业务回归和回滚基线保留。

## 当前定位

- 管理数据集、算法和 LDD 超参数模板。
- 创建并启动 LDD 训练前数据清洗任务，记录清洗结果。
- 查看失败任务并重试，保留每次启动的实验参数快照。
- 通过运行标识隔离重试过程，避免旧执行器回调覆盖新任务结果。
- 保存数据集、算法和运行环境指纹，支持核对历史实验使用的资源版本。
- 在算法结果中记录实际 Python、PyTorch、NumPy、CUDA 和 GPU 运行环境，并在实验报告中展示。
- 查看 LDD 实验报告，并按保留索引导出清洗后的 CIFAR-10 子集。
- 查看任务创建、编辑、启动、重试、取消和清洗子集导出的操作审计记录。
- 通过统一执行器接口为后续真实算法接入预留边界。
- 浏览训练前、训练中、训练后三大防御阶段及算法目录；当前只有 LDD 进入真实任务执行链路，其他算法卡片不创建任务。
- 观测有界任务执行池的线程、队列和饱和状态，并在队列满载时将任务收敛为可重试失败。
- 通过单机运行任务总数、单用户运行任务数和执行池接收状态进行任务准入，并记录每次尝试的配额快照。
- 按 SDD 文档驱动方式推进重构，降低返工风险。

## 技术栈

当前平台运行版本采用稳定、成熟的目标技术栈：

- 后端：Java 17、Spring Boot 3.5、Spring Security 6、JWT、MyBatis-Plus。
- 前端：Vue 3、Vite、Element Plus、Pinia、Vue Router 4。
- 数据与运行：MySQL 是事实数据源；Redis 用于资源目录缓存；Python、PyTorch 执行 LDD；RabbitMQ/Kafka 尚未接入。
- 构建：Maven、npm。

旧版 Spring Boot 2.7 + Vue 2 工程保留在 `eladmin/` 和 `eladmin-web/`，不再作为迁移版运行入口。

## 项目结构

```text
.
├── docs/sdd/                  # SDD 文档与运行手册
├── eladmin/                   # 后端工程
│   └── eladmin-system/        # 后端启动模块与安全训练业务模块
├── eladmin-web/               # 前端工程
├── migration-backend/         # Spring Boot 3 / Java 17 运行后端
├── migration-frontend/        # Vue 3 / Vite 运行前端
└── sql/                       # 数据库初始化与 MVP seed 脚本
```

平台前端通过 `8014` 访问，后端通过 `8100` 访问。认证、资源、LDD 任务、任务进度、
实验报告、日志和清洗子集导出均由新 Spring Boot 3 后端原生处理。新前端已经提供工作台、
数据集资源、算法资源、LDD 任务中心和三大防御阶段页面；
任务中心已接入 LDD 任务创建、启动、任务详情、实验报告摘要、执行日志和按保留索引
导出清洗子集。训练中和训练后防御页面仅用于展示后续扩展结构，未接入真实执行。报告中的
完整运行环境、指标图表和产物 PNG 预览已经接入。

## 算法执行范围

当前平台只允许启动 LDD（训练前防御）。历史 mock baseline 仅保留在旧代码和数据库结构中，用于兼容迁移，不属于当前可执行算法范围。

当前真实接入的算法为 LDD。平台通过
`PythonLddTaskExecutor` 和 `algorithm-runtime/vab/algorithm_entry.py` 调用外部
VaB 项目中的 `LDD.py`，并将配置、结构化结果和任务产物渲染到训练前任务详情页。

后续真实算法应继续实现后端统一的 `DefenseTaskExecutor` 边界，避免业务 CRUD
代码直接依赖具体算法实现。

## 本地环境

迁移版使用 MySQL 保存资源、任务、报告和执行快照。资源目录缓存使用 Redis，默认连接
`localhost:6379`；缓存异常时查询回源 MySQL，因此 Redis 不可用不会改变事实数据源。
RabbitMQ/Kafka 尚未接入，任务仍由事务提交后的有界 JVM 线程池调度。Redis 连接地址和超时
可通过 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_CONNECT_TIMEOUT` 和
`REDIS_COMMAND_TIMEOUT` 覆盖。

- MySQL：`localhost:3306`
- 数据库：`eladmin`
- 用户名：`root`
- 密码：通过 `DB_PWD` 环境变量配置，不在仓库中保存真实密码。
- JWT 密钥必须通过 `SECURITY_MODEL_JWT_SECRET` 提供，至少 32 字节；未设置或长度不足时
  后端会拒绝启动。生产环境应使用安全的外部密钥管理设施。

首次初始化数据库：

```sql
SOURCE sql/eladmin.sql;
SOURCE sql/security_model_mvp_seed.sql;
SOURCE sql/security_model_execution_snapshot.sql;
SOURCE sql/security_model_task_run_context.sql;
SOURCE sql/security_model_clean_subset_export.sql;
SOURCE sql/security_model_operation_audit.sql;
SOURCE sql/security_model_ownership.sql;
SOURCE sql/security_model_gpu_lease.sql;
```

已有开发库只需补充 MVP 数据：

```sql
SOURCE sql/security_model_mvp_seed.sql;
SOURCE sql/security_model_execution_snapshot.sql;
SOURCE sql/security_model_task_run_context.sql;
SOURCE sql/security_model_clean_subset_export.sql;
SOURCE sql/security_model_operation_audit.sql;
SOURCE sql/security_model_ownership.sql;
SOURCE sql/security_model_gpu_lease.sql;
```

`security_model_gpu_lease.sql` 创建单槽 GPU 租约表（`default-gpu-0`），任务启动前必须执行；未建表时启动任务会显式失败。它只保存租约，不改变 LDD 配置。

`security_model_ownership.sql` 分两段使用：

1. **第一段（默认，只读）**：直接执行。它只新增 `owner_user_id`、`data_origin`、
   `owner_backfill_status` 三列与 `idx_owner_status`，然后打印逐行清单、疑似演示行、
   `running` 但无 `run_id` 的行、按作者分组计数。**不修改任何业务行**（`@apply_backfill = 0`）。
2. **审核后第二段**：把审核结论写进决策表 `sys_pre_defense_ownership_decision`（一行一个任务，
   `decision` 取 `demo`/`assign`/`reject`，必须写 `reason` 与 `decided_by`；`assign` 必须给存在的
   `user_id`），再把 `@apply_backfill` 改为 `1` 重新执行（幂等）。脚本**不再按用户名自动匹配**：
   `demo` → `owner_user_id=NULL` + `unattributed` + `demo`；`assign` → 指定 owner + `matched` + `legacy`；
   `reject` → 撤回；没有决策的行一律 `unattributed`，对普通用户不可见。未知任务 ID、未知用户或非法
   决策值会让整批回填在写入前中止。

```sql
-- 例：仓库真实库的裁定（8–11 号待审核，先不写决策）
INSERT INTO sys_pre_defense_ownership_decision
  (task_id, decision, user_id, reason, decided_by, decided_at) VALUES
  (1,  'demo',   NULL, '2025-07 种子模拟 zscore 结果，无执行快照', 'reviewer', NOW()),
  (2,  'demo',   NULL, '2025-07 种子模拟 isolation 结果，无执行快照', 'reviewer', NOW()),
  (3,  'demo',   NULL, '恢复线程写入的失败结果，不代表真实执行', 'reviewer', NOW()),
  (4,  'demo',   NULL, '恢复线程写入的失败结果，不代表真实执行', 'reviewer', NOW()),
  (12, 'assign', 1,    'system 为平台批处理作者，人工确认归属 admin', 'reviewer', NOW());
```

脚本不改状态、结果 JSON、run id 或执行快照；回滚脚本为
`sql/security_model_ownership_rollback.sql`（只删三列，决策表保留为审计证据）。执行前请先
`mysqldump` 备份 `sys_pre_defense`，备份放在仓库外。**生产库 `eladmin` 尚未执行迁移**，
执行前需另行确认时间窗口、备份绝对路径、库名、执行人与回滚方式。

列出的 migration 脚本可重复执行。`execution_snapshot` 保存每次启动时冻结的资源和参数，
`current_run_id` 与 `current_attempt` 用于校验异步执行器回调是否属于当前运行；
`sys_pre_defense_export` 保存异步清洗子集导出任务及其进度、产物和失败原因。
`security_operation_audit` 保存任务命令和清洗子集导出的操作人、状态变化与请求快照。

启动快照默认对可访问的数据集文件计算 SHA-256，并记录文件真实路径、大小和修改时间；
可通过 `SECURITY_MODEL_FINGERPRINT_CONTENT=false` 关闭内容摘要，或通过
`SECURITY_MODEL_FINGERPRINT_MAX_BYTES` 设置参与摘要计算的文件大小上限。
LDD 结果还会记录 Python 适配器版本、解释器版本、PyTorch/NumPy 版本、CUDA 可用性、GPU
型号和关键 Python 依赖版本，用于区分“启动时配置快照”和“算法实际运行环境”。

## 启动迁移版后端

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\migration-backend
mvn spring-boot:run
```

默认后端地址：

- API 服务：`http://localhost:8100`
- 健康检查：`http://localhost:8100/actuator/health`

## 启动迁移版前端

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\migration-frontend
npm ci
npm run dev -- --host 0.0.0.0
```

默认前端地址：

- 旧版：`http://localhost:8013/`
- 迁移版：`http://localhost:8014/`

默认登录账号：

- `admin / <初始化时设置的密码>`

登录使用迁移版后端验证码与 JWT。真实数据只来自资源、任务、进度、报告、日志、导出和审计接口；
工作台（`/dashboard`）、案例库（`/cases`）、运行监控（`/runtime-monitor`）、接口扩展预览
（`/training-defense`）四页是前端硬编码的演示内容，其中包含“累计任务 28”“攻击成功率 4.7%”、
RTX 3090 节点利用率以及虚构的实验配置清单，**不代表真实任务、真实实验指标或真实硬件状态**，
演示时须口头说明。

## 验证命令

迁移版后端验证：

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\migration-backend
mvn -B verify
```

迁移版前端构建：

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\migration-frontend
npm ci
npm run build
```

旧 Vue CLI/Webpack 在 Node 17+ 环境下需要 `--openssl-legacy-provider`。构建时出现 asset size 或 Browserslist 过期提示属于当前旧前端栈的已知警告，不阻塞 MVP 联调。

迁移版后端通过 Maven `verify` 执行单元测试；使用 `-Pmysql-it` 可额外运行 MySQL 集成测试。
集成测试覆盖并发原子领取、正常完成与恢复扫描的状态竞争、孤儿任务恢复边界、迁移脚本两段式行为，
以及真实安全链路的双账号归属验收（每个类都只使用随机命名的临时 schema）。
密码通过环境变量 `MYSQL_IT_PASSWORD` 提供，不写入仓库、文档或命令历史；交互式运行可用：

```powershell
pwsh -File scripts\run-mysql-it.ps1
```

R1 的验收证据分级、接口归属审计表与迁移执行记录模板见 `docs/sdd/18-multiuser-ownership-and-resources.md` §8–§10。
GitHub Actions 工作流配置了 MySQL 8.4 服务和该集成测试；远端运行结果需以 Actions 记录为准。

旧版 ELADMIN 后端的定向测试使用以下命令单独运行，不计入迁移版测试覆盖：

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin
mvn -pl eladmin-system -am test "-Denforcer.skip=true" "-Dsecurity-model.skip-tests=false" "-Dtest=CleanSubsetExportTaskServiceImplTest,PreDefenseControllerTest,LddConfigurationPolicyTest,PreDefenseTaskStatePolicyTest,ExecutionFingerprintServiceTest,CleanSubsetExportServiceImplTest,AfterCommitTaskDispatcherTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

旧版定向测试的历史记录为 42 项通过；该记录不能代表迁移版测试数量或覆盖率。

## SDD 文档

重构迭代以 `docs/sdd` 为准：

- `docs/sdd/README.md`：文档索引。
- `docs/sdd/04-refactor-backlog.md`：重构任务与验收标准。
- `docs/sdd/05-decisions.md`：设计决策记录。
- `docs/sdd/06-mvp-runbook.md`：MVP 运行、联调和冒烟测试记录。
- `docs/sdd/07-executor-contract.md`：算法执行器契约。
- `docs/sdd/08-local-runtime-notes.md`：本地运行环境记录。
- `docs/sdd/09-project-status.md`：项目整体目标、当前进度、已完成能力和后续待办。
- `docs/sdd/16-engineering-improvement-roadmap.md`：迁移后质量门禁、任务可靠性、缓存和消息队列的实施状态与后续条件。
- `docs/sdd/10-vab-integration.md`：VaB 训练前防御算法适配、参数和产物约定。
- `docs/sdd/11-platform-implementation.md`：当前平台实现、业务流程和实现思路。
- `docs/sdd/12-resume-project-narrative.md`：简历项目描述与面试讲解提纲。
- `docs/sdd/13-framework-migration-readiness.md`：Spring Boot 3、Java 17、Vue 3 迁移验收记录。
- `docs/sdd/17-agent-development-guide.md`：后续逐轮迭代的任务范围、依赖与验收条件（规划文档）。
- `docs/sdd/18-multiuser-ownership-and-resources.md`：多用户归属、共享资源权限、历史迁移与 GPU 并发（R0 设计契约）。
- `docs/sdd/19-local-data-layout-and-retention.md`：受控本地目录布局与保留策略（R0 草案）。
- `docs/project-introduction-and-interview-guide.md`：完整项目介绍、业务流程、当前进度和面试讲解稿。
- `docs/resume-project-entry.md`：简历项目简介和 4-5 条核心项目要点。

## 当前限制

- 当前真实运行和答辩展示只覆盖 LDD 训练前数据清洗。
- 训练中、训练后和其他对比算法暂不接入主流程。
- **多用户隔离已在 R1 落地**：任务归属以 `owner_user_id` 为准，任务/报告/日志/产物/导出/审计均做 owner 校验；跨用户读对管理员默认关闭（`SECURITY_MODEL_ADMIN_CROSS_USER_READ`），且任何角色都不能跨用户写。R1 为**有条件通过**：隔离库迁移与双账号端到端验收已实跑通过（80 单测 + 29 项 MySQL IT），但生产库迁移尚未执行，不能表述为“生产级权限体系已完成”。
- **恢复护栏（ADR-017）**：执行器把 pid/启动时间写入 `process.json`；重启恢复先确认遗留 Python 已退出（启动时间匹配才杀），只有确认进程消失才释放 GPU 租约；杀不掉则保留租约到 300s TTL。R3 调度意图见 ADR-018（设计冻结、未实施）。
- **R2 第三切片已落地**：attempt 目录内日志超过 `max-log-bytes`（默认 20 MiB）只保留尾部并写截断标记；每次执行写 `manifest.json`（文件名/字节数/SHA-256）；`retention-days` 默认 0（不删除），开启后只清理“终态任务 + 非当前 attempt + 超期”的目录。Java 80 单测 + 29 IT 全绿；未跑真实 LDD。
- 升级到 R1 必须执行 `sql/security_model_ownership.sql`（先跑只读段，审核后写入 `sys_pre_defense_ownership_decision` 决策行，再在同一 MySQL 会话里 `SET @apply_backfill = 1` 重跑）；未执行迁移时任务接口会显式失败。升级前签发的旧 token 没有 `userId`，需重新登录后才能执行任务写操作。
- 令牌不可单独撤销，但每次请求都会回查 `sys_user.enabled` 与 `is_admin`：禁用账号或撤销管理员后，已签发 token 立即失去权限，无需等 8 小时过期；代价是每个认证请求一次主键查询。
- 共享数据集/算法/超参数模板的写操作仅管理员；普通用户只读。数据集登记后需管理员调用 `POST /api/dataset/{id}/validate` 才会变为可用（`parse_status='success'`）。
- 任务产物默认写在系统临时目录（`<tmp>/security-model-executor`），尚未迁到受控数据根目录（R2）。
- 同一张 GPU 的串行只靠适配器文件锁；配额不是 GPU 并发控制，陈旧锁会导致后续任务全部失败（R2 才做数据库单槽租约）。
- 新后端已经完成当前 LDD 展示范围内的认证、领域服务、数据访问和 JWT 迁移；旧工程仅作为回退基线。
- 前端任务中心已接入真实任务接口，覆盖创建表单、启动、报告摘要、日志和清洗子集导出；数据集资源、算法资源和训练前、训练中、训练后防御演示页面已建立。
- 清洗子集导出已改为异步任务，支持进度查询和失败重试；当前迁移版尚无导出任务启动恢复扫描。
- 任务详情已支持 stdout/stderr 按 offset 增量轮询，任务中心已展示有界执行池和任务配额状态；SSE/WebSocket 日志流和后续模型训练衔接仍为后续优化项。

## 可用于简历的工程亮点

- 采用 `DefenseTaskExecutor` 统一执行器边界，将 Java 任务编排与 Python/PyTorch 算法运行环境解耦。
- 采用任务状态机和数据库条件更新实现并发启动保护，使用 `run_id`、`attempt` 和任务专属目录隔离重试。
- 采用启动时不可变参数快照，支持资源、配置、结果产物和实验报告的可追溯记录。
- 通过资源内容摘要和服务层引用保护，避免数据集、算法或超参数模板被删除后破坏历史实验。
- 对外部算法产物执行任务目录 containment 校验，并限制日志读取和 PNG 预览范围。
- 通过异步导出任务按 LDD 保留样本索引重建 CIFAR-10 子集，形成从算法结果到可下载数据资产的业务闭环。

## 原始项目来源

本项目基于 ELADMIN 工程基础改造。原项目地址：

- GitHub: `https://github.com/elunez/eladmin-mp`
- Gitee: `https://gitee.com/elunez/eladmin-mp`

## VaB 真实算法接入

LDD（论文提出方法）已通过 `algorithm-runtime/vab/algorithm_entry.py` 接入 Python
执行器契约。VaB 原始代码、数据集、检查点和实验输出仍保留在外部算法目录，不会复制进本仓库。

当前完成的是平台注册、配置映射、任务隔离、结果协议和前端结果展示。已完成一轮
CIFAR-10 / SIG / batch size 128 / 5 epoch 的本地真实运行，结果产物保存在外部
VaB 目录；该运行用于验证工程闭环，不作为论文正式实验结论。

训练前任务详情支持失败重试、每次启动的参数快照和可视化实验报告。对成功的 LDD
CIFAR-10 任务，报告页可按隔离比例读取 `selected_indices_<ratio>.npy`，重建原始
CIFAR-10 训练样本并下载 ZIP；压缩包包含 PNG 图片、标签、索引及重建元数据，而非
VaB 运行期构造的触发器修改张量。

启动使用真实 VaB 算法的迁移版后端前，需要设置：

```powershell
$env:VAB_ROOT='F:\Study\postgraduate\1下\VB backdoor defence\VaB-main'
$env:SECURITY_MODEL_EXECUTOR_PYTHON_SCRIPT='F:\Study\postgraduate\毕业论文\security_model\algorithm-runtime\vab\algorithm_entry.py'
$env:SECURITY_MODEL_EXECUTOR_PYTHON_EXECUTABLE='F:\path\to\the\VaB-python.exe'
$env:SECURITY_MODEL_JWT_SECRET='<至少32字节的本地密钥>'
```

`SECURITY_MODEL_EXECUTOR_PYTHON_EXECUTABLE` 必须指向可运行 LDD 的 Python 环境。清洗子集导出仅依赖
该环境中的 `numpy`，不额外依赖 PyTorch、torchvision 或 Pillow。

然后启动迁移版后端（**不是**旧 `eladmin`）：

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\migration-backend
mvn spring-boot:run
```

注意：`--spring.profiles.active=dev,python-executor` 只在旧 `eladmin` 工程中存在
（`@Profile("python-executor")`），迁移版没有该 profile，设置它不会产生任何效果。迁移版通过
`security-model.executor.python.*` 配置键读取上述环境变量。

详细的参数映射、运行约束和验收标准见
`docs/sdd/10-vab-integration.md`。
