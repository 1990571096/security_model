# 安全模型训练平台：面试准备手册

更新时间：2026-10-26

本文基于当前仓库实现，帮助把项目讲清楚、讲可信。建议先读[设计思路](project-design-overview.md)理解业务，再用[实现说明](project-implementation-guide.md)复习代码。

## 1. 面试定位

一句话定位：

> 我把网络安全竞赛中的研究型 LDD 后门防御代码接入一个可追踪的实验任务平台，重点完成了任务编排、Java/Python 执行边界、并发状态保护、运行追溯和 CIFAR-10 清洗子集导出。

更完整的事实口径：

> 平台按训练前、训练中、训练后三阶段组织算法展示和扩展入口；当前真实接入并可通过平台运行的是训练前 LDD。其他算法和阶段页面属于展示或规划，不应描述为已实际运行。

面试可信度来自把范围讲准确，再深入解释自己真正实现的可靠性与集成细节，而不是把页面上的方案卡片说成算法接入。

## 2. 一分钟项目介绍

> 我做的是一个面向深度神经网络后门防御的实验平台，项目来源于网络安全创新竞赛。当前真实闭环是训练前 LDD 数据清洗：用户配置数据集和参数后，Spring Boot 后端校验资源，生成执行快照，再通过有界线程池启动 Python 适配器调用外部 VaB 工程中的 LDD。浏览器到 Java 使用 HTTP/JSON，Java 与 Python 通过请求、结果 JSON 文件协作；训练中的阶段、epoch 和心跳另写入原子替换的 `progress.json`，Java 等待器据此更新数值进度，前端通过日志接口轮询展示详情。针对并发启动、重试和迟到写入，我用数据库条件更新、`run_id` 和 `attempt` 保护状态；任务支持取消和失败重试，应用重启后遗留的 running 任务会被标记成可重试失败。结果页会展示配置快照、Python/PyTorch/CUDA/GPU 环境和 LDD 指标，并能按保留索引异步重建 CIFAR-10 原始训练子集。平台界面按三阶段扩展，但目前只有 LDD 是真实执行算法。

建议实际面试时再加一句个人贡献，例如“我主要负责任务后端和 Python 适配链路”，但只说自己确实承担的模块。

## 3. 三分钟展开顺序

### 3.1 业务问题

研究脚本适合单次实验，不天然具备 Web 平台需要的状态机、并发保护、取消重试、运行日志、环境记录和安全产物下载。我把核心对象从“直接执行脚本”抽象成“有生命周期的一次实验任务”。

### 3.2 架构与数据流

```text
Vue 3 页面
  → Spring Boot Controller / Application Service
  → MySQL 任务状态、运行快照、审计
  → after-commit + 有界线程池
  → PythonLddTaskExecutor
  → algorithm_entry.py
  → VaB 的 LDD.py
  → result.json、日志、索引和模型产物
  → 报告与 CIFAR-10 子集导出
```

数据库保存任务元数据和结构化 JSON；文件系统保存体积较大的日志和训练产物，避免把模型或图片二进制塞进任务记录。浏览器到后端是 HTTP/JSON，Java 到 Python 才是文件 JSON 协议。

### 3.3 自己解决的工程问题

重点讲清三个设计：

1. **任务原子领取**：`UPDATE ... WHERE status='pending'`，更新一行才算拿到任务，避免先查后改的竞态。
2. **重试与迟到回调隔离**：每次执行创建新的 `run_id` 和 `attempt`；所有结果更新都必须匹配当前 `run_id` 和 `running` 状态。
3. **外部副作用与数据库事务一致**：先提交任务状态和执行快照，再由事务提交回调派发 Python 进程。

再补充实验追溯：每次尝试保存冻结请求和资源指纹；Python 结果记录实际运行环境。SHA-256 只覆盖符合开关和文件大小上限的普通文件。

### 3.4 结果闭环和边界

LDD 输出保留样本索引，平台按索引从原始 CIFAR-10 训练数据重建图片、标签、索引和元数据，再压成 ZIP。它不是把运行中用于实验的触发器修改张量直接打包。

当前使用单机 JVM 线程池和本地 Python 进程，没有分布式 worker、Redis 队列或容器级 GPU 隔离。界面上的训练中/训练后和其他算法不属于当前真实运行范围。

## 4. 可直接复述的五分钟版本

