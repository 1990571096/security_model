# 00 从一份数据走到一份报告

假设你是实验者，手里有一份已经放在服务器磁盘上的 CIFAR-10 训练数据。你想用 LDD 筛选可保留的样本，查看本轮用了哪些参数，最后把筛出的子集下载下来。本文用 `taskId=42`、`runId=r1`、`attempt=1` 贯穿全过程。真实 ID 由程序生成，第一次运行的 `runId` 是 UUID。本文所有 ID 和状态变化都是教学推演。

平台导航覆盖训练前、训练中、训练后三个阶段。以下可执行案例沿着训练前 LDD 走。第一遍按默认 `db/api` 模式阅读，Java API 和执行器在同一个 JVM 中。

## 第一步 登录并选好输入

1. 打开登录页，先取图形验证码 `GET /auth/code`，再输入用户名、密码和验证码。`POST /auth/login` 进入 `AuthController.login`，后端校验验证码、账户状态和 BCrypt 密码后签发 JWT。后续请求带上 Bearer 令牌。后端从登录身份取得用户 ID，任务建档时把它记为 `ownerUserId`。身份回答“你是谁”，归属检查回答“这条任务能否由你操作”。
2. 数据集页登记环境中的路径、名称和类型。新登记记录要验证，启用且 `parseStatus=success` 的记录才能进可用列表。管理员点验证对应 `POST /api/dataset/{datasetId}/validate`。如果登记的是 `cifar-10-batches-py`，当前后端会检查预期 CIFAR 文件可读且非空。登记的是文件位置与元数据，训练进程后续自己读样本。
3. 在任务中心选该数据集、LDD 和本次自定义参数。数据集路径会决定运行根目录与 `dataset` 参数；算法的默认超参数在算法详情页“参数配置中心”维护，新任务不再选择独立模板。创建前的参数预览会按“算法默认 → 历史模板兼容 → 本次自定义”合并并校验。

[登录 Controller](../../migration-backend/src/main/java/com/securitymodel/interfaces/web/AuthController.java) · [资源 Controller](../../migration-backend/src/main/java/com/securitymodel/interfaces/web/ResourceController.java) · [资源校验](../../migration-backend/src/main/java/com/securitymodel/application/resource/ResourceCommandService.java)

## 第二步 创建给人看的任务

用户在表单点“创建任务”，`TaskCenterView.vue` 的 `submitCreate` 调用 `POST /api/pre-defense`。浏览器发任务名、资源 ID 和参数。Java 用 `@RequestBody` 接成命令对象，重新读取资源、校验算法和参数，然后写 `sys_pre_defense`。成功响应 `201` 返回任务 ID `42`，页面打开详情。

此刻 `42` 是一份可检查的待运行任务。`status=pending`，`progress=0`，当前轮次尚未分配。现在关闭页面，重新打开任务中心还能找到同一条任务。

```text
页面表单 -> POST 创建 -> Controller -> 创建 Service -> INSERT -> 返回 taskId
```

## 第三步 确认启动，生成一次运行

用户核对详情，点击“启动任务”并确认。浏览器发 `POST /api/pre-defense/start/42`。后端锁住任务行，校验这个用户拥有任务、任务仍为 `pending`、资源和执行器配置仍可用。

同一事务内，后端分配运行 ID `r1` 与次数 `1`，为本地执行取得 GPU 租约，生成并保存执行快照、领取任务和保存派发意图。事务提交后才把本轮交给后台执行。启动响应 `200` 是命令受理，接下来可以离开页面，后台仍会推进。

可以把三个 ID 读成一句话。`42` 是实验任务，`r1` 是这次执行的身份，`1` 是这次执行的次数。`running` 已经落库时，Python 进程可能还在等待派发。

[页面](../../migration-frontend/src/views/TaskCenterView.vue) · [创建与启动](../../migration-backend/src/main/java/com/securitymodel/application/task/PreDefenseTaskCommandService.java)

## 第四步 Java 启动 Python，交换三个 JSON 文件

