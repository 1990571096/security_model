# 设计决策记录

## ADR-001：第一阶段不物理删除大批 ELADMIN 模块

日期：2026-07-11

决策：第一阶段先新增 SDD、明确裁剪边界、调整首页入口，不直接删除 generator、tools、maint 等大模块。

原因：

- 当前项目没有可用 Git 仓库状态，缺少快速回滚能力。
- 动态菜单来自数据库，前端组件、后端接口、SQL 初始化脚本需要同步裁剪。
- 直接删除容易造成菜单指向空组件、Maven 依赖断裂或接口 404。

影响：

- 短期代码中仍存在无关模块。
- 后续 R1/R2 迭代按菜单、SQL、前端、后端依赖顺序删除。

## ADR-002：优先围绕训练前数据清洗重构

日期：2026-07-11

决策：优先完善数据集、算法、训练前防御、任务进度、模型管理；训练后防御保留但不作为当前主流程。

原因：

- 开题报告的核心创新点是两个训练前防御算法。
- 系统名称和论文目标都聚焦“面向数据清洗”。
- 训练中算法后续需要统一执行器支撑，不宜在 CRUD 服务中硬编码。

影响：

- `PreDefense` 将重构为第一条主链路。
- `DuringDefense` 更适合作为安全训练/训练中防御的承载。
- `PostDefense` 暂时保持扩展状态。

## ADR-003：算法以执行器/插件方式接入

日期：2026-07-11

决策：平台不把具体安全算法直接写入业务 Service，而是通过统一执行器边界调用。

原因：

- 防御算法迭代频繁，直接嵌入 Java CRUD 层会造成高返工。
- 算法大概率依赖 Python、PyTorch、GPU、数据文件路径和长任务执行。
- 执行器边界能隔离任务编排和算法实现，方便后续替换为独立算法服务。

影响：

- 需要新增执行器接口、任务输入输出协议和日志协议。
- 现有 `startTask` 的 TODO 将被执行器调用替代。

## ADR-004：目标技术栈升级到 Spring Boot 3 与 Vue 3

日期：2026-07-11

决策：目标系统采用 Spring Boot 3.x + Java 17 LTS + Vue 3 + Vite + Element Plus，不继续以 Spring Boot 2.7 + Vue 2 作为最终技术基线。

原因：

- Spring Boot 3、Java 17、Vue 3、Vite、Element Plus 是当前更成熟、稳定、流行且仍在维护的主流技术栈。
- Vue 2 已停止维护，继续扩展会增加后续维护风险。
- 论文系统需要体现工程化与可持续迭代能力，技术栈不应停留在旧后台模板。

影响：

- 迁移会涉及 `javax` 到 `jakarta`、Spring Security 配置、Swagger/Knife4j、MyBatis-Plus 兼容性、前端动态路由和组件 API 改造。
- 技术栈升级不与第一轮业务边界梳理同时强推；应先裁剪无关模块，再升级后端，最后迁移前端。
- 算法执行器接口应按新技术栈设计，避免在旧 CRUD 服务中继续堆业务逻辑。

## ADR-005：先实现可视化 MVP，再集成真实安全算法

日期：2026-07-11

决策：当前阶段先完成“可视化界面 + 基础资源管理 + LDD 任务可运行闭环”的 MVP。其他算法和训练阶段后续按执行器接口扩展。

原因：

- 先形成可演示平台骨架，能尽早验证页面、任务状态、参数配置、结果展示和报告结构。
- 真实算法依赖 Python、PyTorch、GPU、数据集格式和实验参数，过早绑定会增加返工。
- 当时通过 mock 执行器稳定了前后端协议；该实现不代表当前迁移版的真实执行方式。

影响：

- 当前主流程已经切换为真实 LDD 执行；历史 mock 执行器不属于迁移版当前执行链路。
- 后续接入其他算法时，复用 `DefenseTaskExecutor` 和稳定的结果协议，不改变当前 LDD 页面主流程。

## ADR-006：初始化数据采用补丁脚本收敛

日期：2026-07-11

决策：当前阶段不大规模重写 `sql/eladmin.sql`，新增 `sql/security_model_mvp_seed.sql` 作为 MVP 初始化补丁。

原因：

- 原始 SQL 同时包含菜单、角色、权限、演示数据和已有安全模块数据，直接删除容易破坏外键与角色菜单关系。
- 补丁脚本可以在现有开发库和新库初始化后重复执行关键收敛动作，便于快速联调。
- 后续完成菜单、角色、权限梳理后，再生成干净的正式初始化脚本，返工成本更低。

