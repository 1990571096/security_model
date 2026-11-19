# 面向数据清洗的安全模型训练系统

本项目是一个**单机实验平台工程交付版**：把原有 Python 安全算法重构为可编排的实验平台。Java 侧通过任务快照、状态机、幂等控制和执行器协议隔离算法实现；MySQL 管理任务事实，Redis 缓存热点资源；Outbox/RabbitMQ 支持可选的独立 Worker；基于 SDD 知识库接入 RAG 和受控 Assistant，为参数校验、任务预检、结果分析提供辅助。真实任务执行始终受后端权限和状态机控制。

> **当前状态、测试数字和验收边界的唯一权威入口**：
> [`docs/sdd/26-current-delivery-facts.md`](docs/sdd/26-current-delivery-facts.md)。
> 本 README 只保留稳定的定位、结构、启动和学习入口，不维护“最新测试数字”。

## 交付口径

- 可以说：**单机实验平台工程交付版已完成相关工程验收**。
- 正式 5 epochs 验收只支持工程执行链路、配置快照、产物、GPU 租约、进程收尾和验收脚本闭环。
- 不可以说：论文效果验证完成、生产级多机部署完成、真实外部模型生产联调完成、生产库 `eladmin` 已迁移。
- 旧 ELADMIN 工程已归档到 Tag `migration-r4-verified` 与 `legacy` 分支；当前分支已删除 `eladmin/`、`eladmin-web/`，`sql/eladmin.sql` 仅作为基础 schema 保留，不是当前运行基线。

## 技术栈

| 层 | 当前实现 |
|---|---|
| 后端 | Java 17 编译目标、Spring Boot 3.5、Spring Security 6、JWT、MyBatis-Plus |
| 前端 | Vue 3、Vite 6、Element Plus、Pinia、Vue Router 4 |
| 事实数据 | MySQL 8.0/8.4；任务、状态机、快照、租约、Outbox 账本以数据库为准 |
| 缓存 | Redis 8.0；资源目录缓存、可选知识向量检索；异常时回源 MySQL |
| 派发 | 默认 `dispatch.mode=db`；可选 `rabbit`（Outbox + Relay + Worker + RabbitMQ 4.1） |
| 算法 | Python/PyTorch LDD；`algorithm-runtime/vab/algorithm_entry.py` 是仓库内适配器 |
| 测试 | Maven Surefire + Failsafe、Python unittest、Vite build |

### 运行版本基线

| 组件 | 基线 | 依据 |
|---|---|---|
| JDK | 17 | `pom.xml` 编译目标与 CI `java-version: 17` |
| Node.js | 20 | CI `node-version: 20`；Vite 6 当前构建实测通过 |
| Python | 3.11（测试/CI）；3.9（VaB 适配器说明的最低基线） | CI `python-version: 3.11`；`algorithm-runtime/vab/README.md` |
| MySQL | 8.0 / 8.4 | 本机文档记录 8.0；CI service 使用 `mysql:8.4` |
| Redis | 8.0 | CI service 使用 `redis:8.0` |
| RabbitMQ | 4.1 | CI service 使用 `rabbitmq:4.1`；单机 Rabbit 演练记录 4.1.8 |

## 项目结构

```text
.
├── docs/sdd/                  # SDD、验收清单、部署记录
├── docs/learning/             # 从架构到源码的学习路线
├── migration-backend/         # Spring Boot 3 后端
├── migration-frontend/        # Vue 3 / Vite 前端
├── algorithm-runtime/vab/     # Python LDD 适配器与测试
├── scripts/dev/               # 初始化、启停、前端静态托管、LDD 验收
├── scripts/r4/                # R4 Outbox/RabbitMQ 演练与短桩
├── sql/                       # 基础 schema、增量迁移与 MVP seed
└── screenshots/               # 已跟踪的历史界面截图，保留审计
```

默认端口：后端 `8100`；Vite 开发服务器 `8014`；静态托管脚本默认 `18220`（可用 `-StaticPort 8014` 覆盖）；`rabbit` 模式 Worker 默认 `8101`。

## 当前能力

