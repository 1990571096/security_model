# 任务追踪工作表

第一次对照示例栏，来源写“源码模拟”。第二次带着 Network、只读 SQL 和 attempt 目录填自己的值。记录前遮盖令牌、密钥和真实用户目录。

| 我看到的东西 | 已填示例 | 我的运行记录 | 去哪里找 |
| --- | --- | --- | --- |
| 数据集 ID 与验证状态 | 示例 `7` / `success` |  | 数据集页 |
| 创建请求方法、路径、状态码 | `POST /api/pre-defense` / `201` |  | Network |
| 启动请求方法、路径、状态码 | 用户确认后 `POST /api/pre-defense/start/42` / `200` |  | Network |
| `taskId` | `42` |  | 创建响应 |
| `currentRunId` 与 `currentAttempt` | `r1` / `1` |  | 任务详情或隔离库 |
| 各次业务状态 | `pending → running → success` |  | 任务详情 / 查询 |
| 派发模式与意图状态 | `db` / `pending → submitting → submitted` |  | 配置 / 派发监控 |
| `request.json` 中的自定义参数 | 报文形如 `"customParams": "{\"num_epochs\":1}"`，实际值看快照 |  | attempt 目录 |
| `result.json` 的 `run_id` 与 `status` | `r1` / `success` |  | attempt 目录 |
| 日志的一次 offset 变化 | 假设返回 120 字节，下一次从 `120` 开始取 |  | 日志响应 |
| 报告是否有可用子集 | 短桩通常只有 marker，当前选项不可用 |  | 报告与筛选索引 |
| 导出请求传哪个 ID | 父任务 `42` 和保留比例 |  | 导出 POST 的路径和查询参数 |
| 查询与下载用什么 ID | 导出记录 `701` |  | 导出响应和后续 GET |
| ZIP 是否仍可下载 | 对比导出状态、`downloadReady` 和下载响应 |  | 报告的导出项与下载接口 |
| 一条本次执行的测试结果 | 填测试名、断言和输出 |  | 命令输出 |

用自己的话填四句。

1. 用户点击提交时请求 __。核对任务后点启动并确认，浏览器再请求 __。
2. Java 把 __ 写到 __，把 __ 交给 Python。
3. Python 写 __，Java 读取后用 __ 条件更新数据库。
4. 如果 __ 出错，先检查 __，再沿 __ 定位。

<details>
<summary>用来核对的示例</summary>

用户点提交，浏览器创建 `pending` 任务。用户核对后确认启动，页面用创建时返回的 `taskId` 发送启动命令。Java 把 `DefenseTaskRequest` 写成 `request.json`，把绝对路径作为 `--request` 的值。Python 写 `result.json`，Java 核对 `run_id` 与 `attempt`，再按任务、当前轮次和 `running` 更新数据库。若启动后一直运行中，先看调度意图，再看当前 attempt 的进程记录、进度和 stderr。

</details>