影响：

- 第一阶段通过隐藏菜单和禁用测试数据收敛功能面，不代表相关后端模块已经物理删除。
- 部署新环境时需要先执行主 SQL，再执行 MVP seed patch。

## ADR-007：任务执行结果先采用 JSON 协议承载

日期：2026-07-11

决策：训练前任务的 `cleaning_result` 和训练中任务的 `training_log` 先作为统一执行结果 JSON 承载字段，不在 MVP 阶段新增 `failure_reason`、`artifact_path` 等数据库列。

原因：

- 现有表已经有 JSON 字段，可立即支撑结果详情、失败原因、执行时间和 artifact 元信息。
- 后续真实算法的输出指标和文件形态还不完全确定，过早固化列会增加迁移成本。
- 前端详情页只需要依赖稳定 JSON key，不需要知道具体算法实现。

影响：

- 执行器实现需要至少写入 `executor`、`stage`、`status`、`message`、`started_at`、`finished_at`、`artifacts`。
- 失败结果需要额外写入 `error_code` 和 `failure_reason`。
- 后续当真实算法稳定后，可再评估是否把高频查询字段独立成数据库列。

## ADR-008：资源登记持久化与安全停用

日期：2026-10-26

决策：迁移版资源增改通过 MySQL 原生接口保存；移除仅停用，保留任务外键和历史快照。登记路径属于元数据，未经独立验证不得标记解析成功；重复名称由数据库唯一约束兜底。

原因：前端内存编辑不能支撑跨会话业务流程，硬删除会破坏实验溯源。UI 仅能登记元数据，不具备文件上传/解析能力。

验收：字段校验、冲突提示、事务审计、刷新后可见、停用后历史可查且不再进入可用资源选择。

## ADR-009：任务分页和查询批量化

日期：2026-10-26

决策：增设受限页大小的任务分页 API，旧列表 API 暂时兼容；本页资源名称使用批量 ID 查询，避免 N+1。MySQL 为唯一任务事实源。

验收：分页结果与总数一致，过滤器和排序稳定；单页资源查询数不随行数增长。

## ADR-010：Redis 仅缓存可重建资源目录

日期：2026-10-26

决策：迁移版引入 Spring Data Redis，仅缓存数据集、算法和超参数目录及按 ID 查询结果。
MySQL 继续作为资源和任务的事实源，任务状态、任务报告和执行快照不写入 Redis。

实现约束：

- 缓存键使用 `security-model:resource:*` 命名空间，默认 TTL 为 5 分钟。
- 资源新增、编辑和停用时主动清理对应列表缓存和实体缓存。
- Redis 读取、写入或删除失败时记录 debug 日志并直接回源 MySQL，不阻塞业务请求。
- 当前不使用 Redis 分布式锁、不使用 Redis 保存任务状态，也不以 Redis 替代消息队列。

原因：资源目录属于读多写少、可由 MySQL 重建的元数据，适合用来验证缓存一致性和降级能力；
任务状态则需要依赖数据库条件更新、`run_id` 和事务语义，迁移到缓存会扩大一致性风险。

验收：缓存命中不访问数据库；缓存未命中回源并写入 TTL；资源变更清理缓存；Redis 不可用时
资源查询仍可正常返回 MySQL 数据。

## ADR-011：单机多用户实验归属与外部模型数据边界

日期：2026-11-13；状态：规划决策，尚未完成多用户归属与 Agent 接入。

决策：目标交付为单机真实运行，多个登录用户分别管理和查看自己的实验。MySQL 是实验归属与任务状态的事实源，数据集和产物留在受控本地目录。分布式 Worker 仅是可选扩展，不作为交付门槛。开发资源按 RTX 2060 6GB 显存、16GB 内存预算；多用户不代表允许多个 GPU 任务同时运行。

实验任务需关联稳定用户 ID；任务列表、ID 查询、重试/取消、进度、报告、日志、导出与文件下载均须服务端授权，跨用户访问不得靠前端隐藏入口防护。管理员跨用户权限应显式定义并审计。共享算法/数据集目录的权限、历史无 owner 记录的迁移与隔离方案须在 R0 确定，不能默认把旧实验暴露给所有用户。