> 这个项目是一个面向深度神经网络后门防御研究的实验任务平台，来源于网络安全创新竞赛。研究代码原本是独立运行的 Python 脚本，不方便管理任务状态、并发启动、失败重试、运行记录和结果下载。我负责把真实的训练前 LDD 数据清洗流程接入平台，并围绕它补齐任务生命周期和实验追溯。  
>
> 系统前端是 Vue 3，后端是 Java 17 和 Spring Boot，MySQL 保存资源、任务、执行快照、结构化结果、审计和导出状态。Redis 只缓存部分可重建的资源目录，任务状态不放 Redis，任务执行也不是通过 Redis 队列调度。  
>
> 用户先创建一个 pending 任务。前端通过 HTTP 把数据集、算法、超参数模板和自定义参数交给 Spring Controller，应用服务检查数据集解析状态、算法启用状态和实现路径，并合并默认配置、模板配置和任务参数。当前后端只放行实现路径 `vab://pre-defense/ldd`，所以三阶段页面是扩展目录，不能等同于三阶段算法都已真实接入。  
>
> 用户点击启动后，Java 再做一次校验，生成本次运行的 UUID `run_id` 和递增的 `attempt`，把请求、环境和配额信息追加到执行快照。数据库用一条带 `status = pending` 条件的 UPDATE 把任务原子领取为 running。事务提交以后，才由本机有界线程池派发执行器，避免数据库回滚了但 Python 已经启动。  
>
> Java 执行器先把 `DefenseTaskRequest` 用 Jackson 序列化成 UTF-8 的 `request.json`，再用 `ProcessBuilder` 的参数列表启动 Python 适配器，并传 `--stage`、`--request`、`--result` 和 `--progress-file` 参数。这里不是 HTTP、不是标准输入传 JSON，也没有 Python 主动回调 Java。Python 入口用 argparse 解析文件路径，用 `json.loads` 读请求，根据 `algorithm.path` 做白名单检查，再合并并校验参数。  
>
> 适配器用 AST 为这次任务生成独立的 LDD 脚本，并在训练循环中注入 epoch 进度上报，再启动 VaB 算法子进程。也就是说有两层 Python 进程：Java 启动适配器，适配器再启动算法脚本。适配器和生成脚本把阶段、epoch、百分比、消息及心跳写到原子替换的 `progress.json`；Java 等待器看到文件变化后校验运行标识并条件更新数据库中的数值进度。任务详情每 3 秒请求日志接口取得日志增量和完整进度快照，心跳超过 60 秒只显示提醒。LDD 完成后，适配器把状态、指标、运行环境和产物引用写入 `result.json`，然后退出。Java 等待适配器退出，读取并校验结果里的阶段、`run_id`、`attempt` 和状态，再通过带当前运行令牌的条件 SQL 更新结果和终态；因此进度文件不是完成回调，最终成败以结果协议和数据库状态为准。  
>
> 并发与重试方面，`taskId` 表示业务任务，`attempt` 表示第几次尝试，`run_id` 是具体一次运行的唯一令牌；每次尝试有独立目录，旧尝试的迟到更新无法覆盖新尝试。取消会先把数据库任务改为 canceled，再通知执行器强制终止它登记的适配器进程；这不等于已实现跨平台的整棵子进程树清理。应用重启时，遗留的 running 训练任务会被标记成 `EXECUTOR_RESTARTED` 失败，交给用户显式重试，而不是自动续跑。  
>
> 成功后报告会呈现请求快照、指标、环境和日志。LDD 保存的保留索引还可以触发异步 CIFAR-10 子集导出：导出器按索引从原始训练 batch 重建图像和标签，再生成 ZIP。它不是把算法运行时内存里的触发器修改数据直接打包。当前系统完成的是 LDD 的真实工程闭环；其他算法和阶段仍需独立完成适配、结果协议和测试后才能称为真实接入。

## 5. Java 与 Python 通信：白板顺序

把通信拆成三条边，面试时就不会把 HTTP、子进程和数据库混成一件事：

| 边界 | 谁与谁通信 | 实际方式 | 传递内容 |
| --- | --- | --- | --- |
| 页面到后端 | 浏览器与 Spring Boot | HTTP/JSON + JWT | 创建、启动、查询任务等业务请求 |
| Java 到适配器 | Java 执行器与 `algorithm_entry.py` | 本机进程 + 共享文件系统 | Java 写 `request.json`，命令行传请求、结果和进度文件路径；Python 写 `result.json` 与 `progress.json` |
| 适配器到算法 | Python 适配器与 VaB LDD | Python 子进程 | 启动 AST 生成的单次任务脚本；日志和算法产物写文件 |
| 执行器到数据库 | Java 与 MySQL | MyBatis-Plus / SQL | 进度、结构化结果和终态的条件更新 |
| 训练进度展示 | 适配器、Java 等待器、日志接口和浏览器 | `progress.json` 文件旁路 + HTTP 轮询 | Java 观察变化并条件更新百分比；日志接口返回阶段、epoch、消息和心跳快照 |

