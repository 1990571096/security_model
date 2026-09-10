# 面向数据清洗的安全模型训练系统

本项目由早期网络安全竞赛项目重构而来，目标是形成一个面向数据清洗与安全模型训练的可视化平台。当前版本先保留 ELADMIN 既有工程基础，完成 LDD 训练前数据清洗、结果报告和清洗子集导出的可视化闭环；后续再扩展其他安全算法和安全模型训练能力。

## 当前定位

- 管理数据集、算法和 LDD 超参数模板。
- 创建并启动 LDD 训练前数据清洗任务，记录清洗结果。
- 查看失败任务并重试，保留每次启动的实验参数快照。
- 通过运行标识隔离重试过程，避免旧执行器回调覆盖新任务结果。
- 保存数据集、算法和运行环境指纹，支持核对历史实验使用的资源版本。
- 在算法结果中记录实际 Python、PyTorch、NumPy、CUDA 和 GPU 运行环境，并在实验报告中展示。
- 查看 LDD 实验报告，并按保留索引导出清洗后的 CIFAR-10 子集。
- 通过统一执行器接口为后续真实算法接入预留边界。
- 观测有界任务执行池的线程、队列和饱和状态，并在队列满载时将任务收敛为可重试失败。
- 按 SDD 文档驱动方式推进重构，降低返工风险。

## 技术栈

当前 MVP 为了减少重构风险，继续复用原项目技术栈：

- 后端：Spring Boot 2.7.18、Spring Security、JWT、MyBatis-Plus、MySQL、Redis。
- 前端：Vue 2.7、Vue CLI、Element UI。
- 构建：Maven、npm。

目标技术栈已在 SDD 中记录，后续稳定迁移方向为 Spring Boot 3、Vue 3 等成熟主流技术栈。

## 项目结构

```text
.
├── docs/sdd/                  # SDD 文档与运行手册
├── eladmin/                   # 后端工程
│   └── eladmin-system/        # 后端启动模块与安全训练业务模块
├── eladmin-web/               # 前端工程
└── sql/                       # 数据库初始化与 MVP seed 脚本
```

## 算法执行范围

当前平台只展示、只允许启动 LDD（训练前防御）。历史 mock baseline 仅保留在旧代码和数据库结构中，用于兼容迁移，不属于当前演示功能。

当前真实接入的算法为 LDD。平台通过
`PythonDefenseTaskExecutor` 和 `algorithm-runtime/vab/algorithm_entry.py` 调用外部
VaB 项目中的 `LDD.py`，并将配置、结构化结果和任务产物渲染到训练前任务详情页。

后续真实算法应继续实现后端统一的 `DefenseTaskExecutor` 边界，避免业务 CRUD
代码直接依赖具体算法实现。

## 本地环境

推荐使用本机 MySQL 和 Redis：

- MySQL：`localhost:3306`
- 数据库：`eladmin`
- 用户名：`root`
- 密码：`123456`
- Redis：默认本地服务，后端开发环境使用 Redis DB `1`

首次初始化数据库：

```sql
SOURCE sql/eladmin.sql;
SOURCE sql/security_model_mvp_seed.sql;
SOURCE sql/security_model_execution_snapshot.sql;
SOURCE sql/security_model_task_run_context.sql;
SOURCE sql/security_model_clean_subset_export.sql;
```

已有开发库只需补充 MVP 数据：

```sql
SOURCE sql/security_model_mvp_seed.sql;
SOURCE sql/security_model_execution_snapshot.sql;
SOURCE sql/security_model_task_run_context.sql;
SOURCE sql/security_model_clean_subset_export.sql;
```

三个 migration 脚本均可重复执行。`execution_snapshot` 保存每次启动时冻结的资源和参数，
`current_run_id` 与 `current_attempt` 用于校验异步执行器回调是否属于当前运行；
`sys_pre_defense_export` 保存异步清洗子集导出任务及其进度、产物和失败原因。

启动快照默认对可访问的数据集文件计算 SHA-256，并记录文件真实路径、大小和修改时间；
可通过 `SECURITY_MODEL_FINGERPRINT_CONTENT=false` 关闭内容摘要，或通过
`SECURITY_MODEL_FINGERPRINT_MAX_BYTES` 设置参与摘要计算的文件大小上限。
LDD 结果还会记录 Python 适配器版本、解释器版本、PyTorch/NumPy 版本、CUDA 可用性、GPU
型号和关键 Python 依赖版本，用于区分“启动时配置快照”和“算法实际运行环境”。

