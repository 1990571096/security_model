# 安全模型训练平台：设计思路

更新时间：2026-10-26

本文从业务目标、系统边界和架构流程解释项目“为什么这样设计”。代码实现细节见[实现说明](project-implementation-guide.md)，面试表达与追问见[面试准备手册](project-interview-preparation.md)。

## 1. 项目定位

项目由网络安全创新竞赛中的后门防御算法工程发展而来，目标是把研究型算法包装成可配置、可执行、可观测、可追溯的实验平台。当前实际闭环围绕训练前 LDD 数据清洗：用户登记实验资源、创建并启动任务，平台调用外部 Python/PyTorch 算法，保存结果和产物，再根据保留样本索引导出 CIFAR-10 清洗子集。

需要先区分两个层次：

| 层次 | 当前情况 |
| --- | --- |
| 平台规划与界面 | 菜单和页面覆盖训练前、训练中、训练后三个阶段，可展示多个算法方案。 |
| 后端真实执行 | 仅开放 `vab://pre-defense/ldd`，其他阶段和其他算法不进入真实任务执行链路。 |

因此，面试中可以说“平台按三阶段设计了可扩展目录和展示入口，当前已完成 LDD 训练前防御的真实执行闭环”，不应说“多种三阶段算法均已实际接入运行”。

## 2. 设计目标

平台要解决的不是单纯从网页启动一个 Python 脚本，而是把一次实验作为有生命周期的业务任务管理：

1. 任务执行前验证数据集、算法阶段、实现路径和配置，尽量把错误挡在运行态之前。
2. 长耗时算法不占用 HTTP 请求线程，并具有状态、进度、日志、取消和重试能力。
3. 每次运行都有唯一标识和独立目录，重试不会覆盖上次运行的日志与产物。
4. 参数、资源身份和运行环境可以回看，让报告解释清楚“本次实验实际使用了什么”。
5. 外部算法与 Java 业务服务解耦，平台不需要把 PyTorch 依赖塞进后端进程。
6. 将 LDD 的保留索引转化为可下载的清洗子集，而不把算法内部的中间张量误当作最终数据集。

## 3. 总体架构

```text
┌────────────────────────────── 浏览器 ──────────────────────────────┐
│ Vue 3 / Vite / Element Plus / Pinia / Vue Router                  │
│ 资源选择、任务中心、状态轮询、日志、报告、导出、三阶段方案展示      │
└──────────────────────────────┬────────────────────────────────────┘
                               │ HTTP + JWT
┌──────────────────────────────▼────────────────────────────────────┐
│ Spring Boot 3 / Java 17                                           │
│                                                                    │
│ Web Controller → Application Service → Domain / Persistence       │
│                            │                                       │
│                            ├─ 参数与资源校验                        │
│                            ├─ 任务状态、配额、快照、审计             │
│                            └─ 事务提交后派发                        │
│                                  │ 有界 JVM 线程池                  │
│                                  ▼                                 │
│                         PythonLddTaskExecutor                      │
└──────────────────────────────┬────────────────────────────────────┘
                               │ request.json / result.json / progress.json
                               │ stdout.log / stderr.log
┌──────────────────────────────▼────────────────────────────────────┐
│ Python 适配器 algorithm_entry.py                                   │
│ 配置合并、参数映射、CUDA 预检、AST 生成单任务脚本、进度快照、产物收集│
└──────────────────────────────┬────────────────────────────────────┘
                               │ 子进程
                               ▼
                    外部 VaB 工程中的 LDD.py
                    数据集 / 模型 / 训练与清洗逻辑

进度旁路：
LDD / 适配器 ──原子替换 progress.json──> PythonProcessWaiter
            ──校验 run_id、attempt 并条件更新──> MySQL 数值进度
浏览器 ──每 3 秒请求日志接口──> Java 返回日志增量和 progress.json 快照

Spring Boot ─────────────── MySQL
  任务状态、运行快照、结果 JSON、审计、导出任务

Spring Boot ─────────────── Redis
  可重建的资源目录缓存（TTL 5 分钟；失效时回源 MySQL）

Python / VaB ────────────── 文件系统
  请求响应文件、日志、索引、图像、检查点、导出 ZIP
```

Java、Python 与算法仓库之间通过 JSON 文件协议和进程边界协作。外部 VaB 工程仍维护算法源码、数据和训练产物；平台侧维护任务业务、适配器、执行协议和结果展示。

## 4. 模块职责