**请求是怎样发出去的：**

1. `PreDefenseTaskCommandService` 根据数据库资源记录组装 `DefenseTaskRequest`，其中包括 `taskId`、`attempt`、`runId`、`stage`、`dataset`、`algorithm`、`hyperParameter` 和 `customParams`。
2. `PythonLddTaskExecutor` 创建 `pre_defense-{taskId}/attempt-{attempt}` 目录，并将 Java 对象编码为 UTF-8 JSON 写入 `request.json`。
3. Java 用 `ProcessBuilder(List<String>)` 启动解释器和适配器，参数包括 `--stage pre_defense --request <request.json> --result <result.json> --progress-file <progress.json>`。JSON 本体留在文件中，命令行只传路径；`ProcessBuilder` 不经过 shell 字符串拼接。
4. Python 的 `argparse` 解析三个参数，`Path(...).read_text(encoding="utf-8")` 读取请求文件，`json.loads()` 把文本解析为字典。适配器按 `request["algorithm"]["path"]` 找白名单实现，并再次检查配置。

一个常见追问是“为什么请求 JSON 里的参数看起来还有引号和转义符”。`DefenseTaskRequest.customParams` 以及资源对象里的 `configJson` 类型是 Java `String`，因此外层 JSON 会把内层配置 JSON 编码成字符串。Python 解析外层文件后，`request["customParams"]` 仍是字符串；适配器再调用 `load_json_object()` 对它执行第二次 `json.loads()`，才得到可以合并的配置字典。可以把它画成“外层协议对象 -> 字段内配置文本 -> 配置字典”三步。

**结果是怎样回来的：**

1. 适配器取得 VaB 根目录的独占文件锁后，以 `subprocess.Popen()` 启动 AST 生成的 LDD 脚本。LDD 子进程的 stdout 和 stderr 合并到管道，适配器逐行读取，每行同时写入 `execution.log` 并打印到自身 stdout；训练脚本在 epoch 边界写入 `progress.json`，适配器同时负责准备、启动、整理和结束阶段的快照。
2. 每次写快照时先写同目录临时文件，再以 `os.replace()` 原子替换。训练快照包含 `run_id`、`attempt`、`phase`、`phase_label`、`progress`、`epoch`、`total_epochs`、`message` 和 `heartbeat_at`。适配器最后仍把结构化结果写入 Java 预先指定的 `result.json`，以进程退出码表示适配器整体成功或失败。
3. Java 的 `PythonProcessWaiter` 等待的是 Java 启动的外层适配器进程，在等待期间约每 200 毫秒检查一次进程，并在进度文件修改时间或大小变化时通知执行器读取快照。执行器检查存在的运行标识字段、校验数值进度，将运行期间的数据库进度限制在 `0..99`，再通过 `task_id + current_run_id + status='running'` 条件更新；损坏或不匹配的快照会被忽略。
4. `GET /api/pre-defense/logs/{taskId}` 由 `PreDefenseLogQueryService` 返回日志增量和当前进度快照。前端任务详情每 3 秒轮询；超过 60 秒没有心跳时显示“较久未更新”，但不改变任务状态，也不认定任务失败。
5. Java 等待适配器进程退出后读取结果文件，并校验 `stage`、`run_id`、`attempt`、`status` 和 `artifacts` 等字段，再条件写入结果和终态。若进程没有生成合法结果，Java 生成平台级失败原因；若运行令牌已过期，条件更新影响行数为 0，旧结果被忽略。`progress.json` 只提供观测信息，不能替代结果协议。

不要把 Python 的退出码单独等同于任务最终状态：适配器捕获到算法错误时，会尽量先写入 `status: failed`、`error_code` 和 `failure_reason`，再以非零退出码退出。Java 在确认结果文件存在后仍会校验 JSON 协议；合法的失败结果会被保存为任务失败，缺失或不合法的结果则由 Java 转成平台级失败信息。

**运行目录和日志：**