## 启动后端

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin
mvn -pl eladmin-system -am spring-boot:run -Denforcer.skip=true
```

默认后端地址：

- API 服务：`http://localhost:8000`
- 接口文档：`http://localhost:8000/doc.html`

## 启动前端

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin-web
$env:NODE_OPTIONS='--openssl-legacy-provider'
npm run dev -- --host 0.0.0.0
```

默认前端地址：

- `http://localhost:8014/`

默认登录账号：

- `admin / 123456`

## 验证命令

后端编译：

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin
mvn -pl eladmin-system -am compile -Denforcer.skip=true
```

前端构建：

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin-web
$env:NODE_OPTIONS='--openssl-legacy-provider'
npm run build:prod
```

旧 Vue CLI/Webpack 在 Node 17+ 环境下需要 `--openssl-legacy-provider`。构建时出现 asset size 或 Browserslist 过期提示属于当前旧前端栈的已知警告，不阻塞 MVP 联调。

后端定向测试默认被原项目配置跳过，需要显式打开：

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin
mvn -pl eladmin-system -am test "-Denforcer.skip=true" "-Dsecurity-model.skip-tests=false" "-Dtest=CleanSubsetExportTaskServiceImplTest,PreDefenseControllerTest,LddConfigurationPolicyTest,PreDefenseTaskStatePolicyTest,ExecutionFingerprintServiceTest,CleanSubsetExportServiceImplTest,AfterCommitTaskDispatcherTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

当前核心服务、控制器、策略、快照、导出幂等、队列拒绝和批量接口边界测试共 27 项；普通 Maven 流程仍保持跳过测试，
以兼容原项目的默认打包行为。

## SDD 文档

重构迭代以 `docs/sdd` 为准：

- `docs/sdd/README.md`：文档索引。
- `docs/sdd/04-refactor-backlog.md`：重构任务与验收标准。
- `docs/sdd/05-decisions.md`：设计决策记录。
- `docs/sdd/06-mvp-runbook.md`：MVP 运行、联调和冒烟测试记录。
- `docs/sdd/07-executor-contract.md`：算法执行器契约。
- `docs/sdd/08-local-runtime-notes.md`：本地运行环境记录。
- `docs/sdd/09-project-status.md`：项目整体目标、当前进度、已完成能力和后续待办。
- `docs/sdd/10-vab-integration.md`：VaB 训练前防御算法适配、参数和产物约定。
- `docs/sdd/11-platform-implementation.md`：当前平台实现、业务流程和实现思路。
- `docs/sdd/12-resume-project-narrative.md`：简历项目描述与面试讲解提纲。

## 当前限制

- 当前真实运行和答辩展示只覆盖 LDD 训练前数据清洗。
- 训练中、训练后和其他对比算法暂不接入主流程。
- 前端和后端仍保留部分 ELADMIN 历史模块与技术债，后续按 SDD 逐步裁剪和迁移。
- 清洗子集导出已改为异步任务，支持进度查询、失败重试和应用重启恢复；旧同步下载接口仅用于兼容。
- 任务详情已支持 stdout/stderr 按 offset 增量轮询，任务中心已展示有界执行池状态；SSE/WebSocket 日志流、运行资源配额和后续模型训练衔接仍为后续优化项。

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

启动使用真实 VaB 算法的后端前，需要设置：

```powershell
$env:VAB_ROOT='F:\Study\postgraduate\1下\VB backdoor defence\VaB-main'
$env:SECURITY_MODEL_PYTHON_SCRIPT='F:\Study\postgraduate\毕业论文\security_model\algorithm-runtime\vab\algorithm_entry.py'
$env:PYTHON_EXECUTABLE='F:\path\to\the\VaB-python.exe'
```

`PYTHON_EXECUTABLE` 必须指向可运行 LDD 的 Python 环境。清洗子集导出仅依赖该环境
中的 `numpy`，不额外依赖 PyTorch、torchvision 或 Pillow。

然后用 Python 执行器 profile 启动后端：

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin
mvn -pl eladmin-system -am spring-boot:run -Denforcer.skip=true "-Dspring-boot.run.profiles=dev,python-executor"
```

详细的参数映射、运行约束和验收标准见
`docs/sdd/10-vab-integration.md`。