- 数据集、算法、超参数模板登记与权限控制。
- LDD 训练前数据清洗任务的创建、编辑、删除、启动、重试、取消。
- 任务参数快照、`run_id` / `attempt` 隔离、数据库条件更新和请求幂等。
- 有界执行池、单机/单用户运行配额、MySQL 单槽 GPU 租约和隔离槽位。
- 失败恢复、调度意图、Outbox、RabbitMQ Worker、重复投递与死信重放。
- 结果报告、日志增量读取、产物 containment 校验和清洗子集 ZIP 异步导出。
- RAG 知识索引、ACL-first 检索、路径/凭据/联系方式脱敏和行号引用。
- Assistant 三场景：参数校验、任务预检、结果解释；写操作只生成预览，必须一次性确认。
- 运行监控、健康检查、前端生产静态托管和单机启停脚本。

## 安全初始化（不触碰生产库）

`DB_NAME` 默认已改为 `security_model`；初始化/启停脚本只允许 `security_model` 前缀库，拒绝 `eladmin`。不要复用任何现有生产库。

配置模板见 `.env.example`（复制为不入库的 `.env`）：

| 类别 | 变量 | 说明 |
|---|---|---|
| 必需 | `SECURITY_MODEL_JWT_SECRET` | 至少 32 字节；缺失或过短拒绝启动 |
| 必需 | `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PWD` | `DB_NAME` 必须是 `security_model` 前缀隔离库 |
| 可选 | `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD` | 缓存/知识向量；不可用时回源 MySQL |
| 可选 | `SECURITY_MODEL_DISPATCH_*` | `db` 默认；`rabbit` 需显式配置 |
| 可选 | `SECURITY_MODEL_EXECUTOR_*` | Python 执行器、工作目录和产物保留策略 |
| 可选 | `SECURITY_MODEL_KNOWLEDGE_*`、`SECURITY_MODEL_ASSISTANT_*` | 外部模型默认关闭，启用需单独审批 |

`.env`、真实密码、JWT 密钥、数据库 dump、数据集、权重和运行日志都不得提交。

```powershell
$repo = 'F:\Study\postgraduate\毕业论文\security_model'
Set-Location $repo

# 只放在当前进程；不要写入仓库、命令历史或文档
$env:MYSQL_IT_PASSWORD = '<本机隔离 MySQL 管理员密码>'
$env:SECURITY_MODEL_JWT_SECRET = '<至少 32 字节的本地随机密钥>'

# 可选：提供 VaB 数据集根目录；不提供时只注册 LDD 算法与超参模板
pwsh -File scripts\dev\InitializeSecurityModelDatabase.ps1 `
  -SchemaName security_model_delivery_20261119 `
  -VabDatasetRoot '<VaB 数据集目录>'

# 后端打包；启停脚本默认读取这个 jar
mvn -B -f migration-backend\pom.xml package -DskipTests

# db 模式：单 API 进程，进程内派发
pwsh -File scripts\dev\Start-SecurityModelStack.ps1 `
  -Mode db -SchemaName security_model_delivery_20261119 -ApiPort 8100

# 健康检查
Invoke-RestMethod http://127.0.0.1:8100/actuator/health/readiness
```

停止并确认端口释放：

```powershell
pwsh -File scripts\dev\Stop-SecurityModelStack.ps1 `
  -StateRoot tmp\deploy-stack\security_model_delivery_20261119\db
Get-NetTCPConnection -State Listen -LocalPort 8100 -ErrorAction SilentlyContinue
```

`rabbit` 模式需要额外的 RabbitMQ 与两个 JVM：

```powershell
pwsh -File scripts\dev\Start-SecurityModelStack.ps1 `
  -Mode rabbit -Role all -SchemaName security_model_delivery_20261119 `
  -ApiPort 8100 -WorkerPort 8101
```

`db` 与 `rabbit` 的角色差别：

| 模式 | API 进程 | Worker 进程 | 任务派发 |
|---|---|---|---|
| `db` | 必需 | 禁止 | 事务提交后由进程内有界执行池调度 |
| `rabbit` | 必需 | 必需 | API 写 Outbox，Relay 发布，Worker 消费后执行 |

## 最小可复现演示

演示分两条路径，不要混用：

- **路径 A：只启动看界面**：只需要隔离库、后端和前端，不创建任务，不要求数据集可用。
- **路径 B：跑通短桩任务**：需要先准备一个可验证的本地演示数据集，并在启动 JVM **之前**设置短桩执行器环境变量。

