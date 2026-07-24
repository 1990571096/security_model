# 面向数据清洗的安全模型训练系统

本项目由早期网络安全竞赛项目重构而来，目标是形成一个面向数据清洗与安全模型训练的可视化平台。当前版本先保留 ELADMIN 既有工程基础，完成安全训练主链路的 MVP 闭环；后续再逐步迁移到更稳定的新技术栈，并接入真实训练前、训练中和训练后防御算法。

## 当前定位

- 管理数据集、模型、算法和超参数模板。
- 创建并启动训练前防御任务，记录清洗结果。
- 创建并启动训练中防御任务，记录训练日志、损失曲线和精度曲线。
- 通过统一执行器接口为后续真实算法接入预留边界。
- 按 SDD 文档驱动方式推进重构，降低返工风险。

## 技术栈

当前 MVP 为了减少重构风险，继续复用原项目技术栈：

- 后端：Spring Boot 2.7.18、Spring Security、JWT、MyBatis-Plus、MySQL、Redis。
- 前端：Vue 2.7、Vue CLI、Element UI。
- 构建：Maven、npm。

目标技术栈已在 SDD 中记录，后续稳定迁移方向为 Spring Boot 3、Vue 3 等成熟主流技术栈。

## 项目结构

```text
.
├── docs/sdd/                  # SDD 文档与运行手册
├── eladmin/                   # 后端工程
│   └── eladmin-system/        # 后端启动模块与安全训练业务模块
├── eladmin-web/               # 前端工程
└── sql/                       # 数据库初始化与 MVP seed 脚本
```

## MVP 内置算法

当前算法由 `MockDefenseTaskExecutor` 模拟执行，用于验证平台流程、接口协议和前端展示。

训练前防御：

- 基线-统计异常清洗
- 基线-低置信样本过滤
- 基线-IQR四分位清洗
- 基线-IsolationForest异常清洗

训练中防御：

- 基线-鲁棒训练
- 基线-标签平滑训练
- 基线-对抗训练占位

真实算法接入应实现后端统一的 `DefenseTaskExecutor` 边界，避免业务 CRUD 代码直接依赖具体算法实现。

## 本地环境

推荐使用本机 MySQL 和 Redis：

- MySQL：`localhost:3306`
- 数据库：`eladmin`
- 用户名：`root`
- 密码：`123456`
- Redis：默认本地服务，后端开发环境使用 Redis DB `1`

首次初始化数据库：

```sql
SOURCE sql/eladmin.sql;
SOURCE sql/security_model_mvp_seed.sql;
```

已有开发库只需补充 MVP 数据：

```sql
SOURCE sql/security_model_mvp_seed.sql;
```

## 启动后端

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin
mvn -pl eladmin-system -am spring-boot:run -Denforcer.skip=true
```

默认后端地址：

- API 服务：`http://localhost:8000`
- 接口文档：`http://localhost:8000/doc.html`

## 启动前端

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin-web
$env:NODE_OPTIONS='--openssl-legacy-provider'
npm run dev -- --host 0.0.0.0
```

默认前端地址：

- `http://localhost:8014/`

默认登录账号：

- `admin / 123456`

## 验证命令

后端编译：

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin
mvn -pl eladmin-system -am compile -Denforcer.skip=true
```

前端构建：

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin-web
$env:NODE_OPTIONS='--openssl-legacy-provider'
npm run build:prod
```

旧 Vue CLI/Webpack 在 Node 17+ 环境下需要 `--openssl-legacy-provider`。构建时出现 asset size 或 Browserslist 过期提示属于当前旧前端栈的已知警告，不阻塞 MVP 联调。

## SDD 文档

重构迭代以 `docs/sdd` 为准：

- `docs/sdd/README.md`：文档索引。
- `docs/sdd/04-refactor-backlog.md`：重构任务与验收标准。
- `docs/sdd/05-decisions.md`：设计决策记录。
- `docs/sdd/06-mvp-runbook.md`：MVP 运行、联调和冒烟测试记录。
- `docs/sdd/07-executor-contract.md`：算法执行器契约。
- `docs/sdd/08-local-runtime-notes.md`：本地运行环境记录。
- `docs/sdd/09-project-status.md`：项目整体目标、当前进度、已完成能力和后续待办。
- `docs/sdd/10-vab-integration.md`：VaB 训练前防御算法适配、参数和产物约定。

## 当前限制

- 内置 baseline 仍由 mock 执行器模拟；VaB 的 LDD 与 7 个可独立运行的比较基线已接入 Python 适配器，但当前阶段不以真实训练结果作为验收依据。
- 训练后防御暂未作为 MVP 主链路开放。
- 前端和后端仍保留部分 ELADMIN 历史模块与技术债，后续按 SDD 逐步裁剪和迁移。

## 原始项目来源

本项目基于 ELADMIN 工程基础改造。原项目地址：

- GitHub: `https://github.com/elunez/eladmin-mp`
- Gitee: `https://gitee.com/elunez/eladmin-mp`

## VaB 真实算法接入

LDD（论文提出方法）以及 VS-DCM、VS-ABL、VS-Meta-Sift、VS-MI-DCM、
VS-Spectral Signature、VS-Spectre、VS-Telltale 七个可独立运行的对比基线，均已通过
`algorithm-runtime/vab/algorithm_entry.py` 接入 Python 执行器契约。VaB 原始代码、
数据集、检查点和实验输出仍保留在外部算法目录，不会复制进本仓库。

当前完成的是平台注册、配置映射、任务隔离、结果协议和前端结果展示。真实训练轮次、
数据集和论文实验配置由后续迭代确定，平台不会将当前静态接入状态标记为实验结论。

启动使用真实 VaB 算法的后端前，需要设置：

```powershell
$env:VAB_ROOT='F:\Study\postgraduate\1下\VB backdoor defence\VaB-main'
$env:SECURITY_MODEL_PYTHON_SCRIPT='F:\Study\postgraduate\毕业论文\security_model\algorithm-runtime\vab\algorithm_entry.py'
```

然后用 Python 执行器 profile 启动后端：

```powershell
cd F:\Study\postgraduate\毕业论文\security_model\eladmin
mvn -pl eladmin-system -am spring-boot:run -Denforcer.skip=true "-Dspring-boot.run.profiles=dev,python-executor"
```

详细的参数映射、运行约束和验收标准见
`docs/sdd/10-vab-integration.md`。