| 模块 | 主要职责 | 关键代码 |
| --- | --- | --- |
| 前端 | 资源查询、LDD 任务创建/操作、状态与执行池展示、报告和日志查看、导出操作；三阶段目录提供方案展示 | `migration-frontend/src/views/TaskCenterView.vue`、`DefenseStageView.vue` |
| Web 接口 | 接收 HTTP 请求、进行接口层绑定并调用应用服务 | `migration-backend/src/main/java/com/securitymodel/interfaces/web/` |
| 任务应用服务 | 校验任务命令、创建快照、状态转换、审计、启动/重试/取消 | `application/task/PreDefenseTaskCommandService.java` |
| 持久化层 | 读写业务记录；用带状态条件的 SQL 实现原子领取和回调保护 | `infrastructure/persistence/mapper/PreDefenseTaskMapper.java` |
| 执行器契约 | 以稳定的任务请求描述执行输入，不让 Controller 依赖具体算法 | `application/task/DefenseTaskRequest.java`、`infrastructure/executor/DefenseTaskExecutor.java` |
| Python 执行器 | 创建本次执行目录、启动和等待 Python 进程、检查结果协议、更新进度和终态 | `infrastructure/executor/PythonLddTaskExecutor.java` |
| 算法适配器 | 将平台参数映射到 VaB LDD，隔离原始脚本的固定实验循环和输出目录 | `algorithm-runtime/vab/algorithm_entry.py` |
| 报告与日志 | 聚合任务、快照、算法结果和导出记录；按偏移读取日志 | `application/report/` |
| 清洗子集导出 | 校验 LDD 索引，异步重建并打包原始 CIFAR-10 训练样本 | `application/export/` |

当前迁移版使用 MySQL 和 MyBatis-Plus 保存业务数据，任务执行由 JVM 内的有界线程池承担。Redis 已接入，但只缓存可由 MySQL 重建的数据集、算法和超参数目录等资源元数据，默认 TTL 为 5 分钟；Redis 不保存任务状态，也不是任务队列。RabbitMQ、Kafka 均未接入。

## 5. 核心业务流程

### 5.1 资源到任务

```text
用户登录
  → 选择已解析的数据集、已启用的 LDD 算法和可选超参数模板
  → 创建 pending 任务
  → 任务启动时再次校验资源是否仍有效
  → 合并默认配置、模板配置、任务自定义参数
  → 通过单机配额检查
  → 创建 run_id / attempt 和本次执行快照
  → 数据库条件更新领取任务
  → 事务提交后向有界线程池派发
```

这里有意区分“创建任务”和“启动任务”：任务可以先作为 `pending` 记录保存；真正启动时重新检查资源和参数，避免配置在创建后变更或失效。

### 5.2 Python 执行与结果回写

```text
Java 任务服务创建 DefenseTaskRequest
  → Jackson 序列化到 attempt 专属目录的 request.json
  → ProcessBuilder 启动 Python 适配器，并传入 request/result/progress 文件路径
  → Python 读取 request.json、校验算法白名单、合并并验证配置
  → Python 生成单次运行脚本，在 VaB 根目录取得独占文件锁后以 Popen 启动
  → 算法子进程 stdout/stderr 合并后逐行转发到 execution.log 和适配器 stdout
  → 适配器和生成脚本原子更新 progress.json，记录阶段、epoch 与心跳
  → Java 等待器读取变化的进度快照，并按运行令牌条件更新 MySQL 数值进度
  → 适配器把结构化执行结果写入 result.json，Python 进程退出
  → Java 读取并校验 result.json，再按 taskId + runId + running 写回任务终态
  → 前端每 3 秒通过日志接口取得日志增量和进度快照
```

这条链路没有 Python 到 Java 的 HTTP 回调。Java 启动并等待的是外层 `algorithm_entry.py` 适配器；适配器内部再启动 LDD 算法子进程。算法子进程的 stdout 与 stderr 被合并到管道，适配器逐行读取后同时写入产物目录的 `execution.log` 并打印到自己的 stdout，因此相同的算法输出也会经 Java `ProcessBuilder` 进入 attempt 目录的 `stdout.log`。`stderr.log` 记录的是适配器进程自身的标准错误，不是算法子进程的独立 stderr。

**配置覆盖顺序：**

```text
算法默认配置 < 超参数模板 < 本次任务自定义参数
```

后加载的同名参数覆盖前者。启动前 Java 做白名单、必填项、类型和范围校验；Python 适配器再按 VaB 需要完成路径和运行环境预检。

### 5.3 状态机

