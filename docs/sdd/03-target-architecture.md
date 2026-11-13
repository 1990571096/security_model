# 目标架构设计

更新时间：2026-11-13（R0 事实校准）。本文分两栏：**当前实现**（代码可验证）与**目标设计**（尚未实现）。未标注“已实现”的内容都是规划。

## 1. 当前运行架构（已实现）

```text
浏览器（Vue 3 / Vite / Element Plus）
   │  Bearer JWT（subject=username，authority=ROLE_USER）
   ▼
Spring Boot 3.5（migration-backend）
   ├─ JwtAuthenticationFilter → SecurityConfig（全部请求需认证，5 个白名单路径除外）
   ├─ interfaces.web   控制器：/auth、/api/dataset、/api/algorithm、/api/hyper、
   │                   /api/pre-defense、/api/task-progress、/api/audit、/api/v1/system
   ├─ application       任务命令与查询、资源命令与查询、报告、日志、导出、审计
   ├─ domain            任务/资源/导出/审计/用户实体（MyBatis-Plus 映射既有表）
   └─ infrastructure    executor（Python 进程）、cache（Redis）、security、persistence.mapper
        │                        │
        │ JDBC                   └─ Redis（仅资源目录缓存，MySQL 回源）
        ▼
      MySQL（任务、资源、导出、审计的事实源）
        │
        │ ProcessBuilder（工作目录 = 执行 workspace）
        ▼
   Python 适配器 algorithm-runtime/vab/algorithm_entry.py
        ├─ AST 生成单任务脚本（不改原 VaB 源码）
        ├─ 子进程运行生成脚本 → 外部 VaB LDD（要求 CUDA）
        └─ 写 request/result/progress/log/产物到 attempt 目录
```

关键边界（已实现的事实）：

- **进程模型**：算法在 API 进程的子进程中运行，线程池 `taskAsync` 有界；没有独立 Worker，也没有消息队列。
- **完成语义**：`result.json` 是唯一完成依据；`progress.json` 只是诊断快照。
- **幂等与竞争**：任务领取是 `pending → running` 的条件更新；进度/结果/结束回调都要求 `status='running'` 且 `current_run_id` 相同。
- **启动恢复**：`ApplicationReadyEvent` 时把所有 `running` 收敛为 `failed(EXECUTOR_RESTARTED)`，隐含“单实例”前提；导出任务没有启动恢复。
- **归属**：R1 已实现。`owner_user_id` 是唯一授权键，`TaskAccessGuard` 在任务命令、报告、日志、产物、导出、下载、审计入口统一校验；`create_by` 仅作展示与审计快照。跨用户读默认关闭，demo 行任何人不可写。
- **令牌**：JWT 只证明登录主体；`enabled`/`is_admin` 每个请求回查 `sys_user`，禁用或降权立即生效（见 ADR-013 决策 9）。

## 2. 目标运行架构（规划）

在不改变技术栈、不引入 MQ 的前提下，R1–R3 只做三件事：

```text
请求 → [认证] → [归属/角色授权 (R1)] → [业务服务] → [MySQL 条件更新]
                                        └→ [GPU 单槽租约 (R2)] → [有界执行器] → Python
请求 → [持久调度意图表 + 扫描 (R3)] → 领取 → 执行
```

- R1（**已完成，2026-11-13**）：任务/报告/日志/产物/导出按 `owner_user_id` 授权；共享资源收敛为管理员维护；数据集增加管理员验证入口。
- R2：配额与领取同事务；GPU 单槽租约；导出启动恢复；日志与产物限额。
- R3：`pending` 之前增加数据库调度意图，关闭“提交成功但派发前崩溃”的窗口。
- R4/R8（可选）：Outbox + RabbitMQ + 独立 Worker / 多实例，只在有真实跨进程测试条件时启用。

## 3. 后端包结构（当前真实结构）

```text
com.securitymodel
├── interfaces.web                 控制器（无业务逻辑）
├── application
│   ├── task      命令服务、查询、配额、进程等待、恢复、指纹、TaskAccessGuard
│   ├── resource  资源命令与查询（写操作仅管理员）
│   ├── report    报告与日志查询
│   ├── export    清洗子集导出服务与执行器
│   └── audit     AuthenticatedUser、当前用户、操作审计、AdminRequiredException
├── domain        task / dataset / algorithm / hyper / export / audit / user
└── infrastructure
    ├── executor  DefenseTaskExecutor、PythonLddTaskExecutor、线程池配置
    ├── security  SecurityConfig、JwtAuthenticationFilter、JwtTokenService
    ├── cache     ResourceCatalogCache
    └── persistence.mapper  MyBatis-Plus Mapper
```

历史文档中“从 `me.zhengjie.modules.system` 迁出目标包”的措辞属于**旧工程**，迁移版已完成，不再作为待办。

## 4. 领域模型（当前映射）

| 概念 | 承载表/类 | 关键字段 |
| --- | --- | --- |
| 数据集 | `sys_dataset` / `Dataset` | `file_path`（本机绝对路径）、`parse_status`、`enabled` |
| 算法 | `sys_algorithm` / `Algorithm` | `defense_stage`、`implementation_path`、`default_config`、`config_schema` |
| 超参数模板 | `sys_hyper` / `Hyper` | `parameter_config`、`parameter_schema`、`is_default` |
| 训练前任务 | `sys_pre_defense` / `PreDefenseTask` | `status`、`progress`、`current_run_id`、`current_attempt`、`execution_snapshot`、`cleaning_result`、`owner_user_id`、`data_origin`、`owner_backfill_status` |
| 导出任务 | `sys_pre_defense_export` / `CleanSubsetExportTask` | `(task_id, isolation_ratio)` 唯一、`status`、`file_path` |
| 审计 | `security_operation_audit` / `OperationAudit` | `action`、`operator`、前后状态、请求快照 |