后台线程进入 `PythonLddTaskExecutor.invoke`。Java 将本轮 DTO 序列化为 UTF-8 的 `request.json`，再启动一个 Python 进程。`--request` 的值是请求文件的路径，`--result` 和 `--progress-file` 告诉 Python 将输出写到哪里。默认教学路径形如 `<workspace>/pre_defense-42/attempt-1/`。

```text
HTTP 请求已经结束

Java 异步线程
  写 request.json
  ProcessBuilder 启动 algorithm_entry.py
  等待期间读取 progress.json
  进程结束后读取 result.json

Python 适配器
  argparse 取路径 -> read_text -> json.loads
  将 customParams 等字段中的 JSON 文本再次解析为字典
  校验运行环境 -> 启动 VaB 训练子进程
  写进度、日志、结果与筛选产物位置
```

将文件看成一本交接记录。Java 在请求写 `runId: "r1"` 和 `attempt: 1`。Python 解出它们，再用结果字段 `run_id: "r1"` 和 `attempt: 1` 标记结果。Java 检查退出码、文件结构、状态和身份，再按 `task_id=42 AND current_run_id='r1' AND status='running'` 写回。进度更新也带运行条件。Python 的交付动作就是落文件，Java 负责持久化任务状态。

[真实文件协议](../project-implementation-guide.md#7-java-到-python文件协议) · [只需 Python 的 3 分钟练习](02-java-python.md)

## 第五步 看报告，再决定是否导出

浏览器查询任务详情和日志。运行期间由抽屉定时刷新，进入终态或切换运行会联动更新报告。`GET /api/pre-defense/report/42` 检查可读权限，然后把本轮结构化结果和资源信息拼成报告。日志接口按每个文件的字节位置读取，返回下一次读取的 offset。

真实 LDD 结果提供 `retained_clean_subset` 及可用筛选索引，CIFAR-10 训练批次也可用时，报告才有可导出选项。用户选择一个隔离比例后提交导出，拿到独立的 `exportId`。导出后台任务按索引从原训练样本中选取图片并打 ZIP。浏览器查询导出状态，成功后另发下载请求。比如 `42` 已经 `success`，导出仍可以 `pending`。ZIP 缺失时可重建，复用导出记录。

这里的选项字段叫 `isolationRatio`，它是筛选配置的隔离比例。导出内容由对应索引文件明确指定。具体数量以该索引及报告为准。

[看懂报告和 ZIP](04-report-and-export.md)

## 按顺序核对五个证据

| 时点 | 任务 42 | 当前轮次 | 找什么 |
| --- | --- | --- | --- |
| 提交创建之后 | `pending`, `0` | 无 | Network 中 `201` 与详情 ID |
| 确认启动之后 | `running` | `r1`, `1` | MySQL 任务和派发意图 |
| 适配器执行时 | `running` | `r1`, `1` | attempt 目录，请求、进度和日志 |
| 结果通过校验 | `success` | `r1`, `1` | `result.json`、数据库终态与报告 |
| 提交导出 | 仍为 `success` | 仍为 `r1`, `1` | 单独的 `exportId` 与下载状态 |

练习时先用 [已填示例工作表](task-notebook.md) 对答案。无数据库可走源码与 Python 短桩。完整短桩页面可观察创建、启动、回写和报告。真实 LDD 产物用于观察筛选索引和 ZIP。

## 合上文档复述

用一分钟完成这六句话。用户是谁？他准备的三个输入是什么？创建与启动各写了什么？Java 怎样把本轮请求交给 Python？页面怎样看到结果？最后下载的 ZIP 从哪里来？

能复述后加一个变化。假设启动检查发现脚本没配好，应该看到 `42` 仍是 `pending`，启动返回错误，本轮尚未分配。假设轮次已分配且进程失败，先看 `42/r1/1` 的 stderr 与结果，再看失败后的重试条件。同一个 `taskId` 下一次可以是 `r2/2`。

[下一课 从页面找到数据库行](01-follow-a-task.md)