```text
                 ┌───────────── 重试（新 attempt）─────────────┐
                 │                                             │
pending ──启动──> running ──正常结果──> success                 │
                    │                                            │
                    ├──异常/超时/启动拒绝/重启恢复──> failed ───┘
                    │
                    └──用户取消──> canceled
```

`failed` 重试会清理当前显示的运行结果和时间字段，再生成新的 `run_id`、`attempt` 和目录；历史启动参数仍保存在 `execution_snapshot.attempts` 中。当前启动恢复策略是把失去进程的 `running` 训练任务标记为可重试失败，不尝试恢复原 Python 进程，也不自动重跑。

### 5.4 一次任务的端到端口述

面试或演示时可以按下面顺序，从用户点击“启动”讲到结果显示：

1. 用户先在前端创建任务。Axios 把任务名、数据集 ID、算法 ID、可选模板 ID 和自定义参数发给 `POST /api/pre-defense`；Java 校验后将任务保存为 `pending`。创建任务本身不会启动 Python。
2. 用户启动任务后，前端调用 `POST /api/pre-defense/start/{taskId}`。Spring Controller 把命令交给 `PreDefenseTaskCommandService.start()`。
3. 服务再次从资源层读取数据集、算法和模板，确认数据集启用且解析成功，算法启用、阶段为 `pre`、实现路径为 `vab://pre-defense/ldd`，并重新校验合并后的参数。
4. 服务构造 `DefenseTaskRequest`，生成新的 UUID `runId`，根据快照历史生成递增的 `attempt`。同一请求快照追加到 `execution_snapshot.attempts`。
5. 配额策略检查当前 JVM 的执行池和运行任务数。随后 Mapper 执行一条 `UPDATE ... WHERE task_id=? AND status='pending'`。只有影响一行的启动请求取得执行权；任务状态、运行令牌、尝试次数和快照在事务中一起落库。
6. 事务提交后，`AfterCommitTaskDispatcher` 才调用执行器。执行器通过 Jackson 把请求对象序列化为 UTF-8 的 `request.json`，然后用 `ProcessBuilder` 的参数列表启动 Python 适配器。请求 JSON 不是写入标准输入，也没有经过 HTTP。
7. `algorithm_entry.py` 根据 `--request` 读取 JSON，根据 `--stage` 检查阶段，根据请求中的 `algorithm.path` 查白名单；它把算法默认值、模板值和任务自定义参数合并后预检数据路径、VaB 源码和 CUDA 环境。
8. 适配器通过 AST 将原始 LDD 脚本调整为本次任务参数和独立输出目录。正式训练前，它以 `os.open(..., O_CREAT | O_EXCL | O_WRONLY)` 独占创建 `VAB_ROOT/.security-model-vab.lock` 并写入适配器 PID；锁已存在时当前任务快速失败，不排队。取得锁后，适配器用 `subprocess.Popen()` 启动生成脚本，把算法子进程 stderr 合并到 stdout，再逐行写入 `execution.log` 并打印到适配器 stdout。Java 的进程重定向因此把同一份算法输出也写入 `stdout.log`；`stderr.log` 主要用于适配器自身错误。
9. 执行期间，适配器在准备、训练启动、epoch 训练、结果整理和结束等节点写入 `progress.json`。请求解析后，快照包含阶段、阶段说明、百分比、提示消息、`run_id`、`attempt` 和心跳时间；训练时还包含当前 epoch 与总 epoch。Python 先写同目录临时文件，再用 `os.replace` 原子替换，避免 Java 读到半截 JSON。
10. Java 的 `PythonProcessWaiter` 等待的是 Java `ProcessBuilder` 启动的外层适配器进程，而不是直接等待 LDD 算法子进程。等待期间它比较进度文件的修改时间和大小，文件变化后由执行器解析快照；运行标识不匹配或进度格式无效的快照会被忽略。有效百分比在任务运行期间限制为 `0..99`，再通过带当前 `run_id` 和 `running` 状态条件的 SQL 更新任务数值进度。进度快照是观测旁路，不是 Python 对 Java 的 HTTP 回调。
11. 任务详情页每 3 秒刷新任务详情和日志接口。日志服务增量读取日志，并从当前尝试目录读取进度快照；前端据此展示阶段、epoch、消息和心跳。超过 60 秒没有心跳只显示“较久未更新”，不据此判定任务失败。
12. LDD 结束后，适配器在正常执行路径的 `finally` 中删除独占锁，再收集指标、索引、图像、模型文件和 Python 运行环境，写出 `result.json` 并以退出码表示适配器运行是否成功。Java 等适配器进程结束后读取并校验结果协议，只有校验通过并成功条件更新数据库，任务才进入成功终态。若适配器被强制终止，Python 的 `finally` 可能无法执行，锁文件可能残留；排障时必须先确认没有 VaB 任务仍在运行，再人工清理残留锁。`progress.json` 的结束快照不能替代 `result.json` 和数据库终态。
13. 报告 API 汇总快照和结果，日志 API 根据客户端 offset 增量读取文件。若用户申请导出，平台另建异步导出任务，按 LDD 的保留索引重建 CIFAR-10 子集。

