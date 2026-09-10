# 框架迁移准备清单

更新时间：2026-09-10

## 1. 目标

当前平台继续使用旧版 ELADMIN 基座完成 LDD 演示和业务验证。本文档冻结迁移边界，
为后续 Spring Boot 3、Java 17、Vue 3、Vite、Element Plus 和 Pinia 迁移提供依据。
迁移工作的目标是替换技术基座，不改变已经验证的 LDD 业务流程。

## 2. 已冻结的业务协议

### 2.1 任务状态

```text
pending -> running -> success
                    -> failed -> pending（受控重试）
                    -> canceled
```

任务执行必须携带 `run_id` 和 `attempt`。运行中的进度、结果和结束回调只能更新当前
运行令牌对应的任务。

### 2.2 统一请求

Python 执行器接收 `DefenseTaskRequest`，至少包含：

```text
taskId, attempt, runId, taskName, stage, createdBy,
customParams, dataset, algorithm, hyperParameter
```

Java 侧负责参数合并、资源校验、配额准入和快照，Python 侧负责算法计算和结果产物生成。

### 2.3 统一结果

结果文件使用 `result.json`，至少包含：

```json
{
  "executor": "python-process",
  "stage": "pre_defense",
  "status": "success",
  "message": "任务执行完成",
  "run_id": "uuid",
  "attempt": 1,
  "started_at": "ISO-8601",
  "finished_at": "ISO-8601",
  "artifacts": {},
  "metrics": {}
}
```

失败结果必须包含 `error_code` 和 `failure_reason`。当前 LDD 使用的主要错误码包括：
`TASK_TIMEOUT`、`TASK_CANCELED`、`PYTHON_PROCESS_EXITED`、`RESULT_MISSING`、
`RESULT_INVALID_JSON`、`RESULT_PROTOCOL_ERROR` 和 `TASK_QUEUE_FULL`。

## 3. 迁移前必须保持的接口

| 领域 | 当前接口范围 | 迁移要求 |
| --- | --- | --- |
| 资源 | 数据集、算法、超参数模板、模型 CRUD | 保持字段语义和权限校验 |
| 任务 | LDD 任务创建、启动、取消、重试、详情 | 保持状态和错误码 |
| 进度 | 任务列表、批量进度、执行池状态 | 保持 LDD 任务类型白名单 |
| 报告 | 参数快照、运行环境、指标、产物 | 保持快照 JSON 可读 |
| 导出 | 清洗子集提交、进度、重试、下载 | 保持按保留索引重建数据集 |

迁移期间不增加 VS、训练中防御或训练后防御功能，避免迁移同时扩大业务范围。

## 4. 后端迁移步骤

1. 使用 Java 17 编译基线建立独立迁移分支。
2. 将 `javax.*` 迁移为 `jakarta.*`，处理 Servlet、Validation 和 JPA 相关依赖。
3. 升级 Spring Security 配置，保持 JWT 登录、菜单权限和接口权限行为不变。
4. 升级 MyBatis-Plus、Druid、Redisson、Knife4j 等 Spring Boot 3 兼容版本。
5. 优先迁移安全训练领域包和 DTO，再迁移旧 ELADMIN 管理模块。
6. 保留数据库表和字段，先做兼容映射，不在框架迁移期间改动实验数据结构。
7. 执行登录、权限、LDD 任务、失败重试、报告和导出回归。

## 5. 前端迁移步骤

1. 新建 Vue 3 + Vite 前端目录，旧 Vue 2 页面作为回归基线。
2. 将 Axios API 调用、任务状态枚举和结果格式抽到独立模块。
3. 用 Pinia 替换 Vuex，仅迁移安全训练领域状态。
4. 用 Element Plus 替换 Element UI，优先迁移任务列表、详情、报告和导出页面。
5. 用 Vue Router 4 保持登录、菜单权限和页面路径语义。
6. 完成桌面端和窄屏端任务中心、报告页和日志区域回归后，再切换默认前端。

## 6. 当前不做的迁移工作

- 不在当前分支直接升级 Spring Boot 主版本。
- 不同时改数据库表结构和任务协议。
- 不在迁移期间接入新的防御算法。
- 不把本地单机配额描述成分布式 CPU、内存或 GPU 调度。

## 7. 验收标准

- LDD 任务状态、重试、取消和迟到回调保护行为不变。
- 参数快照仍能展示资源指纹、运行环境和配额快照。
- Python 结果协议与报告页面兼容。
- 清洗子集导出仍按索引重建并支持失败重试。
- 新旧前端可使用同一组后端接口完成答辩演示流程。