Agent 采用外部大模型 API，本机不部署大模型。生成模型和 embedding 服务均只能接收经过当前用户权限过滤、服务端脱敏、字段白名单限制的 SDD 文档片段、算法参数说明及实验报告摘要；禁止发送原始样本、完整日志、本机绝对路径、凭据与用户个人信息。供应商、费用上限、数据留存条款和密钥注入方式在接入前另行确定。

验收：双用户在 MySQL + HTTP 集成测试中各自创建、管理和查看实验，跨用户列表及 ID 直取/产物下载均无法越权；外部 API 出站请求可测试白名单和脱敏，违规内容不会离开服务端。以上均为后续迭代验收条件，不代表当前代码已经实现。

## ADR-012：R0 事实冻结——文档以运行代码为准，归属契约进入设计阶段

日期：2026-11-13；状态：已生效（仅冻结事实与契约，不含实现）。

决策：

1. 文档与实现冲突时，以 `migration-backend` 源码、`sql/` 脚本和**已实际执行的测试**为事实，先修文档再谈实现。本轮确认并修正的冲突如下，不允许回退到旧表述：

| 编号 | 冲突点 | 修正后的事实 |
| --- | --- | --- |
| C1 | `07-executor-contract.md` 称 `result.json` 缺失且退出码为 0 时“补最小成功结果” | `PythonLddTaskExecutor.invoke()` 直接抛 `RESULT_MISSING`，任务判失败。不存在补偿成功路径 |
| C2 | `07` 与 README 让迁移版用 `--spring.profiles.active=dev,python-executor` 启动 Python 执行器 | `python-executor` profile 只存在于旧 `eladmin`（`@Profile("python-executor")`）。迁移版通过配置键 `security-model.executor.python.*`（环境变量 `SECURITY_MODEL_EXECUTOR_PYTHON_SCRIPT` 等）注入 |
| C3 | `01` 把数据导入写成“上传 + 解析” | 只有本地路径元信息登记；无上传接口；`parse_status` 被固定写 `pending`，平台无推进入口 |
| C4 | `03` 列出 `/api/tasks`、`/api/reports/{id}`、`/api/datasets/upload`、`/api/executors/callback` | 均不存在，实际接口以控制器为准（见 03 第 6 节） |
| C5 | `09`/`11` 称页面“接口失败显示明确错误状态，不使用样例数据” | 工作台、案例页、运行监控页、接口扩展预览为前端硬编码演示数据（含 RTX 3090 节点利用率、攻击成功率 4.7%、累计任务 28、虚构实验配置清单）；仅资源/任务/报告/导出走真实接口 |
| C6 | `11` 称保留 `GET /api/pre-defense/download-cleaned-dataset/{taskId}` 兼容接口、可登记模型资源 | 两者都不存在 |
| C7 | `09`/`16` 记录“10 项单元测试” | 2026-11-13 实测 `mvn verify` 为 9 个测试类、30 项单元测试全通过（不含 `mysql-it`） |

2. R0 冻结以下语义，后续迭代不得悄悄改动：任务五态与允许的状态边（见 03 第 5 节）、`request.json`/`result.json`/`progress.json` 的字段集合、`run_id + attempt` 回调校验规则、`(task_id, isolation_ratio)` 导出幂等键、产物路径 containment 规则。需要变更时先加新 ADR。

3. 多用户归属的**实现方案尚未决定**（共享资源写入权限、历史 `create_by` 回填口径、GPU 并发上限、路径验证入口）。推荐方案与风险见 [18-multiuser-ownership-and-resources.md](18-multiuser-ownership-and-resources.md)、[19-local-data-layout-and-retention.md](19-local-data-layout-and-retention.md)。在用户确认前，不得给历史数据赋予任意用户访问权，也不得按推荐方案直接改配置或改代码。

4. 演示内容必须与真实能力可区分：演示页数据不得进入真实指标口径，也不得写在简历或论文中作为实验结果。展示算法（LDD 以外的算法卡片）继续保持“展示占位”。

5. 本轮范围仅限文档与设计契约：不实现 R1 授权、不接入消息队列/RAG/Agent、不修改也不重跑既有 LDD 128 batch / 5 epochs 配置。

原因：R0 的职责是把“文档说什么”和“代码做什么”对齐。旧文档中的三处乐观描述（补偿成功、profile 启动、无样例数据）会直接误导后续授权与可靠性改造，必须先纠正再进入 R1。

影响：
- 修正后的文档降低了宣称能力，但不降低代码能力；R1 的验收基线因此可测。
- 04/16 中的“已完成”条目若与上表冲突，以本 ADR 为准，后续统一收敛。

