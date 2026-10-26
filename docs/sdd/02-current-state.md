# 当前状态分析

> 本文记录的是旧 ELADMIN 工程的历史基线，不代表迁移版当前运行技术栈。迁移版现状以
> [09-project-status.md](09-project-status.md) 和 [11-platform-implementation.md](11-platform-implementation.md) 为准。

## 技术栈

- 历史后端：Spring Boot 2.7.18、Java 8、MyBatis-Plus、Spring Security/JWT、Redis、MySQL、Quartz。
- 前端：Vue 2.7、Vue CLI 3、Element UI、Vuex、Axios、ECharts。
- 项目基座：ELADMIN 后台管理系统。

## 目录结构

- `eladmin/eladmin-common`：通用配置、异常、工具、基础实体。
- `eladmin/eladmin-logging`：系统日志。
- `eladmin/eladmin-system`：系统入口、认证授权、业务模块、运维模块、定时任务。
- `eladmin/eladmin-tools`：邮件、支付、对象存储、本地存储等第三方工具。
- `eladmin/eladmin-generator`：CRUD 代码生成器。
- `eladmin-web`：Vue 前端。
- `sql`：初始化 SQL。

## 可复用业务模块

- `Dataset`：已有数据集元数据、上传、解析状态、统计字段。
- `Algorithm`：已有算法阶段、类型、实现路径、默认配置、参数 schema。
- `Hyper`：已有超参数模板。
- `Model`：已有模型元数据、自定义模型文件、解析状态、可用状态。
- `PreDefense`：已有训练前防御任务字段，适合作为数据清洗任务的初始承载。
- `DuringDefense`：已有训练中防御任务字段，可作为训练任务/训练中防御的重构基础。
- `PostDefense`：已有训练后防御任务字段，当前保留为扩展模块。
- `TaskProgress`：已有跨阶段任务进度聚合能力。

## 当前主要问题

1. 领域模块仍挂在 `modules.system` 下，和用户、角色、菜单等后台系统管理模块混杂。
2. 迁移版 LDD 执行器是单机本地 Python 进程模式，尚未演进为独立 worker 或分布式调度。
3. `PreDefense`、`DuringDefense`、`PostDefense` 三套任务模型重复字段较多，后续应抽象统一任务生命周期。
4. LDD 任务的数据集、算法和超参数关联校验已实现；其他历史任务模块仍有待统一治理。
5. 初始化 SQL 中存在与论文目标无关的数据样例，例如文本、表格、通用支付/运维表。
6. 前端仍保留 ELADMIN 示例看板、工具页、运维页、生成器页，目标用户路径不集中。
7. 当前仍需在稳定业务边界后裁剪无关模块并迁移到目标技术栈。

## 初步裁剪分组

### 保留

- 登录、权限、用户、角色、菜单。
- 数据字典。
- 系统日志。
- 数据集管理、算法管理、模型管理、超参数管理。
- 训练前防御、训练中防御、任务进度。

### 暂时保留但降级

- 训练后防御：作为扩展能力保留，短期不作为主流程入口。
- Quartz：后续可能用于异步任务扫描和状态恢复，暂时保留。
- 服务监控：仅用于开发调试，不放入论文系统主导航。

### 待裁剪

- `eladmin-tools` 中的 Alipay、Email、S3 控制台。
- `eladmin-generator` 及前端生成器页面。
- `modules.maint` 远程部署、服务器、数据库执行器。
- 前端 `tools`、`generator`、`maint`、`nested`、示例图表页。

## 第一阶段不直接删除的原因

当前菜单来自数据库动态路由，后端模块、前端视图和 SQL 初始化脚本耦合较多。直接删除文件会导致已有菜单指向不存在组件，或者 Maven 模块依赖断裂。第一阶段先确定裁剪清单和首页入口，第二阶段通过 SQL 菜单、前端路由、后端依赖三处同步裁剪。