### 路径 A：只启动看界面

1. 初始化隔离库并打包后端（不提供 `-VabDatasetRoot` 时只注册 LDD 算法和默认超参模板）：

```powershell
$repo = 'F:\Study\postgraduate\毕业论文\security_model'
Set-Location $repo
$env:MYSQL_IT_PASSWORD = '<本机隔离 MySQL 管理员密码>'
$env:SECURITY_MODEL_JWT_SECRET = '<至少 32 字节的本地随机密钥>'

pwsh -File scripts\dev\InitializeSecurityModelDatabase.ps1 `
  -SchemaName security_model_delivery_demo
mvn -B -f migration-backend\pom.xml package -DskipTests
```

2. 启动 `db` 模式后端，确认 readiness：

```powershell
pwsh -File scripts\dev\Start-SecurityModelStack.ps1 `
  -Mode db -SchemaName security_model_delivery_demo -ApiPort 8100
Invoke-RestMethod http://127.0.0.1:8100/actuator/health/readiness
```

3. 构建并托管前端，登录后只浏览界面：

```powershell
npm --prefix migration-frontend ci
npm --prefix migration-frontend run build
pwsh -File scripts\dev\Start-FrontendStatic.ps1 -StaticPort 8014 -ApiPort 8100
```

打开 `http://127.0.0.1:8014`，使用初始化脚本输出的演示管理员账号登录；共享环境必须立即改密。此路径不创建任务，也不要求数据集状态为 `success`。

### 路径 B：跑通短桩任务

1. 准备一个**非空**的本地演示数据集目录。短桩不读取样本，只要求目录可读且非空；它不是 CIFAR-10 真实数据：

```powershell
$repo = 'F:\Study\postgraduate\毕业论文\security_model'
New-Item -ItemType Directory -Force -Path "$repo\runtime\demo-dataset" | Out-Null
Set-Content -LiteralPath "$repo\runtime\demo-dataset\README.txt" `
  -Value 'short-stub demo dataset; not real CIFAR-10'
```

2. 初始化隔离库并打包后端（不传 `-VabDatasetRoot`，避免被误认为真实 CIFAR 数据）：

```powershell
$env:MYSQL_IT_PASSWORD = '<本机隔离 MySQL 管理员密码>'
$env:SECURITY_MODEL_JWT_SECRET = '<至少 32 字节的本地随机密钥>'
pwsh -File scripts\dev\InitializeSecurityModelDatabase.ps1 `
  -SchemaName security_model_delivery_demo
mvn -B -f migration-backend\pom.xml package -DskipTests
```

3. **先设置短桩执行器，再启动后端**。已经运行的 JVM 不会读取之后新设置的变量；改完必须重启后端：

```powershell
$env:SECURITY_MODEL_EXECUTOR_PYTHON_EXECUTABLE = 'python'
$env:SECURITY_MODEL_EXECUTOR_PYTHON_SCRIPT = "$repo\scripts\r4\r4_python_stub.py"
# 下面两个变量必须指向同一个受控目录：
# python.workspace 决定 attempt 目录写在哪里；executor.workspace 是进程记录的边界。
$env:SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE = "$repo\runtime\executor"
$env:SECURITY_MODEL_EXECUTOR_WORKSPACE = "$repo\runtime\executor"

pwsh -File scripts\dev\Start-SecurityModelStack.ps1 `
  -Mode db -SchemaName security_model_delivery_demo -ApiPort 8100
Invoke-RestMethod http://127.0.0.1:8100/actuator/health/readiness
```

4. 构建并托管前端：

```powershell
npm --prefix migration-frontend ci
npm --prefix migration-frontend run build
pwsh -File scripts\dev\Start-FrontendStatic.ps1 -StaticPort 8014 -ApiPort 8100
```

5. 打开 `http://127.0.0.1:8014`，登录后完成数据集准备：

- 进入数据集页面，新建数据集：名称 `Stub Demo Dataset`、类型 `image`、路径填 `$repo\runtime\demo-dataset` 的绝对路径、启用。
- 新登记数据集初始为 `pending`，不会出现在可用数据集列表。
- 以管理员身份点击“验证/Validate”，或调用 `POST /api/dataset/{datasetId}/validate`；状态变为 `success` 后才可被任务选用。
- 这一步不是“解析真实 CIFAR-10”，只证明目录存在、可读且非空；短桩任务不依赖真实样本。

