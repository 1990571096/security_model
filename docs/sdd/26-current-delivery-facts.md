# 当前交付事实（唯一权威入口）

> 本文件是当前交付状态、测试口径和验收边界的唯一权威入口。
> 其他文档只链接本文件，不各自维护容易过期的“最新数字”。
> 历史状态保留在 `09-project-status.md`、`21-r4-outbox-rabbitmq-design.md`、
> `24-stage3-single-node-deployment.md` 等文件中，但必须标注为历史快照。

## 1. 交付口径

- 交付物：**单机实验平台工程交付版**。
- 当前结论：**单机实验平台工程交付版已完成相关工程验收**。
- 正式 5 epochs 验收只支持以下结论：工程执行链路、配置快照、产物、GPU 租约、进程收尾和验收脚本闭环。
- 不得据此宣称：论文效果验证完成、生产级多机部署完成、真实外部模型生产联调完成、生产库 `eladmin` 已迁移。

## 2. 当前代码与运行事实

| 项 | 当前事实 |
|---|---|
| 后端 | Java 17 编译目标、Spring Boot 3.5、Spring Security 6、JWT、MyBatis-Plus |
| 前端 | Vue 3、Vite 6、Element Plus、Pinia、Vue Router 4 |
| 事实数据源 | MySQL；任务、状态机、快照、租约、Outbox 账本均以数据库为准 |
| 缓存 | Redis；资源目录缓存和可选知识向量检索，缓存不可用时回源 MySQL |
| 派发 | 默认 `dispatch.mode=db`，事务提交后由进程内有界执行池调度 |
| RabbitMQ | 可选 `dispatch.mode=rabbit`；Outbox + Relay + 独立 Worker + 手动 ACK；默认不启用 |
| Python 执行器 | `PythonLddTaskExecutor` 调用 `algorithm-runtime/vab/algorithm_entry.py`，真实 VaB 目录在仓库外 |
| 数据库默认 | `DB_NAME` 默认 `security_model`；初始化/启停脚本只接受 `security_model` 前缀隔离库，拒绝 `eladmin` |
| 认证 | `SECURITY_MODEL_JWT_SECRET` 至少 32 字节，缺失或过短时拒绝启动 |

## 3. 正式 5 epochs 工程验收

| 项 | 结果 |
|---|---|
| 任务 | `2122901116713205761` |
| run / attempt | `37204a64-2b3d-4c72-8cb1-5b7bc2617dd9` / 1 |
| 状态 | `success`、`progress=100` |
| 时间 | 2026-11-18 17:03:48.372 至 17:09:12.986（UTC+8），约 324.6 秒 |
| 实际轮数 | 原始日志含 `Epoch: 0..4` |
| 验收 | `acceptance.state=passed`，80 项检查，0 失败 |
| 产物 | 16 个文件、46,351,536 B；模型 `0.1.pth` 44,767,430 B |
| 指标 | clean_purity 5%–30% 为 100.00%，35%–60% 为 99.99% |
| GPU 租约 | `default-gpu-0`，17:03:47.786 获取，17:09:13.513 释放，`blocked_at=NULL` |
| 运行期资源 | 最低空闲 RAM 4.13 GB；GPU 峰值 1,153 MiB；利用率峰值 100% |
| 证据包 | `F:\security-model-data\ldd-acceptance-20261118\formal-freeze-20261118\formal-run-20261118` |

关键证据文件：

- `formal-5epochs-run-summary.json`
- `report-final.json`
- `logs-final.json`
- `runtime-samples.csv`
- `artifact-sha256.json`

验收汇总：`F:\Study\postgraduate\毕业论文\security_model\tmp\ldd-formal-5epochs-20261118-run1\run-summary.json`。

正式运行代码为 `4c544c4`；文档提交为 `3c86a12`；对应 CI run `44274820789` 的五个 job 全部成功：
<https://github.com/1990571096/security_model/actions/runs/44274820789>。
该 CI 是正式冻结时的记录，不是本轮文档修改后的 CI 结果。

## 4. 本轮（2026-11-19）实际验证