```text
<workspace>/pre_defense-81/attempt-1/
  request.json       Java -> Python 的结构化请求
  result.json        Python -> Java 的结构化响应
  progress.json      Python -> Java/前端的训练进度观测快照
  stdout.log         适配器标准输出，包含逐行转发的 LDD 子进程输出
  stderr.log         适配器自身标准错误
  artifacts/
    execution.log    LDD 子进程 stdout/stderr 合并后的逐行日志
    selected_indices_*.npy
    图片、模型和其他算法产物
```

适配器对算法日志采用“双写”路径：每行先写到产物目录的 `execution.log`，再 `print(..., flush=True)` 到自身 stdout，Java 的 `ProcessBuilder.redirectOutput()` 将后者写入 `stdout.log`。算法子进程 stderr 已通过 `stderr=subprocess.STDOUT` 合并，所以正常 LDD 错误输出也会进入这两份日志；`stderr.log` 主要用于适配器进程自身的错误。任务运行中日志接口读 attempt 目录的 stdout/stderr；任务完成后按结果中的 `artifacts.logs` 读取。成功结果通常登记 `execution.log` 和解析出的文本日志，Java 生成的平台级失败结果登记 stdout/stderr。当前适配器自行生成的失败结果 `artifacts` 默认为空，结束后的日志接口可能因此没有可读日志；文件仍在 attempt 目录时，需要直接从工作目录排障。这是当前日志登记的边界，不能承诺所有失败日志都会显示在页面。

可以用这句话收束：**Java 写请求文件并启动 Python 适配器；适配器读请求、取得 VaB 文件锁、启动 LDD 子进程并流式双写日志，随后写结果文件并退出；Java 等适配器进程结束，读结果、校验运行令牌并更新 MySQL。**

## 6. 白板讲解图

```text
                    ┌───────────────┐
                    │ Vue 任务中心   │
                    └───────┬───────┘
                            │ create/start/status/report
                    ┌───────▼───────┐
                    │ Task Service   │
                    │ 校验/状态/审计  │
                    └───┬────────┬──┘
                        │        │
       MySQL 条件领取/快照│        │ afterCommit
                        ▼        ▼
                 ┌──────────┐  ┌──────────────────┐
                 │ MySQL    │  │ 有界线程池         │
                 └──────────┘  └────────┬─────────┘
                                        ▼
                            ┌──────────────────────┐
                            │ Java Python Executor │
                            └──────────┬───────────┘
                            │ request.json / result.json
                                       ▼
                            ┌──────────────────────┐
                            │ Python VaB Adapter   │
                            └──────────┬───────────┘
                                       ▼
                                  LDD.py
                                       │
                                       ▼
                            指标 / 索引 / 日志 / 模型
                                       │
                              报告与 CIFAR-10 导出

Python 适配器/训练脚本 ──原子写 progress.json──> Java PythonProcessWaiter
Java 等待器 ──按 run_id 条件更新百分比──> MySQL
浏览器 ──每 3 秒请求日志接口──> Java 返回日志增量 + progress 快照
```

讲图时先从上到下说主流程，再讲 MySQL 条件更新和文件产物两个横切面。

## 7. 高频问题与建议回答

### 为什么不用 HTTP 请求线程直接跑 LDD？

算法耗时和资源占用不可预测。同步执行会长期占用 Web 请求线程，增加请求超时风险，也难以管理取消、重试和执行现场。平台把一次运行变成任务，在提交后由有界线程池启动 Python 进程，HTTP 层只负责接受命令和查询状态。

### Java 是怎么把 JSON 发送给 Python 的？Python 又怎么回调 Java？

Java 不是把 JSON 放进 HTTP body，也不是写到 Python 标准输入。`PythonLddTaskExecutor` 先用 Jackson 序列化 `DefenseTaskRequest`，写到本次 attempt 目录中的 `request.json`；然后用 `ProcessBuilder` 启动适配器，并通过 `--request`、`--result` 和 `--progress-file` 参数把文件路径交给它。Python 的 `argparse` 读取命令行参数，`json.loads(Path(request).read_text(...))` 解析请求。适配器用 `subprocess.Popen()` 启动 AST 生成的 LDD 子进程，把子进程 stderr 合并到 stdout，逐行读取并双写到 `execution.log` 与适配器 stdout；Java 已将适配器 stdout/stderr 分别重定向到 `stdout.log` 和 `stderr.log`。算法完成后，适配器把结果 JSON 写到 `result.json` 并退出。Java 等待的是外层适配器进程，结束后读取结果文件、校验运行令牌和协议字段，最后自己更新 MySQL。**没有 Python 到 Java 的 HTTP 回调。**

