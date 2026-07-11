# 重构待办与验收标准

## R0：文档基线与入口对齐

状态：已完成。

目标：

- 建立 SDD 文档集。
- 首页从 ELADMIN 示例看板改为安全训练工作台。
- 明确保留、降级、裁剪模块。

验收：

- `docs/sdd` 中存在系统范围、现状、目标架构、重构待办、决策记录。
- 前端默认首页不再展示访问量、消息、购物等无关示例指标。

## R1：菜单与初始化数据裁剪

状态：部分完成。

目标：

- 保留论文系统主流程菜单。
- 从 SQL 初始化中删除无关菜单、无关演示数据、无关算法样例。
- 将 `sys_dataset` 样例收敛为图像数据集。
- 将 `sys_algorithm` 样例收敛为 LDD、MetaPurifier、训练中防御占位、训练后防御占位。

验收：

- 新库初始化后侧边栏只出现安全训练平台必要菜单。
- 数据集样例全部为图像数据。
- 算法样例的 `defense_stage` 与主流程一致。

当前进展：

- 新增 `sql/security_model_mvp_seed.sql`，用于在主初始化脚本后补充 MVP 基线算法和超参数。
- 采用隐藏菜单方式收敛旧 ELADMIN 功能面，暂不物理删除历史菜单和角色关联。
- 明显测试算法/模型行已在补丁脚本中置为不可用/废弃。

## R1.5：MVP 可演示任务闭环

状态：后端和前端基础闭环已完成，待数据库联调。

目标：

- 在真实算法集成前提供 mock 执行器。
- 训练前防御任务启动后能自动推进进度并生成清洗结果。
- 训练中防御任务启动后能自动推进进度并生成训练日志。
- 前端工作台和任务进度页面能看到任务从 `running` 到 `success` 的状态变化。

验收：

- `POST /api/pre-defense/start/{taskId}` 可触发模拟清洗。
- `GET /api/pre-defense/progress/{taskId}` 可观察进度变化。
- `GET /api/pre-defense/cleaning-result/{taskId}` 可查看模拟清洗结果。
- `POST /api/during-defense/start/{taskId}` 可触发模拟安全训练。
- `GET /api/during-defense/training-log/{taskId}` 可查看模拟训练日志。

当前进展：

- 已新增 `DefenseTaskExecutor` 接口，训练任务服务依赖执行器接口而非具体 mock 类。
- `MockDefenseTaskExecutor` 可推进训练前/训练中任务并写入前端兼容的结果 JSON。
- 训练前/训练中任务启动前已校验数据集、算法、模型和超参数模板可用性。
- 前端任务详情已支持展示后端训练曲线和结构化失败原因。

## R2：后端依赖裁剪

目标：

- 移除 `eladmin-system` 对 `eladmin-generator` 的运行期依赖。
- 移除支付、邮件、S3 控制台依赖。
- 移除远程部署、数据库执行器相关后端模块。

验收：

- Maven 编译通过。
- 登录、权限、数据集、算法、模型、训练前/中任务接口可用。
- 不再暴露支付、邮件、S3、生成器、远程部署接口。

## R2.5：后端技术栈升级

目标：

- 将后端基线从 Spring Boot 2.7.x 升级到 Spring Boot 3.x。
- 将运行时升级到 Java 17 LTS。
- 完成 `javax.*` 到 `jakarta.*` 的迁移。
- 替换或升级 Swagger/Knife4j、Spring Security、MyBatis-Plus 等依赖到兼容版本。

验收：

- Maven 编译通过。
- 登录鉴权、权限控制、数据集、算法、模型、任务接口回归通过。
- 不再依赖 Spring Boot 2 专属 API 或不可维护依赖。

## R3：任务生命周期统一

状态：部分完成。

目标：

- 新增统一任务生命周期服务。
- 对 `PreDefense`、`DuringDefense`、`PostDefense` 的重复状态逻辑做收敛。
- 启动任务前校验数据集、算法、模型、超参数模板。

验收：

- 不可用数据集不能启动任务。
- 阶段不匹配算法不能绑定任务。
- 已运行任务不能重复启动。
- 任务失败能记录失败原因。

当前进展：

- `PreDefenseServiceImpl` 和 `DuringDefenseServiceImpl` 已补齐启动前资源校验。
- 任务失败原因已先通过现有 `cleaning_result` / `training_log` JSON 承载，暂不新增表字段。
- 统一生命周期服务尚未抽取，后续可在接入真实算法时进一步收敛状态更新和失败记录。

## R4：算法执行器接口

状态：部分完成。

目标：

- 定义算法执行器接口。
- 支持本地 Python 算法入口。
- 统一参数 schema 校验、任务输入输出路径、日志写入。

验收：

- 可以注册一个 mock 算法并完成任务状态从 `pending` 到 `success`。
- 执行日志、结果 JSON、输出 artifact 路径可查询。

当前进展：

- 已建立 `DefenseTaskExecutor` 作为当前 MVP 执行边界。
- 已约定基础结果 JSON 字段：`executor`、`stage`、`status`、`message`、`started_at`、`finished_at`、`artifacts`。
- 失败结果需额外包含 `error_code` 和 `failure_reason`。
- 正式 Python 算法入口、artifact 路径协议、参数 schema 校验仍待后续算法项目到位后实现。

## R4.5：前端技术栈升级

目标：

- 将前端从 Vue 2 + Vue CLI + Element UI 迁移到 Vue 3 + Vite + Element Plus。
- 将 Vuex 迁移到 Pinia。
- 将动态路由、权限指令、CRUD 组件迁移到 Vue 3 兼容实现。
- 保留安全训练主流程页面，放弃无关 ELADMIN 示例页面。

验收：

- `npm run build` 构建通过。
- 登录、动态菜单、数据集、算法、模型、训练前/中任务、任务进度页面可用。
- 不再依赖 Vue 2 EOL 生态。

## R5：集成训练前两类算法

目标：

- 集成 LDD。
- 集成 MetaPurifier。
- 支持风险评分、干净子集输出、清洗报告。

验收：

- 用户可选择数据集、算法、参数并启动清洗。
- 前端可展示中毒比例、子集纯度、样本数量、结果下载入口。

## R6：安全训练与评估报告

目标：

- 基于清洗数据训练安全模型。
- 生成训练曲线和评估指标。
- 输出防御报告。

验收：

- 训练任务可复现参数和输入数据版本。
- 报告包含数据清洗详情、模型指标、防御效果。