可以把这一流程压缩成一句话：**Java 写请求文件并启动 Python，Python 读文件并运行算法后写结果文件，Java 等进程退出后读结果文件、校验运行令牌并回写 MySQL。**

## 6. 关键设计决策

### 6.1 用执行器接口隔离算法实现

任务服务依赖 `DefenseTaskExecutor`，执行器接收 `DefenseTaskRequest`。任务服务知道业务资源和状态，但不必知道 LDD 的训练函数、PyTorch 模型或原脚本目录结构。后续要接算法时，应新增或扩展执行器适配、参数 schema、结果映射和对应测试，而不是让 Controller 直接启动脚本。

### 6.2 用数据库条件更新保护任务状态

不能只在 Java 中先查状态再更新，因为两个并发请求可能都读到 `pending`。领取 SQL 使用 `WHERE task_id = ? AND status = 'pending'`，只有更新行数为 1 的请求取得执行权。进度、结果和完成 SQL 继续检查 `task_id + current_run_id + status = 'running'`，迟到或过期回调更新不到当前记录。

### 6.3 事务提交后才派发外部副作用

任务状态、当前运行令牌、快照和审计记录先在事务中落库；通过 `AfterCommitTaskDispatcher` 等事务提交后再调用执行器。避免出现“数据库事务回滚了，但 Python 进程已经开跑”的不一致。

### 6.4 每次尝试独立化

任务 ID 标识业务任务，`attempt` 表示第几次尝试，`run_id` 是本次运行令牌。执行工作目录按 `pre_defense-{taskId}/attempt-{attempt}` 隔离，包含请求、响应和标准输出/错误日志。这样重试可保留旧目录，并且运行令牌可拦截旧回调。

### 6.5 将“计划输入”和“实际环境”分别记录

启动快照记录 Java 侧冻结的资源、配置、路径和环境信息；Python 结果中的 `runtime` 记录适配器实际使用的 Python、PyTorch、NumPy、CUDA、GPU 和依赖版本。数据集文件只有在开启内容指纹且大小不超过配置上限时才计算 SHA-256；目录或超限文件不能描述为已完整哈希。

### 6.6 把算法产物视为不可信输入

日志、PNG、索引和 ZIP 的路径要限制在任务产物根目录内，并校验文件类型。执行结果也要校验字段、阶段、状态和运行标识，不能因为 Python 进程返回了一个 JSON 就直接当作成功结果。

### 6.7 Redis 只缓存资源目录，不参与任务调度

`ResourceCatalogCache` 缓存可重建的资源元数据，默认 TTL 为 5 分钟，当前覆盖部分数据集、算法和超参数目录查询，以及数据集、算法按 ID 查询。资源查询先尝试 Redis，未命中、值为空、反序列化失败或 Redis 访问异常时返回 cache miss，再由 `ResourceQueryService` 查询 MySQL 并回填缓存。数据集和算法的新增、编辑或停用会在事务提交后失效对应缓存键；MySQL 始终是事实源。

这项设计降低资源目录重复读取的数据库压力，同时让 Redis 故障不会阻断资源查询。它不改变任务状态机：任务状态、运行快照、报告和导出任务仍以 MySQL 为准，线程池仍在单个 JVM 内执行；Redis 没有承担分布式锁、消息队列或 GPU 调度器的职责。

### 6.8 用进度快照提供训练期可观测性

长时间训练期间，单靠进程是否存活和日志尾部不足以解释任务正在做什么。适配器与 AST 生成的任务脚本通过 `progress.json` 发布阶段、epoch、消息和心跳；文件原子替换，Java 等待器在进程等待循环中观察文件元数据变化后读取。Java 检查快照中的 `run_id`、`attempt`（字段存在时），并通过运行中的任务条件更新 MySQL 数值进度；日志查询接口则从本次尝试目录读取完整快照，前端据此显示阶段、epoch 和最近心跳。