6. 在任务中心创建 LDD 任务，选择 `Stub Demo Dataset` 和 `LDD (VaB)`，先做参数校验/预检，再启动。等待任务进入 `success`。

7. 在任务详情查看状态、报告、日志和清洗子集导出入口。

8. 停止并确认端口释放：

```powershell
pwsh -File scripts\dev\Stop-SecurityModelStack.ps1 `
  -StateRoot tmp\deploy-stack\security_model_delivery_demo\db
Get-NetTCPConnection -State Listen -LocalPort 8100 -ErrorAction SilentlyContinue
```

短桩只用于 UI/API 闭环演示，不代表真实 LDD/GPU 结果。真实 LDD 必须按
[`docs/sdd/25-ldd-real-acceptance-checklist.md`](docs/sdd/25-ldd-real-acceptance-checklist.md)
在受控环境另立运行窗口，本轮不启动。

## 构建与验证

```powershell
# 后端单测（不需要 MySQL）
mvn -B -f migration-backend\pom.xml test

# 前端
npm --prefix migration-frontend ci
npm --prefix migration-frontend run build

# Python 适配器
python -m unittest discover -s algorithm-runtime/vab/tests

# MySQL/Redis/RabbitMQ 集成测试（需要隔离库凭据；不要用生产库）
$env:MYSQL_IT_PASSWORD = '<本机隔离 MySQL 管理员密码>'
$env:REDIS_IT = 'true'
$env:RABBIT_IT = 'true'
pwsh -File scripts\run-mysql-it.ps1
```

测试数字、本轮实际结果和未执行项统一记录在
[`docs/sdd/26-current-delivery-facts.md`](docs/sdd/26-current-delivery-facts.md) §4。

## 数据、日志与运行产物

- 仓库内只提交代码、配置模板、SQL、测试和文档。
- `.env`、`secrets/`、数据库 dump、数据集、模型权重、运行日志、`runtime/`、`tmp/`、`target/`、`node_modules/`、`dist/` 均不进入 Git。
- `secrets/` 只报告路径和 Git 跟踪状态，不读取内容。
- 正式 5 epochs 证据保存在仓库外 `F:\security-model-data\ldd-acceptance-20261118\formal-freeze-20261118`，不要移动或删除。
- 任务产物默认在系统临时目录下的 `security-model-executor`；保留策略由 `security-model.executor.artifacts.*` 控制，默认不自动删除。

## 文档与学习入口

- **当前事实**：[`docs/sdd/26-current-delivery-facts.md`](docs/sdd/26-current-delivery-facts.md)
- **SDD 索引**：[`docs/sdd/README.md`](docs/sdd/README.md)
- **源码学习路线**：[`docs/learning/README.md`](docs/learning/README.md)
- **R4 契约与证据**：[`docs/sdd/21-r4-outbox-rabbitmq-design.md`](docs/sdd/21-r4-outbox-rabbitmq-design.md)
- **单机部署演练**：[`docs/sdd/24-stage3-single-node-deployment.md`](docs/sdd/24-stage3-single-node-deployment.md)
- **LDD 正式验收**：[`docs/sdd/25-ldd-real-acceptance-checklist.md`](docs/sdd/25-ldd-real-acceptance-checklist.md)
- **项目介绍/面试材料**：[`docs/project-introduction-and-interview-guide.md`](docs/project-introduction-and-interview-guide.md)、[`docs/resume-project-entry.md`](docs/resume-project-entry.md)
- **Agent/RAG 演示**：[`docs/agent-rag-demo.md`](docs/agent-rag-demo.md)

## 当前限制

- 只覆盖 LDD 训练前数据清洗；训练中、训练后和其他对比算法不进入真实执行。
- 默认单机 `db` 模式；`rabbit` 是可选单机双进程模式，不是多机生产部署。
- 未做生产级多机、Kubernetes、对象存储、跨节点 GPU 调度和 HA。
- 未做论文效果评估、基线对照、多随机种子/多攻击设置。
- 取消、超时、重试的真实 GPU 行为，以及长期 Broker 故障压力，仍是未完成边界。
- Assistant/RAG 外部模型默认关闭，未做真实外部模型生产联调。