### 为什么任务目录里有两份算法日志？`stderr.log` 为什么可能为空？

`execution.log` 是适配器从 LDD 子进程的 stdout 管道逐行读到的内容，同时包含已合并的子进程 stderr。为了让 Java 执行目录也能直接看到实时输出，适配器把相同行再打印到自己的 stdout，所以它也会出现在 `stdout.log`。`stderr.log` 接收的是适配器自身 stderr，正常训练时可以为空；不能把它解释成 LDD 子进程的独立错误日志。运行中接口读取 stdout/stderr，结束后则依据 `result.json` 的日志产物列表读取。注意，适配器自己写出的失败结果目前没有登记日志路径，结束后的接口可能读不到该次日志；Java 兜底生成的失败结果会登记 stdout/stderr 路径。

### VaB 文件锁如何工作？强制取消后有什么边界？

适配器通过 `os.open(path, O_CREAT | O_EXCL | O_WRONLY)` 原子尝试创建 `VAB_ROOT/.security-model-vab.lock`，并写入适配器 PID；已存在就快速失败，不排队。正常执行会在 `finally` 删除锁。但 Java 超时或取消时若强制终止适配器，Python 清理逻辑可能没有运行机会，留下残余锁。它只是共享 VaB 路径上的文件系统互斥标记，不是 Redis/数据库分布式锁。遇到锁冲突时先确认没有任务仍在使用 VaB，再人工核查和清理残留文件；不能仅凭 PID 或文件年龄自动删除。

### 为什么 Java 与 Python 用文件 JSON，而不是直接把算法改写成 Java？

核心算法依赖 PyTorch 和现有 VaB 代码，重写会改变研究代码、增加验证成本。文件 JSON 是简单且可检查的进程边界：请求、结果和日志都能留档，Python 环境也可独立管理。代价是本地路径共享、文件清理和协议版本需要治理；若未来多机部署，接口应演进为对象存储或 RPC/任务服务，而不是继续依赖共享磁盘。

### `run_id` 和 `attempt` 有什么区别？

`attempt` 是业务上的第几次尝试，便于报告展示重试历史；`run_id` 是一次运行的唯一令牌，用于识别回调属于哪次具体执行。任务 ID 不够，因为同一任务重试后 ID 不变。Mapper 要求回调的 `run_id` 等于当前运行标识，因此旧运行的迟到写入更新不到新尝试。

### 为什么要在事务提交后启动进程？

启动 Python 是不可回滚的外部副作用。如果在事务提交前启动，数据库随后回滚时就会出现“进程在跑、任务记录不存在或状态仍是 pending”。`AfterCommitTaskDispatcher` 将派发延迟到事务成功提交后。

### 条件更新如何避免两个请求重复启动？

两个请求可能同时读到 `pending`，所以应用层读状态并不足够。最终由数据库执行 `UPDATE ... WHERE task_id=? AND status='pending'`；只有一个请求能把状态从 pending 改成 running，另一个请求更新行数为 0 并返回状态冲突。

### 失败重试如何避免覆盖原始运行？

失败任务先通过条件更新回到 pending，再生成新 `run_id` 和递增 `attempt`。新尝试使用新的目录；历史快照保存在 `execution_snapshot.attempts`，旧目录也不会被新执行路径复用。任务结果列记录当前结果，不能把它说成每一次完整结果都永久存放在单一 JSON 字段中。

### 应用重启后怎么处理？

Python 进程不能随 Java 应用状态一起恢复。迁移版启动时扫描仍为 running 的训练任务，用条件更新把它们写成 `EXECUTOR_RESTARTED` 失败，让用户明确重试；不是自动续跑。当前迁移版没有导出任务启动恢复 Runner，遗留导出任务不会自动重新派发或收敛。

### 任务取消是否一定杀掉整棵 Python 子进程树？

当前 Java 执行器记住运行标识对应的 Python `Process`，取消或超时时会枚举当时可见的后代进程并尝试 `destroyForcibly()`，再终止适配器。应说明这是对已发现进程的尽力回收；进程派生竞态、操作系统差异仍可能导致遗留进程，当前也没有容器级隔离或资源配额。生产化可引入进程组/Job Object、容器或独立 worker，并做故障演练。

