# 目标架构设计

## 分层结构

```text
Web UI
  -> REST API
    -> Application Service
      -> Domain Service
        -> Repository / Mapper
        -> Algorithm Execution Adapter
          -> Python Algorithm Runtime / External Training Service
```

## 目标技术栈

目标系统采用成熟、稳定、社区活跃的主流技术栈，避免继续深度绑定旧版 ELADMIN 技术基座。

### 后端

- Java：Java 17 LTS。
- 框架：Spring Boot 3.x。
- 安全：Spring Security 6 + JWT。
- ORM：MyBatis-Plus 3.5.x，适配 Spring Boot 3 与 Jakarta 命名空间。
- 数据库：MySQL 8.x。
- 缓存与任务状态：Redis 7.x。
- API 文档：Knife4j / OpenAPI 3，优先选择 Spring Boot 3 兼容版本。
- 构建：Maven 3.8+。

### 前端

- 框架：Vue 3。
- 构建工具：Vite。
- UI 组件：Element Plus。
- 状态管理：Pinia。
- 路由：Vue Router 4。
- 请求：Axios。
- 图表：ECharts 5。

### 算法运行时

- Python 3.10+。
- 深度学习框架：PyTorch 2.x。
- 算法以本地进程、任务队列或独立服务方式接入，平台通过执行器接口调用。

### 迁移原则

当前项目仍是 Spring Boot 2.7 + Vue 2。为减少返工，迁移顺序为：先裁剪无关模块并稳定安全训练领域模型，再迁移后端到 Spring Boot 3，最后迁移前端到 Vue 3 + Vite。禁止在领域边界未稳定前同时推进大规模框架升级和业务重构。

## 目标领域包

后续建议从 `me.zhengjie.modules.system` 中迁移出安全训练领域：

```text
me.zhengjie.modules.securitymodel
  dataset
  algorithm
  model
  task
  execution
  report
```

第一阶段不立即搬包，避免影响大量 Mapper、Controller、前端接口和 SQL。先在 SDD 中冻结目标边界，后续按模块迁移。

## 核心领域模型

### Dataset

表示训练数据集，短期保留现有字段，后续补充：

- `modality`：固定优先支持 `image`。
- `storageUri`：统一替代分散的 `filePath`。
- `labelSchema`：类别映射。
- `poisonProfile`：中毒率、攻击类型等实验信息，可选。

### Algorithm

表示可调用算法插件，当前字段基本可复用：

- `defenseStage`：`pre`、`during`、`post`。
- `algorithmType`：建议收敛为 `poison_detection`、`clean_subset_selection`、`secure_training`、`model_assessment`。
- `implementationPath`：算法入口路径。
- `configSchema`：前端动态参数表单和后端参数校验共同依据。

### DefenseTask

建议后续抽象统一任务模型：

- `taskId`
- `taskName`
- `taskStage`
- `status`
- `progress`
- `datasetId`
- `inputModelId`
- `algorithmId`
- `config`
- `startTime`
- `endTime`
- `result`
- `errorMessage`

短期继续使用 `PreDefense`、`DuringDefense`、`PostDefense`，但服务层应逐步收敛到统一任务生命周期。

### ExecutionRecord

记录算法执行过程：

- 输入数据快照。
- 参数快照。
- 执行日志。
- 中间指标。
- 输出 artifact 路径。
- 失败原因。

### DefenseReport

生成论文系统需要的可导出报告：

- 数据集信息。
- 算法与参数。
- 中毒检测结果。
- 干净子集统计。
- 训练曲线。
- 模型评估指标。
- 结论与风险提示。

## 算法执行边界

平台不直接把算法逻辑写进 Controller 或 CRUD Service。算法统一通过执行器接口调用：

```text
AlgorithmExecutor
  supports(stage, algorithmType)
  validate(input, config)
  start(task)
  query(task)
  cancel(task)
```

第一版执行器可以先使用本地 Python 进程或脚本入口，后续再替换为独立算法服务。

## 任务生命周期

```text
draft/pending -> queued -> running -> success
                         -> failed
                         -> canceled
```

当前已实现 `pending/running/success/failed/canceled`。当前 MVP 不单独引入队列中的
`queued` 状态；启动请求通过数据库条件更新原子领取任务，取消请求先持久化
`canceled`，事务提交后再回收外部 Python 进程，避免数据库状态与运行时进程不一致。

## 关键接口方向

- `POST /api/datasets/upload`
- `POST /api/pre-defense/{id}/start`
- `GET /api/tasks`
- `GET /api/tasks/{stage}/{id}`
- `GET /api/reports/{taskId}`
- `POST /api/executors/callback`

现有接口可保留，新增统一接口时再逐步兼容迁移。