## ADR-013：R1 落地决策——稳定用户 ID 归属、共享资源只读、历史数据隔离

日期：2026-11-13；状态：**已实施，评审结论“有条件通过”；隔离库迁移与双账号端到端验收已实跑通过，生产库迁移未执行**。

用户指示“按 R0 推荐方案开发”，据此把 18 号文档 §6 的待决项 D1、D2、D3、D6 落为决策，D4（GPU 并发上限）、D5（自动重试策略）、D7（部署数据根目录）仍留到 R2，本轮不实现。

决策：

1. **归属键**：`sys_pre_defense` 新增 `owner_user_id BIGINT NULL`，作为唯一授权依据；`create_by` 只保留为用户名快照（展示与审计用），不再参与授权。新增 `data_origin`（`user` / `demo` / `legacy`）与 `owner_backfill_status`（`matched` / `unattributed`）。
2. **身份**：JWT 增加 `adm` 声明，签发时来自 `sys_user.is_admin`；`JwtAuthenticationFilter` 把 `(userId, username, admin)` 作为 principal 注入 SecurityContext。旧 token 没有 `userId`（升级前签发），视为无归属身份：可登录态访问资源共享目录，但任何任务写操作返回“登录状态无效，请重新登录”。不引入单独的会话表，刷新令牌不在本轮范围。
3. **共享资源（D1 = 方案 A）**：数据集/算法/超参数模板的写操作仅管理员；普通用户只读。`ResourceCommandService` 在入口校验，越权返回 403 `ADMIN_REQUIRED`。
4. **历史数据（D2/D3）**：新增决策表 `sys_pre_defense_ownership_decision`，一行一个任务，`decision` 只能取 `demo` / `assign` / `reject`，必须填 `reason` 与 `decided_by`；`assign` 必须指定存在的 `sys_user.user_id`。**脚本不再按用户名自动匹配、也不解析 ID 字符串**：没有决策的历史行一律 `unattributed`。非法决策（未知任务/未知用户/非法取值/字段缺失）会让整批回填在写入前中止。**迁移不修改任何业务字段**，只写三列标记。
5. **管理员跨用户读（默认关闭）**：配置项 `security-model.ownership.admin-cross-user-read=false`。开启后管理员可读他人任务、报告、日志、产物与导出，并写审计；**任何角色都不能跨用户写**（编辑/启动/重试/取消/删除/导出提交）。
6. **数据集验证入口（D6）**：新增 `POST /api/dataset/{id}/validate`（仅管理员），校验路径存在、可读、非空后把 `parse_status` 推进为 `success` 并写 `parse_message`/`parse_time`；失败写 `failed` 与原因，不抛 500。这是消除“登记即死路”的最小实现，不做内容级解析。
7. **配额口径**：单用户运行任务数按 `owner_user_id` 计数；`owner_user_id IS NULL` 的历史任务不占配额，也不会被新逻辑认领。
8. **R1 不做**：GPU 单槽租约、导出启动恢复、日志/产物限额、消息队列、RAG、Agent。这些仍在 R2/R3/R4+。
9. **令牌不撤销，但权限每次请求重算（评审后补充）**：JWT 只证明“谁登录过”，`enabled` 与 `is_admin` 以 `sys_user` 当前行为准。`JwtAuthenticationFilter` 每个请求按 `userId` 读取账号状态：账号被禁用或删除则本次请求不认证；管理员降权后 `ROLE_ADMIN` 立即消失，令牌里的 `adm` 声明不参与授权。代价是每个认证请求一次主键读，换取“禁用/降权无需等 8 小时令牌过期”，也不引入令牌黑名单或权限版本表。旧 token（无 `userId`）不查询、不认证为可写身份。
10. **demo 行任何人不可写（评审后补充）**：迁移会给演示行回填 owner 以便追溯，但 `TaskAccessGuard.requireOwner` 先判 `data_origin='demo'` 并直接拒绝。写规则与读规则（非跨用户管理员不可见）保持一致，避免“能按 ID 改一条自己看不到的行”。
11. **历史归属只能显式决定，禁止隐式认领（评审第二轮补充）**：删除按 `create_by` 的自动匹配；`matched` 只表示“人工确认”。`create_by='system'` 这类无账号作者不得设永久映射规则，需要归属时写一条 `assign` 决策并说明理由。仓库真实库的裁定：1–4 号 `demo`；12 号 `assign user_id=1`（system 为平台批处理作者）；8–11 号进入审核清单，未确认前保持 `unattributed`。