### 训练期间的进度是如何从 Python 显示到页面的？

它不是 Python 主动回调 Java，而是一条基于共享工作目录的旁路。适配器收到 `--progress-file` 后，准备阶段调用 `write_progress()`；AST 注入到 LDD 训练脚本的 `platform_report_epoch()` 在每个 epoch 生成阶段、轮次和百分比。两个写入点都会更新 `heartbeat_at`，先写同目录临时文件，再原子替换 `progress.json`。

Java 的 `PythonProcessWaiter` 等待外层适配器进程时关注该文件的修改时间和大小，发现变化就调用 `PythonLddTaskExecutor.updateProgressFromSnapshot()`。执行器解析 JSON，对存在的 `run_id`、`attempt` 做本次运行匹配，验证百分比后夹到 `0..99`，再由 Mapper 通过任务 ID、当前运行令牌及 `running` 状态更新 MySQL。进度文件暂时不可读或 JSON 无效时跳过该次进度，不中断算法。

浏览器侧由任务详情每 3 秒调用 `/api/pre-defense/logs/{taskId}`。`PreDefenseLogQueryService` 在同一响应中返回带 byte offset 的日志增量和 `progress` 对象，前端据此展示阶段标签、epoch、百分比、提示消息和心跳。心跳超过 60 秒只触发页面提示；任务最终状态仍以 Java 读取并校验 `result.json` 后写入数据库为准。进度相关代码可从 `algorithm_entry.py`、`PythonProcessWaiter.java`、`PythonLddTaskExecutor.java`、`PreDefenseLogQueryService.java` 和 `TaskCenterView.vue` 顺序阅读。

### 配额是全局并发控制吗？

不是。默认总运行数和单用户运行数是在单个 JVM 实例中按数据库当前 running 记录检查，并将检查结果写入快照；策略的同步也只是 JVM 内同步。数据库条件更新保护单任务只能被一个请求领取，但多实例间的全局配额需要分布式租约、数据库原子配额或集中 worker 调度。

### 保存了哪些可复现信息？SHA-256 是否覆盖整个数据集？

快照记录请求配置、资源 ID/路径/真实路径/修改时间等元数据、Java 运行环境和配额。SHA-256 只在内容指纹开启且目标是大小未超限的普通文件时计算；目录不做递归全量哈希。Python 结果额外记录实际 Python、PyTorch、CUDA 和 GPU 设备等。它提升运行可解释性，但还不是完整的可复现容器镜像。

### 为什么导出清洗子集，而不是只给用户索引文件？

索引需要和原始数据集对应才能用于后续流程。导出器校验索引后读取 CIFAR-10 原始训练 batch，按索引重建样本图片和标签，并把索引、CSV 和元数据打包。导出语义是“由 LDD 保留索引选择的原始 CIFAR-10 训练子集”，不是 VaB 内部的中毒修改张量。

### `clean_purity` 能代表生产环境检测准确率吗？

不能。该指标依赖实验中已知的中毒样本标签/索引，用于受控实验评估。未知生产数据没有这些 oracle 标签时，不能直接计算相同定义的 purity；应和攻击成功率、干净准确率等正式实验指标分开设计。

### 为什么使用 AST 转换 LDD 脚本？

原始研究脚本有固定循环、共享输出目录和交互式绘图，直接导入会触发不适合平台任务的执行流程。适配器生成一个任务级脚本副本，将数据集、触发器和参数替换为当前配置，并重定向输出，同时保留原始 `LDD.py`。这是针对当前脚本结构的适配，不是任意算法的一键接入能力。

### Redis 在项目中承担什么任务？

迁移版接入了 Redis，但范围很窄：`ResourceCatalogCache` 用 5 分钟 TTL 缓存部分数据集、算法、超参数目录查询，以及数据集和算法按 ID 查询。缓存值是 JSON 字符串，MySQL 仍是事实源。缓存未命中、空值、反序列化失败或 Redis 异常会作为 miss 处理，由查询服务回源 MySQL；写缓存或删缓存失败也不会阻断资源业务。数据集和算法变更会在事务提交后失效相关列表键和 ID 键。Redis 不存任务状态，不是任务队列，也没有承担分布式锁或 GPU 调度。

### 如何证明项目经过测试？