| 验证 | 命令 | 结果 |
|---|---|---|
| 后端单测（工作区，配置修改前基线） | `mvn -B -f migration-backend/pom.xml test` | **351 项通过，0 失败、0 错误、0 跳过**；配置修改后的权威结果以干净 worktree 为准 |
| Python 适配器测试（工作区） | `python -m unittest discover -s algorithm-runtime/vab/tests` | **11 项通过** |
| 前端依赖与构建（工作区） | `npm ci` + `npm run build` | **成功**；Vite 6.4.3，产物在 `migration-frontend/dist` |
| 干净 worktree 后端单测 | `git worktree add --detach`（HEAD `3c86a12`）后执行 `mvn -B -f migration-backend/pom.xml test` | **351 项通过，0 失败、0 错误、0 跳过**；最终日志 `tmp/clean-backend-test-final2.log` |
| 干净 worktree 后端打包 | `mvn -B -f migration-backend/pom.xml package -DskipTests` | **BUILD SUCCESS**，生成可执行 jar；日志 `tmp/clean-backend-package.log` |
| 干净 worktree Python 测试 | `python -m unittest discover -s algorithm-runtime/vab/tests` | **11 项通过**；日志 `tmp/clean-python-tests.log` |
| 干净 worktree 前端安装/构建 | `npm ci` + `npm run build` | **成功**；97 packages，Vite 6.4.3；日志 `tmp/clean-frontend-ci.log`、`tmp/clean-frontend-build.log` |
| 部署脚本自测 | `pwsh -File scripts/dev/Test-SecurityModelStackScripts.ps1` | **通过**：参数引用、默认值、身份拒绝、状态保留 |
| 初始化生产库拒绝边界 | `InitializeSecurityModelDatabase.ps1 -SchemaName eladmin -RootPassword <占位值>` | **按预期拒绝**：只允许 `security_model` 前缀；未连接生产库 |
| Markdown 相对链接 | `python tmp/check_md_links.py` | **154 条链接，0 broken**（覆盖根 README、docs、后端/前端/算法模块 Markdown） |
| 前端静态托管启停 | `Start-FrontendStatic.ps1 -StaticPort 18080` → `/healthz`、`/` → `Stop-FrontendStatic.ps1` | **通过**：`/healthz`=200、`/`=200；停止后端口无监听 |
| MySQL/Redis/RabbitMQ IT | `mvn -B -f migration-backend/pom.xml verify -Pmysql-it` | **本轮未执行**：本机未注入 `MYSQL_IT_PASSWORD`，且未读取 `secrets/` 换取凭据 |
| RabbitMQ 专项 | `RABBIT_IT=true` | **本轮未执行**：本机 RabbitMQ 服务未运行 |
| 后端启停/健康检查 | `Start-SecurityModelStack.ps1` / `Stop-SecurityModelStack.ps1` | **本轮未执行**：缺少隔离库凭据，未触碰任何现有库 |

干净 worktree 路径为 `tmp/delivery-clean-20261119`（detached HEAD `3c86a12`），
只复制了本轮文档/配置修改，未带入 `secrets/`、`target`、`node_modules`、运行日志或数据集。
前端静态托管在第 18080 端口实测，未启动后端、未连接任何数据库。

历史口径说明：`126` 项 MySQL/Redis/RabbitMQ IT 是 2026-11-18 正式门禁记录；
本轮没有重跑，因此不能把它写成“本轮通过”。R4-01..08、单机 db/rabbit 启停演练
同样是历史证据，本轮不重新执行。

## 5. R4 与单机部署的当前结论

- **R4 实现验收**：2026-11-16 在真实 RabbitMQ 4.1.8、隔离 schema/vhost、双 JVM、
  短 Python 桩下完成 R4-01..08；包括 publisher confirm 丢失/延迟、重复投递、
  DB 中断恢复、取消、死信重放、replay 与新 attempt 竞争。
- **单机部署验收**：2026-11-18 完成 `db` 与 `rabbit` 两种模式的启动、停止、重启、
  排空、端口释放、前端静态托管和健康检查演练。
- **默认运行模式**：`dispatch.mode=db`；不启用 RabbitMQ/Outbox/Worker。
- **未完成边界**：多机 Worker、跨节点 GPU 调度、容器级隔离、长期 Broker 故障压力、
  生产库迁移和生产级高可用均未验收。

历史证据入口：`21-r4-outbox-rabbitmq-design.md` §16、`24-stage3-single-node-deployment.md` §4。

## 6. 当前未完成与不能推断的事项

1. 论文效果评估、基线对照、统计显著性检验未完成；正式 5 epochs 不是论文最终实验。
2. 未做生产级多机部署、Kubernetes、对象存储、跨节点调度和 HA。
3. 未做真实外部模型生产联调；Assistant/RAG 的外部模型默认关闭，需显式配置和预算审批。
4. 生产库 `eladmin` 未迁移、未访问、未修改；`sql/eladmin.sql` 仅作为基础 schema 保留。
5. `screenshots/`、验收证据、测试夹具、历史 SQL 和本机 ignored/untracked 文件均保留，
   本轮未做删除。
6. 代码清理审计：`infrastructure/legacy/LegacyBackendClient.java` 全仓仅命中自身声明/构造器，
   是 Spring `@Component` 但当前无注入引用；因仍有迁移兼容用途且无专项测试覆盖，本轮保留未删。
   `src/`、`.agents` 为空目录，`secrets/`、`runtime/`、`tmp/` 等 ignored/untracked 内容均未移动或删除。
7. 本轮文档修改未提交、未推送、未创建 Tag/Release，也未触发 CI。

## 7. 文档使用规则

- 需要“当前状态/测试数字/验收边界”时，只引用本文件。
- `09-project-status.md` 是历史状态汇编；引用时必须带“历史快照/当时状态”。
- `21-r4-outbox-rabbitmq-design.md` 顶部“部署验收未执行”是 2026-11-16 的历史口径；
  当前结论以本文件 §5 为准。
- `24-stage3-single-node-deployment.md` §4.6/§4.7 的 343/350 单测数字是历史快照；
  当前单测口径以本文件 §4 为准。
- `25-ldd-real-acceptance-checklist.md` 顶部“正式 5 epochs 未启动”是准备阶段历史；
  当前结论以本文件 §3 为准。
- 其他仍出现 `287/106`、`343/126`、`部署验收未执行` 的文档（如 `03`、`05`、`06`、`13`、`14`、
  `16`、`17`、`20`、`22`、`23` 及顶层 `project-*.md`）均已加历史/当前事实入口提示；
  这些数字和状态不是本轮结论，不能单独引用为“当前通过”。
- 干净检出、构建、启动、学习和源码阅读入口见：
  [`../learning/README.md`](../learning/README.md)、[`README.md`](../../README.md)。