原因：R0 已证明当前实现是“单库共享语义”，任何多用户验收都会失败；而单机、少量真实用户的场景下，方案 A 能以最小改动把“各自管理和查看实验”变得可测，同时不把历史演示数据误当真实实验。

影响：
- 数据库需要执行 `sql/security_model_ownership.sql`；代码依赖新列，**未执行迁移则启动后任务相关接口报错**（这是刻意的显式失败，不做静默降级）。
- 旧 token 在有效期内无法执行任务写操作，需重新登录；这是升级代价，记录在 README。
- 资源写操作从“任意登录用户”收紧为“管理员”，前端非管理员保存按钮会收到 403；R1 不改前端交互，只在接口层强制。
- 回滚：`sql/security_model_ownership_rollback.sql` 删除三列即可恢复旧语义；代码回滚需同步回滚 jar，但**不要**删列后再跑旧代码前恢复备份，避免 owner 数据丢失后无法追溯。

实施结果（2026-11-13，R1 阶段）：决策 1–11 已落地；`TaskAccessGuard` 为核心判定点，`owner_user_id` 是唯一授权键；R1 当时单元测试 57 项通过、`verify -Pmysql-it` 的 24 项 MySQL 集成测试通过（持久层 5、迁移脚本 6、双账号端到端 9、跨用户开关 4；R2 第一切片后为 62 + 29，见 ADR-014）。评审后的修正：①令牌权限改为每请求回查 `sys_user`（禁用/降权立即生效）；②demo 行任何人不可写；③迁移改为结构化决策表，删除用户名自动匹配与快照启发式，非法决策整批中止。第三轮按裁定：1–4 号 `demo`、12 号 `assign user_id=1`、8–11 号待审核保持 `unattributed`；`OwnershipMigrationScriptIT` 实跑 6/6（含“system 任务显式归属 admin”“无决策不认领”“非法决策中止”）。**生产库尚未执行迁移**，不得表述为生产已验证。前端未做交互改动，非管理员保存资源会直接收到 403。决策 8 的范围仍未越界（无 MQ/RAG/Agent）；GPU 租约不在 R1 内，已由 R2 单独立项（ADR-014）。

## ADR-014：R2 第一切片——MySQL 单槽 GPU 租约、fencing 与导出重启收敛

日期：2026-11-13；状态：**已实施并通过隔离库测试；未修改/未重跑真实 LDD，未在生产库执行建表**。

背景：适配器 `VAB_ROOT/.security-model-vab.lock` 是 `O_CREAT|O_EXCL` 文件锁，`SIGKILL`／断电后残留会让所有后续 LDD 永久失败；`TaskExecutionQuotaPolicy` 的 `synchronized` 只覆盖方法体，锁释放后才 `claimForExecution()`，并发下可越过配额。单机单 GPU 场景不需要 MQ 或分布式调度，需要的是数据库层的唯一活动租约。

决策：

1. **单槽表 `sys_gpu_lease`**：`slot_id` 主键（默认 `default-gpu-0`），记录 `lease_token`、`task_id`、`run_id`、`attempt`、`fencing_token`、`acquired_at`、`heartbeat_at`、`expires_at`、`released_at`。不按用户切分槽位——GPU 是设备级资源。
2. **获取是单条条件 UPDATE**：`lease_token IS NULL OR expires_at < now` 时才允许覆盖，并 `fencing_token + 1`。获取与 `claimForExecution` 在**同一事务**：抢不到槽位抛 `GPU_BUSY` 并回滚任务领取；领取失败则回滚租约，不出现半占用。
3. **租期与续租**：`lease-ttl-seconds=300`，`heartbeat-interval-ms=60000`；`GpuLeaseHeartbeat` 续租本 JVM 的活动租约。续租失败（租约已被接管）时取消本地 Python 进程并把任务标记为 `GPU_LEASE_LOST`，不静默继续占用 GPU。
4. **释放**：执行器 `finally` 按 `lease_token` 释放（取消/超时/异常都走这条路）；启动恢复扫描按 `task_id + run_id` 释放（JVM 已死，token 不可知）。
5. **Fencing**：每次获取令牌单调 +1；续租与释放都以 `lease_token` 为条件，旧 worker 既不能延长也不能释放新租约。任务状态更新继续用 `run_id + attempt` 条件更新，两套条件互不替代。租约 token 用 `@JsonIgnore` 排除在 `request.json` 与执行快照之外，快照只保留 `slotId`/`fencingToken`/`expiresAt` 供审计。
6. **并发口径**：GPU 准入并发由数据库租约强制为 **1**；`max-running-tasks=4`、`max-running-tasks-per-user=2` 保留为“拒绝/排队”的业务上限，不再被当作 GPU 并发数。多用户不等于多 GPU 并发。
7. **导出重启收敛**：`ExportRecoveryRunner` 在启动时把 `pending`/`running` 导出显式置为失败（`EXPORT_NOT_STARTED` / `EXPORT_INTERRUPTED`），不自动重派发——可能写到一半的 ZIP 不能标 success；用户通过既有失败详情重新提交。导出进程超时后先杀进程树再强杀，避免子进程继续写文件。
8. **第一轮不做**（第二轮已补，见 ADR-015）：适配器锁陈旧接管（pid + 启动时间）、`GPU_LOCK_BUSY/GPU_OOM/CUDA_UNAVAILABLE` 细分、自动重试队列、attempt 级产物限额。