截至 2026-11-13（R1 及评审三轮修正、R2 第三切片），`mvn -B -f migration-backend/pom.xml verify -Pmysql-it` 实测迁移版 16 个测试类、76 项单元测试 + 29 项 MySQL 集成测试通过，失败/错误/跳过均为 0，jar 打包成功；适配器 `python -m unittest` 11 项通过。单测覆盖资源命令/查询、缓存 TTL 与故障降级、事务提交后派发、任务重试快照、进度等待与查询、启动恢复、YAML 配置解析，以及 R1 新增的任务归属守卫（8 项，含 demo 写拒绝）、按 owner 配额（4 项）、资源管理员权限与数据集验证（4 项）、进度查询越权与统计 scope（3 项）、真实登录取证后的双账号 HTTP 契约（8 项，含禁用账号与令牌 `adm` 不被信任）、R2 的 GPU 租约语义（4 项）、适配器失败码兜底（7 项）、attempt 产物截断/清单/保留期（7 项）。集成测试 29 项分五个类：`MySqlTaskPersistenceIT` 5 项（并发领取原子性、完成与恢复的条件更新竞争、孤儿恢复边界、owner 范围查询、demo 排除）、`OwnershipMigrationScriptIT` 6 项（决策表迁移）、`OwnershipEndToEndIT` 9 项（真实库双账号 + 导出详情/下载/PNG 预览/审计/导出提交鉴权）、`OwnershipEndToEndCrossReadIT` 4 项（管理员开关 false/true）、`GpuLeaseMySqlIT` 5 项（GPU 单槽租约并发唯一、过期接管、fencing、按 run 释放），均在本机 MySQL 实跑通过。命令为 `mvn -B -f migration-backend/pom.xml test` 和 `mvn -B -f migration-backend/pom.xml verify -Pmysql-it`（`mysql-it` 需 `MYSQL_IT_PASSWORD`）。**生产库 `eladmin` 迁移仍未执行，`sys_gpu_lease` 未在生产库建表，也未跑真实 LDD**；GitHub Actions 配置了 MySQL 8.4 service，远端结果仍以 Actions 页面为准。旧 ELADMIN 的测试不计入迁移版覆盖；LDD 工程链路验证也不等于论文正式实验。

## 8. 代码追问导航

| 追问主题 | 建议现场打开 |
| --- | --- |
| 如何验证任务、配置和算法路径 | [`PreDefenseTaskCommandService.java`](../migration-backend/src/main/java/com/securitymodel/application/task/PreDefenseTaskCommandService.java)、[`LddConfigurationPolicy.java`](../migration-backend/src/main/java/com/securitymodel/application/task/LddConfigurationPolicy.java) |
| 如何实现原子领取和迟到回调隔离 | [`PreDefenseTaskMapper.java`](../migration-backend/src/main/java/com/securitymodel/infrastructure/persistence/mapper/PreDefenseTaskMapper.java) |
| 如何做异步调度、队列拒绝和超时 | [`TaskExecutorConfig.java`](../migration-backend/src/main/java/com/securitymodel/infrastructure/executor/TaskExecutorConfig.java)、[`AfterCommitTaskDispatcher.java`](../migration-backend/src/main/java/com/securitymodel/application/task/AfterCommitTaskDispatcher.java)、[`PythonProcessWaiter.java`](../migration-backend/src/main/java/com/securitymodel/application/task/PythonProcessWaiter.java) |
| Java/Python 如何解耦 | [`DefenseTaskRequest.java`](../migration-backend/src/main/java/com/securitymodel/application/task/DefenseTaskRequest.java)、[`PythonLddTaskExecutor.java`](../migration-backend/src/main/java/com/securitymodel/infrastructure/executor/PythonLddTaskExecutor.java)、[`algorithm_entry.py`](../algorithm-runtime/vab/algorithm_entry.py) |
| 如何看重启恢复与边界 | [`TaskRecoveryRunner.java`](../migration-backend/src/main/java/com/securitymodel/application/task/TaskRecoveryRunner.java) |
| 如何导出清洗子集 | [`CleanSubsetExportService.java`](../migration-backend/src/main/java/com/securitymodel/application/export/CleanSubsetExportService.java)、[`CleanSubsetExportExecutor.java`](../migration-backend/src/main/java/com/securitymodel/application/export/CleanSubsetExportExecutor.java) |
| 如何验证前端数据是真实还是演示 | [`TaskCenterView.vue`](../migration-frontend/src/views/TaskCenterView.vue)、[`demoData.js`](../migration-frontend/src/data/demoData.js) |

## 9. 面试前建议准备的现场证据