统一 `DefenseTask` 抽象、独立 `ExecutionRecord`/`DefenseReport` 表仍是规划，不在 R1–R3 内。

## 5. 任务状态机（已实现的边）

```text
                 start(claim, 原子)                finish
    pending ───────────────────────► running ─────────────► success
       │                                │        └────────► failed
       │ update（仅 pending 可编辑）      ├── cancel ───────► canceled
       │                                │
       │                                ├── 启动恢复 ──────► failed (EXECUTOR_RESTARTED)
       │                                └── 派发拒绝 ──────► failed (TASK_QUEUE_FULL)
       │
       └── delete（running 禁止删除）

    failed ──retry(resetForRetry)──► pending ──launch──► running
```

不允许的边（代码会拒绝）：`success/canceled → pending`；`pending → running` 之外的启动；对 `running` 的删除；对 `pending` 的取消或重试；`failed` 的直接启动。

导出任务状态机（独立且更弱）：

```text
pending ──claim──► running ──► success
   │                  └──────► failed
   ├── 派发拒绝 ──────────────► failed (EXPORT_QUEUE_FULL)
   └─ failed ──resetForRetry──► pending ──dispatch──► （重新尝试）
```

缺口：重启后遗留的导出 `pending/running` 没有任何扫描收敛。

## 6. 接口清单（实际 vs 规划）

**已实现**（`migration-backend` 控制器）：

| 方法 | 路径 | 归属校验 |
| --- | --- | --- |
| GET | `/auth/code`、`/auth/info`、`DELETE /auth/logout` | 公开 / 认证 |
| POST | `/auth/login` | 公开 |
| GET/POST/PUT/DELETE | `/api/dataset`、`/api/dataset/{id}`、`/api/dataset/available` | 读=认证；写=管理员 |
| POST | `/api/dataset/{id}/validate` | 管理员（R1 新增） |
| GET/POST/PUT/DELETE | `/api/algorithm`、`/api/algorithm/{id}`、`/api/algorithm/available` | 读=认证；写=管理员 |
| GET | `/api/hyper/available`、`/api/hyper/{id}`、`/api/hyper/algorithm/{id}`、`.../default` | 仅认证 |
| GET/POST/PUT/DELETE | `/api/pre-defense/{id}`、`/api/pre-defense` | 读=owner；写=仅 owner |
| POST | `/api/pre-defense/start/{id}`、`retry/{id}`、`cancel/{id}` | 仅 owner |
| GET | `/api/pre-defense/report/{id}`、`logs/{id}`、`artifact-preview/{id}` | 仅 owner（管理员跨用户读开关开启时例外） |
| POST/GET | `/api/pre-defense/cleaned-dataset/exports/{taskId}`、`.../exports/{exportId}`、`.../exports/task/{taskId}`、`.../download` | 提交=owner；查询/下载=owner |
| GET | `/api/task-progress`、`/page`、`/running`、`/{type}/{id}`、`/statistics`、`/executor-status` | 按 owner 过滤；统计返回 `scope` |
| POST | `/api/task-progress/refresh` | 仅认证（返回静态文本） |
| GET | `/api/audit/task/{id}` | 仅 owner |
| GET | `/api/v1/system/version`、`/actuator/health` | 公开 |

**规划/不存在**（旧文档曾列出，需按此修正）：`POST /api/datasets/upload`、`GET /api/tasks`、`GET /api/tasks/{stage}/{id}`、`GET /api/reports/{taskId}`、`POST /api/executors/callback`、`GET /api/pre-defense/download-cleaned-dataset/{taskId}`、任何 `/api/model` 接口。

## 7. 信任边界

| 边界 | 信任规则 |
| --- | --- |
| 浏览器 → 后端 | 只信任 Bearer JWT；不信任任何由前端传入的“我是谁/我能看什么” |
| 后端 → Python | 只通过 `DefenseTaskRequest` 快照 + 命令行参数；不把数据库凭据传给算法进程 |
| Python → 后端 | `result.json` 视为不可信输入，必须校验 `executor/stage/run_id/attempt/status/artifacts` |
| 后端 → 文件系统 | 所有读取都做 `toRealPath().startsWith(root)`；根来自 `result.json.artifacts.path` |
| 后端 → 外部大模型（R6/R7） | 只发送按当前用户权限过滤、服务端脱敏、白名单字段（SDD 片段/参数说明/报告摘要）；禁止原始样本、完整日志、本机绝对路径、凭据、个人信息 |

## 8. 资源预算（单机）

| 资源 | 预算 | 影响 |
| --- | --- | --- |
| GPU | RTX 2060 6GB，单卡 | 同一时刻只应有一个 LDD 训练占用 CUDA；配额（4/用户 2）不代表 GPU 并发能力 |
| 内存 | 16GB | JVM + Python + CUDA 主机内存共存；并发训练存在 OOM 风险 |
| 磁盘 | 未实测 | 数据集约 170MB（CIFAR-10 Python 批次）；产物与检查点体积需 R2 实测后定配额 |

真实 LDD 工程验证配置固定为 CIFAR-10 / SIG / batch 128 / 5 epochs，本轮不改动、不重跑；论文正式实验参数另行确定。