原因：文件锁和 JVM 锁都不能跨进程、不能抗硬杀；把“谁在用 GPU”落到 MySQL 的单一活动行，才能让两个请求、两个实例和重启后的回收都有同一事实源。

影响：
- 必须先执行 `sql/security_model_gpu_lease.sql`；未建表时任务启动会**显式失败**（`GPU_BUSY` 之外的 SQL 错误），不做静默降级。
- 回滚 `sql/security_model_gpu_lease_rollback.sql` 会删除租约历史；只可在确认无任务执行后使用。
- 租期 300 秒小于 Python `timeout-seconds=3600`，因此续租是正确性的一部分：JVM 卡死超过 TTL 时租约会过期，单机单实例下由人工确认进程后再启动新任务。
- 未跑真实 LDD，因此“6GB 显存下的并发上限”仍无实测依据；本轮只把并发固定为 1。

## ADR-015：适配器陈旧锁接管与失败码细分

日期：2026-11-13；状态：**已实施并通过适配器单元测试；未跑真实 LDD**。

背景：ADR-014 把并发门槛移到 MySQL 租约后，适配器文件锁仍是第二道防线。原锁只写裸 pid、无年龄判断：进程被 `SIGKILL`／断电后残留文件会让所有后续 LDD 永久失败，且失败统一为 `VAB_INTEGRATION_ERROR`，运维无法区分“锁被活进程占用”“显存不足”“CUDA 环境不可用”。

决策：

1. **锁元数据结构化**：锁文件写 JSON（`version/pid/host/task_id/run_id/token/acquired_at`）；旧格式（裸 pid 文本）仍可读，不被误判。
2. **陈旧判定只看持有者是否还活着**：pid 不存活才允许接管；pid 存活一律 `GPU_LOCK_BUSY`，不用“文件存在太久”直接抢活进程的锁。无 pid 或内容不可解析时，才退化为按 `SECURITY_MODEL_VAB_LOCK_STALE_SECONDS`（默认 21600）年龄阈值接管。
3. **接管要防误删新锁**：读到陈旧锁后先 `os.replace` 到隔离名，再比对隔离文件里的 `token` 是否仍是读到的那一个；不一致说明其间已有进程换了锁，恢复原文件并返回 `GPU_LOCK_BUSY`，不制造两个持有者。
4. **释放校验 token**：`release_lock` 只在磁盘锁的 `token` 等于本次运行的 token（或旧格式无 token）时删除，慢进程不能删掉后继者的锁。
5. **进程存活探测不发信号**：Windows 用 `OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION)` + `GetExitCodeProcess == STILL_ACTIVE`；POSIX 用 `os.kill(pid, 0)`。（Windows 的 `os.kill(pid, 0)` 会真的终止进程，禁止使用。）
6. **失败码细分**：适配器按子进程输出归类 `GPU_OOM`（`CUDA out of memory` / `CUBLAS_STATUS_ALLOC_FAILED` / `CUDNN_STATUS_ALLOC_FAILED`）、`CUDA_UNAVAILABLE`（无设备／驱动版本不符／未编译 CUDA），其余保持 `VAB_INTEGRATION_ERROR`；锁冲突单独为 `GPU_LOCK_BUSY`。`IntegrationError` 携带 `error_code`，写入 `result.json.error_code`。
7. **Java 只在没有 result.json 时兜底归类**：子进程非零退出且未写结果时，按 stderr 文本归类为 `GPU_OOM`/`CUDA_UNAVAILABLE`/`GPU_LOCK_BUSY`，否则 `PYTHON_PROCESS_EXITED`；有 result.json 时一律以适配器写入的 `error_code` 为准，Java 不用文本猜测覆盖协议结果。
8. **不自动重试**：`GPU_OOM`、`CUDA_UNAVAILABLE` 明确不可自动重试；`GPU_LOCK_BUSY` 只提示稍后重试，自动重试队列仍留 R3（D5 未关闭）。