1. 一条真实 LDD 成功任务的 task ID、执行时间、配置和报告页面。
2. 对应 attempt 目录的 `request.json`、`result.json`、stdout/stderr 日志和 `selected_indices_*.npy`。
3. 按某个隔离比例导出的 ZIP，能解释其中的索引、标签和图片文件。
4. 当前本机 VaB 路径、Python 环境、PyTorch/CUDA 和 GPU 信息。
5. 一个失败或取消/重试案例，说明状态如何变化、`run_id` 如何变化、旧回调如何被隔离。
6. 构建和测试命令输出，以及 Surefire/Failsafe 报告。明确迁移版实测 69 项单元测试 + 29 项 MySQL 集成测试（2026-11-13，本机环境变量提供密码），以及旧模块历史定向测试的区别；生产库迁移与 GPU 租约建表尚未执行，也未跑真实 LDD，不能拿隔离库结果冒充生产验证。

不要把 `migration-frontend/src/data/demoData.js` 中的任务数字、统计百分比、模拟案例或演示模式页面作为真实实验数据。也不要把页面出现“已接入运行”的静态标签当作后端算法接入证据。

## 10. 能力边界与后续演进回答

如果被问“为什么还没有三阶段真实算法”，可以这样回答：

> 当前优先把真实 LDD 执行闭环做扎实，先验证统一任务协议、参数校验、异步生命周期、进程隔离和实验追溯。三阶段页面是能力目录和展示层，不等于对应算法已经接入。新增算法还要落实输入输出契约、参数 schema、结果指标映射、产物安全策略和运行测试。我会按相同执行器边界扩展，避免为了页面完整而把未验证方案标成真实能力。

如果被问下一步工程化，可以按优先级回答：

1. 在现有迁移版测试套件上补充端到端执行协议、结果协议、取消/超时进程回收竞态和故障注入覆盖。
2. 为导出任务增加明确的启动恢复扫描与故障注入演练。
3. 将线程池和本地进程演进为持久化队列/独立 worker，增加多实例配额、进程树或容器资源隔离。
4. 固定 Python 依赖锁文件和算法/数据版本摘要，强化实验复现。
5. 在完成算法适配和正式实验后，再接入训练中或训练后算法，并区分工程指标和论文指标。

这些是后续路线，不要在面试中说成已经落地。

## 11. 高频术语卡片

| 术语 | 面试时的准确解释 |
| --- | --- |
| `taskId` | 业务任务标识，可包含多次运行尝试 |
| `attempt` | 同一个业务任务的第几次执行 |
| `run_id` | 某一次具体执行的唯一令牌，用于隔离旧回调 |
| `execution_snapshot` | 每次启动输入、配置、资源和运行时计划信息的追加记录 |
| `cleaning_result` | 当前任务结果的结构化 JSON，包含状态、指标、运行环境和产物引用 |
| `retained_clean_subset` | LDD 产出的保留样本索引语义 |
| `afterCommit` | 数据库事务提交成功后才执行 Python 派发 |
| 原子领取 | 用带状态条件的单条 UPDATE 决定哪个请求取得执行权 |
| 工程验证 | 验证代码集成与执行链路，不等价于正式论文实验 |

## 12. 最后检查清单

- 能在 60 秒内说清项目定位、我的工作和真实接入范围。
- 能不看文档连续复述“前端 HTTP -> Java 写 request.json -> ProcessBuilder 启动适配器 -> Python 解析并运行 LDD -> Python 写 result.json -> Java 校验并条件更新 MySQL”。
- 能说明 `progress.json` 如何由 Python 原子写出、由 Java 等待器观察、由日志接口返回给前端，并讲清它不是最终结果协议。
- 能画出从前端到数据库、线程池、Python 适配器、LDD 和导出的数据流。
- 能解释 `run_id`、`attempt`、条件更新和事务提交后派发的必要性。
- 能明确说明任务重启处理是“标记失败后人工重试”，而不是自动恢复运行。
- 能说明 SHA-256 的文件大小条件和目录不做全量摘要。
- 能区分 LDD 保留索引、清洗子集 ZIP 与算法内部中间张量。
- 能把演示数据、工程验证和正式实验分开陈述。
- 能准确说出迁移版实测 48 项单测（2026-11-13）、MySQL 集成测试 5 项场景中仅 3 项有执行记录，并说明面试时以最新报告为准。
- 能指出三阶段展示不代表多算法真实执行，也能给出合理扩展路径。
