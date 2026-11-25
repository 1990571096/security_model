# 03 画出状态和失败分支

**目标**是能用一个具体例子解释并发和恢复。

## 练习一：用纸笔模拟迟到结果

1. 画三列 `taskId`、`current_run_id`、`status`。写入 `42, r1, running`，旁边记 `attempt=1`。
2. 假定 r1 失败，失败后重试，写成 `42, r2, running`。记 `attempt=2`。在 [命令服务](../../migration-backend/src/main/java/com/securitymodel/application/task/PreDefenseTaskCommandService.java) 搜 `retry` 和 `nextAttempt`。
3. 让 r1 的结果晚到，代入 [Mapper](../../migration-backend/src/main/java/com/securitymodel/infrastructure/persistence/mapper/PreDefenseTaskMapper.java) 的 `updateResultForRun`。先填 `task_id=42`，再填 `current_run_id='r1'`，返回影响行数为 0。
4. 将 r2 改成 `canceled`。代入 r2 的 `finishForRun`，这次身份匹配，`status='running'` 条件不匹配，仍影响 0 行。

## 练习二：找出三个防重复位置

| 输入 | 到哪里找 | 记录下来 |
| --- | --- | --- |
| 两条相同 HTTP 请求 | [RequestIdempotencyService](../../migration-backend/src/main/java/com/securitymodel/application/task/RequestIdempotencyService.java) | 谁拿到键、如何对比请求摘要和返回历史响应 |
| 同一所有者用不同请求键启动同一个任务 | `lockOwned` 和 `claimForExecution` | 哪个请求等锁，哪个状态条件保证只领取一次 |
| 同一 MQ 消息再次投递 | [DispatchMessageHandler](../../migration-backend/src/main/java/com/securitymodel/application/task/DispatchMessageHandler.java) | `messageId` 消费账本、intent 与当前轮次如何配合 |

## 练习三：执行有取消和超时的测试

在根目录运行。测试使用短进程和临时目录。

```powershell
mvn -B -f migration-backend/pom.xml '-Dtest=TaskDispatchCoordinatorTest,PythonLddTaskExecutorProcessTest' test
```

打开 [进程测试](../../migration-backend/src/test/java/com/securitymodel/infrastructure/executor/PythonLddTaskExecutorProcessTest.java)，找到 `timeoutKillsTheWholeShortProcessTreeAndReleasesOnlyAfterExit`。定位超时与取消是怎么触发的，再看测试如何验证租约在进程退出之后才释放。超时用例会输出预期的错误日志，测试结论看 Maven 汇总。

## 自测

**API 已经返回运行中，却找不到进程，你如何定位？** 先查派发意图状态。提交到派发之间出现空窗时，任务已是 `running`，意图可能是 `pending`。`recoverAtStartup` 和 `scanPending` 检查标记、身份和快照，在条件允许时补派发。已有 accepted 或进程证据的轮次先处理当前进程和槽位。对照 [恢复协调器](../../migration-backend/src/main/java/com/securitymodel/application/task/DispatchRecoveryCoordinator.java)。

**有租约过期时间，为什么还要检查进程树？** 数据库里租约有效期到期后，操作系统进程仍可能在用 GPU。确认原进程退出之前，执行器可能隔离槽位。沿 `executeAndWait` 的 `finally` 找 `ensureGone` 与 `markBlocked`。

## 推演三个中断点

| 中断点 | 中断前留下的证据 | 后续从哪里接手 |
| --- | --- | --- |
| 领取事务回滚了 | `42` 原有的 `pending` 保留。本轮状态和意图没有提交 | 创建仍成功，修复错误后可以再次启动 |
| 提交成功，还没派发就重启 | `42/r1/running`、快照和待派发意图 | 恢复协调器检查身份、有效期和进程证据 |
| 已交给执行端，进程状态不明 | 可能已有 accepted 或进程记录 | 先辨认旧进程是否存在。无法验证退出时，留给隔离与恢复策略处理 |

在白板上补一条“租约释放”线。它的前提是安全的物理进程状态。进程状态和任务终态分别画出来。

[下一课：报告与导出](04-report-and-export.md)