原因：锁的价值是阻止两个进程同时进 CUDA，宁可保守地判“占用中”，也不能为省一次人工操作而误抢活进程的锁；失败码只有作为稳定契约写入结果，才能被前端与后续重试策略使用。

影响／残余风险：

- 接管存在一个很窄的窗口：读陈旧文件与 `os.replace` 之间若被其他进程刷新，靠 token 比对回滚；但仍可能短暂把新锁移走再恢复，期间第三方会看到锁不存在。单机单实例 + 数据库租约为第一门槛，接受该窗口，不宣称“无竞态”。
- pid 复用会被当作活持有者（保守，需人工确认）；无 pid 的损坏文件最多阻塞 6 小时。
- 适配器单元测试 11 项（`algorithm-runtime/vab/tests/test_algorithm_lock.py`，`python -m unittest`），Java 归包测试 7 项；**均不涉及真实 CUDA／真实 LDD**。
- `SECURITY_MODEL_VAB_LOCK_STALE_SECONDS` 只影响“无 pid／不可解析”文件的接管，不改变活进程判定；未把它做成按任务超时自动计算，避免与 3600 秒执行超时隐式耦合。

## ADR-016：attempt 级产物限额、清单与保留期

日期：2026-11-13；状态：**已实施并通过单元测试；默认不删除任何文件；未跑真实 LDD**。

背景：每次执行写 `pre_defense-<taskId>/attempt-<n>/`，里面有多份日志和结果文件；长期运行会无界增长，也没有可用于完整性核对的文件清单。R2 要求定义 attempt 级目录、日志截断、文件上限、保留期与校验，且清理只能作用于“已过期且无运行引用”的目录。

决策：

1. **attempt 目录不变**：`<workspace>/pre_defense-<taskId>/attempt-<n>/`，重试递增 attempt，旧 attempt 不覆盖。目录即产物边界，不再为每次执行新建随机目录。
2. **日志截断**：`stdout.log`/`stderr.log` 超过 `security-model.executor.artifacts.max-log-bytes`（默认 20 MiB）时只保留尾部，并在文件头写入截断标记与原始字节数；截断发生在进程结束后、写清单前，不影响失败分类（分类先读原始 stderr）。
3. **SHA-256 清单**：每次执行结束在 attempt 目录写 `manifest.json`，列出该目录下每个文件的相对文件名、字节数与 SHA-256；清单本身不纳入列表。写清单或截断失败只记 warn，不改变任务成败。
4. **保留期默认关闭**：`security-model.executor.artifacts.retention-days` 默认 `0`（不删除）。只有 `> 0` 时定时任务才工作。
5. **删除条件必须同时满足**：目录名可解析为 `pre_defense-<id>/attempt-<n>`；任务存在且状态为 `success/failed/canceled`；`n != current_attempt`；目录最后修改时间早于保留期；删除前再次确认路径位于配置的 workspace 内。任一不满足即跳过。`running`/`pending` 任务、当前 attempt、未知任务目录一律不动。
6. **不做文件大小硬上限以外的清理**：不自动删除单个大文件（如 `*.pth`），只按目录保留期整体清理；避免误删仍在被引用的产物。
7. **配置缺失即保守**：未配置 workspace 时清理任务直接返回；配置错误不会删除工作目录之外的内容。

原因：删除类操作不可逆，必须让默认行为是“什么都不做”。清单与截断解决“可核对、可限流”，保留期解决“长期增长”，三者分开关闭/开启，便于按机器磁盘实际情况启用。

影响／残余风险：

- 单元测试 7 项（截断保留尾部、短日志不动、清单 3 文件且 SHA-256 长 64、终态任务删旧 attempt、运行中任务不删、关闭时不删、未知目录不删）；**未覆盖真实 LDD 产物**。
- 清单只覆盖顶层文件，不含子目录递归哈希；导出 ZIP 不在该目录内，走既有导出校验。
- 保留期开启后，历史答辩演示所依赖的旧产物可能被清理，启用前需确认保留天数与备份。

