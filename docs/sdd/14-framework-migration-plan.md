# 框架迁移实施计划与验收结果

更新时间：2026-10-26

## 1. 迁移目标

将当前 Spring Boot 2.7 + Vue 2 的旧 ELADMIN 基座，逐步迁移为 Java 17、
Spring Boot 3、Spring Security 6、MyBatis-Plus Spring Boot 3 Starter、Vue 3、
Vite、Element Plus、Pinia 和 Vue Router 4。

迁移期间保持 LDD 的任务状态、`run_id`、`attempt`、参数快照、报告、日志和清洗数据集导出协议不变。

## 2. Foundation 阶段已完成

已新增两个独立目录：

```text
migration-backend/
migration-frontend/
```

新后端已建立 Java 17、Spring Boot 3.5、Spring Security 6、JWT、MySQL、Redis 和
MyBatis-Plus 运行基线；Redis 仅用于可重建资源目录缓存，Redisson 和消息队列未接入。前端已建立 Vue 3、Vite、
Element Plus、Pinia、Vue Router 4、登录页、工作台、数据集资源、算法资源、
LDD 任务中心和训练中防御演示页面。

旧 `eladmin` 和 `eladmin-web` 不删除，继续作为业务回归和回滚基线。新后端默认使用
`8100` 端口，新前端默认使用 `8014` 端口。

## 3. 迁移阶段与当前结果

### R1：后端领域迁移

1. 统一响应、异常码、分页和当前用户上下文。
2. 迁移登录、验证码、RSA 密码解密和 JWT。
3. 迁移 Dataset、Algorithm、Hyper 资源。
4. 迁移 PreDefense 任务创建、启动、取消、重试和状态机。
5. 迁移 TaskProgress、执行池状态和日志增量读取。
6. 迁移执行快照、运行环境、报告和产物预览。
7. 迁移 CleanSubsetExport 异步导出、重试、恢复和下载。

当前 R1 已完成：认证、资源查询、任务命令、任务观测、报告、日志、产物预览和清洗子集
异步导出均由新后端原生处理。LDD 只通过统一 Python 执行器协议调用外部算法运行时，
不再依赖旧后端业务控制器。

### R2：前端领域迁移

1. 迁移 Axios 请求封装和 token 处理。
2. 迁移登录、验证码和权限路由。
3. 迁移数据集、算法和超参数管理。
4. 迁移 LDD 任务创建和动态参数表单。
5. 迁移任务进度、日志和失败重试。
6. 迁移报告、参数快照、产物预览和清洗子集导出。

当前 R2 已完成当前展示范围：Vue 3 前端已接入登录、工作台、数据集资源、算法资源、
LDD 任务创建、启动、取消、重试、报告、日志、PNG 预览和按保留索引导出。

### R3：旧模块隔离

旧 `eladmin` 和 `eladmin-web` 暂不物理删除，作为回退和对照工程保留。迁移版主流程
不依赖旧业务控制器；后续是否裁剪无关模块取决于论文答辩和部署需求。

## 4. 兼容策略

迁移阶段保留 `/auth/**`、`/api/dataset/**`、`/api/algorithm/**`、
`/api/pre-defense/**` 和 `/api/task-progress/**`。新接口可使用 `/api/v1/**`，
但只有新前端回归完成后才切换默认调用路径。

迁移期间复用当前 MySQL 表结构，不修改实验数据字段。LDD 的 Python 入口和
`result.json` 协议保持不变；新后端负责执行编排，外部 Python 负责算法计算。

任一阶段出现回归问题时，切回旧后端 `8000` 和旧前端 `8013`，不删除新目录和迁移记录。

## 5. 迁移验收标准

- 新后端能独立启动并返回健康检查和版本信息。
- 新前端能独立构建并访问登录、工作台、资源页、任务中心和扩展演示页。
- 新旧工程目录互不依赖。
- 不写入仓库密钥和本机数据库密码。
- 新前端登录请求不再使用本地假 token。

迁移验收已覆盖登录、LDD 任务闭环、失败重试、迟到回调保护、参数快照、报告和异步
导出。后续工作转为迁移版质量门禁、可靠任务执行、查询优化、可观测性和论文正式实验。

## 6. 迁移后质量门禁

- 后端：`mvn -B -f migration-backend/pom.xml verify`，运行迁移版测试。
- 前端：在 `migration-frontend/` 执行 `npm ci` 和 `npm run build`。
- CI 检查迁移版后端和前端；普通构建不依赖本机数据库、Redis 或 GPU，MySQL 集成测试在 CI MySQL 8.4 service 中运行。
- CI 中的后端验证包含应用 YAML 解析测试；它只校验配置语法，不代表运行环境、凭据或数据库连通性已经验证。
- 新增领域功能时同步更新对应 SDD 的实现状态、验收标准和测试说明；旧版 ELADMIN 测试不计入迁移版覆盖。