这是一个辅助观测通道，不是完成协议。任务详情页每 3 秒轮询任务详情和日志接口；运行任务的心跳超过 60 秒未更新时，前端只显示“较久未更新”，不据此判定失败。即使快照显示训练结束，Java 仍需等待进程退出、验证 `result.json` 并条件写入数据库终态；成功终态及 100% 完成进度以 Java 校验结果后的数据库更新为准。

### 6.9 用文件锁保护共享 VaB 运行目录

适配器在真正启动训练前，通过 `os.open()` 的 `O_CREAT | O_EXCL | O_WRONLY` 标志尝试创建 `VAB_ROOT/.security-model-vab.lock`，并把当前适配器 PID 写入文件。原子独占创建成功表示当前进程取得共享 VaB 运行目录的使用权；若文件已存在，任务立即失败，不进入等待队列。正常训练结束或子进程异常退出时，适配器的 `finally` 会删除锁。

这是一种基于共享文件系统路径的互斥标记，不是 Redis 锁、数据库租约或跨机器 GPU 调度器。若 Java 超时或取消时强制终止了适配器，Python 清理代码可能没有机会运行，锁文件就可能残留，后续任务会持续快速失败。排查残留锁前要先确认没有其他 VaB 任务正在运行；确认后才可人工删除锁文件。PID 仅用于排障线索，不能单凭 PID 或文件时间自动断定锁已失效。

## 7. 业务实体与存储

| 实体 | 作用 | 代表字段 |
| --- | --- | --- |
| 数据集 / 算法 / 超参数 | 可复用的实验资源和配置来源 | 资源 ID、路径、阶段、实现路径、配置 JSON |
| `sys_pre_defense` | 训练前任务及当前运行视图 | `status`、`progress`、`cleaning_result`、`execution_snapshot`、`current_run_id`、`current_attempt` |
| `sys_pre_defense_export` | 清洗子集导出任务的独立状态 | `task_id`、`isolation_ratio`、`status`、`file_path`、`failure_reason` |
| `security_operation_audit` | 记录关键操作及操作人 | 资源类型/ID、动作、状态前后值、请求快照、时间 |

结果 JSON 与执行快照分开放置：`execution_snapshot` 追加记录每次启动的输入和环境指纹，`cleaning_result` 保存当前运行的结构化结果与产物信息。

## 8. 当前平台范围与扩展边界

### 已有真实闭环

- LDD 训练前任务的创建、验证、运行、取消、失败重试、报告和日志。
- Python 子进程调用 VaB 的 LDD 原始算法代码。
- 本地单机线程池配额、任务快照、运行环境记录和操作审计。
- 根据 LDD 保留索引异步重建 CIFAR-10 原始训练子集并生成 ZIP。
- 使用 Redis 缓存可重建的资源目录元数据，MySQL 回源并作为唯一事实源。

### 当前不应当作已完成的能力

- 其他训练前算法、训练中防御算法、训练后防御算法的真实执行。
- 分布式任务调度、跨节点租约、消息队列调度、GPU 资源调度。
- 容器级 CPU/内存/GPU 硬隔离。
- 将清洗子集自动衔接后续模型训练。
- 论文正式实验结论。已记录的工程验证用于验证系统链路，不替代正式实验。

资源目录、资源持久化、任务、报告、日志、审计和导出均接入迁移版后端；Redis 只用于部分资源查询缓存。三阶段方案展示中，除 LDD 外的算法仍为占位内容。演示模式的数据和界面指标不能用作真实任务执行或正式实验的证据。

## 9. 快速复习

可以用一句话记住设计主线：

> 用 Spring Boot 管理实验任务和状态，用有界线程池异步启动 Python 进程，用 JSON 契约隔离算法，用 `run_id + attempt + 条件更新` 保护并发与重试，再用快照、日志、报告和索引导出形成可追溯闭环。

面试时讲解顺序建议是：**业务问题 → 总体架构 → 一次任务的完整链路 → 并发与故障处理 → 实验追溯与导出 → 当前边界和扩展路线**。

复习时请把“通信协议”和“算法执行”分开记：JSON 文件是 Java 与 Python 适配器之间的进程协议；`subprocess.Popen()` 是适配器启动 VaB 算法脚本并流式收集日志的子进程调用；MySQL 条件更新则是 Java 平台更新任务状态的持久化协议。Java 等待的是适配器进程，`progress.json` 是进度观测旁路，`result.json` 才是适配器交回的结构化结果。