## ADR-017：恢复前必须确认执行子进程已退出

日期：2026-11-13；状态：**已实施并通过单元测试；未跑真实 LDD**。

背景：R2 的启动恢复会把所有 `running` 任务置失败并释放 GPU 租约。但 Windows 上 JVM 被硬杀不会自动终止它的 Python 子进程：遗留进程可能仍在 CUDA 中训练，而租约已被释放，下一个任务会认为自己独占 GPU 并进入同一张卡，导致显存竞争或 OOM。

决策：

1. **记录子进程**：`PythonLddTaskExecutor` 在 `ProcessBuilder.start()` 之后立即把 `pid`、`command`、`startInstant` 写入 attempt 目录的 `process.json`；写失败只 warn，不影响执行。
2. **恢复前探测并终止**：`TaskRecoveryRunner` 对每个 `running` 任务调用 `AttemptProcessRegistry.reapIfOrphaned(taskId, currentAttempt)`：无记录 → `NO_RECORD`；进程已不存在 → `EXITED`；`startInstant` 与记录不一致 → 视为 pid 被复用，`EXITED`（不杀无关进程）；仍存活且启动时间一致 → 先杀后代再杀自身，2 秒内退出为 `REAPED`，否则 `KILL_FAILED`。
3. **只有确认进程已消失才释放租约**：`NO_RECORD/EXITED/REAPED` 才调用 `releaseByRun`；`KILL_FAILED` 保留租约（任务仍标失败），让租约按 TTL 自然过期并记录 error 日志，避免“进程还活着但槽位已放开”。
4. **不凭 pid 直接杀**：pid 复用检查以 `startInstant` 相等为前提；记录缺失或不可解析时不杀进程、按 `NO_RECORD` 处理并释放租约（此时无证据表明本平台曾启动该进程）。

原因：GPU 独占是设备级事实，不能因为“数据库里任务已失败”就推断进程已退出；必须在释放槽位前取得进程已死的证据。

影响／残余风险：

- `KILL_FAILED` 时租约最多再占用 300 秒（TTL），期间新任务收到 `GPU_BUSY`；这是有意的保守行为。
- `process.json` 只用于恢复判定，不对外提供下载；不写入 `result.json`。
- 单元测试 3 项（无记录、已退出、存活进程被杀）与恢复用例 1 项（杀不掉时不释放租约）；**未在真实崩溃场景演练**。

## ADR-018：调度意图与重启语义（R3 第一增量，设计冻结、未实施）

日期：2026-11-13；状态：**仅设计，代码未实施**。不得在状态文档中写成已实现。

背景：当前 `launchTask` 在事务内完成租约 + 领取，事务提交后才派发执行；若 JVM 在提交成功、派发之前退出，任务停在 `running` 且没有任何执行证据，重启恢复只能统一置失败，无法判断“从未派发”还是“执行到一半”。

决策（待实施）：

1. **调度意图与领取同事务**：新增 `sys_task_dispatch_intent`（`task_id`、`run_id`、`attempt`、`status`（`pending`/`dispatched`/`abandoned`）、`request_json`、`created_at`、`dispatched_at`，唯一键 `(task_id, run_id, attempt)`）。`request_json` 保存本次执行请求（`gpuLeaseToken` 已被 `@JsonIgnore` 排除），用于精确重放。
2. **先标记后提交执行**：事务提交后，先把意图置 `dispatched`，再调用执行器。`pending` 因此只可能表示“从未提交给执行器”。
3. **重启语义**：启动时先做 ADR-017 的进程确认；对 `pending` 且任务仍 `running`、`current_run_id` 匹配的意图，重新获取租约（`GPU_BUSY` 则保留待下次），成功后重放 `request_json`、置 `dispatched`；对 `dispatched` 的 running 任务置 `EXECUTOR_RESTARTED` 失败；任务失败时把对应意图置 `abandoned`。
4. **不做无限重派**：第一增量只在启动时重放一次，不引入周期扫描器；周期扫描与幂等键留 R3 后续。
5. **状态词汇不变**：对外仍只用 `pending/running/success/failed/canceled`，不改 API 与前端；意图状态是内部审计维度。

验收计划：模拟“提交后、标记前”退出 → 重启后重放且只执行一次；`dispatched` 意图不重放；`GPU_BUSY` 时保留意图不执行；孤儿 Python 未死时不释放租约；意图表唯一键阻止同一 `run_id` 二次派发。以上均需真实 MySQL IT，未完成前不声称 R3 已交付。



