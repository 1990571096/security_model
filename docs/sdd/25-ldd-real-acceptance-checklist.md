# 25. 真实 LDD 验收准备清单

更新时间：2026-11-18
状态：**环境预检通过（带 1 项内存警告）；真实 1 epoch 验收成功（任务 `2122913324293771266`）；正式 5 epochs 未启动**

本文档把“准备一次真实 LDD 实验”从口头检查固化为可重复步骤。它不替代论文实验方案，也不把工程验证写成论文结论。当前结论到“环境预检、非训练验收和 1 epoch 真实执行链路验收”；正式 5 epochs 必须另定运行窗口并由实验负责人确认配置。

## 1. 本轮范围与非目标

### 1.1 已做

- 核对 Python / PyTorch / CUDA / GPU，选定唯一执行器解释器。
- 核对 VaB 数据目录、CIFAR-10 批次文件、VaB 锁文件和受控工作/结果目录。
- 建立独立验收库 `security_model_ldd_20261118`，不触碰阶段 3 部署库、恢复库和原 `eladmin`。
- 通过真实 API 验证数据集、算法、执行器状态、调度就绪和 GPU 空闲槽位。
- 创建并核对 1 条 `pending` 任务；不启动、不领取租约、不产生 run/attempt/快照/outbox。
- 新增可重复环境预检脚本 `scripts/dev/Test-LddAcceptanceEnvironment.ps1`。

### 1.2 未做

- 未启动真实 LDD，未在 RTX 2060 上执行 128 batch / 5 epochs 训练。
- 未测显存峰值、单 epoch 耗时、总耗时、产物体积和真实指标。
- 未验证 `pending -> running -> success/failed` 的真实训练链路，未验证取消/超时/重试的真实 GPU 行为。
- 未调用真实外部模型，未做生产库迁移。
- 未把 2026-08-19 的旧工程验证产物当作论文正式实验结论。

## 2. 已冻结的环境事实

| 项 | 当前事实 |
| --- | --- |
| 执行器解释器 | `D:\Anaconda\python.exe` |
| Python | 3.9.7（CPython） |
| PyTorch | 2.1.1+cu118 |
| CUDA | 11.8，`torch.cuda.is_available()=True` |
| GPU | NVIDIA GeForce RTX 2060，6144 MiB，驱动 610.91 |
| 关键依赖 | numpy 1.21.5、scipy 1.7.1、scikit-learn 0.24.2、Pillow 9.4.0、opencv 4.5.5.62、h5py 3.6.0 |
| VaB 根 | `F:\Study\postgraduate\1下\VB backdoor defence\VaB-main` |
| 数据根 | `...\VaB-main\dataset`，`cifar-10-batches-py` 批次文件齐全且非空 |
| 受控工作目录 | `F:\security-model-data\ldd-acceptance-20261118\workspace` |
| 受控结果根 | `F:\security-model-data\ldd-acceptance-20261118\results` |
| 磁盘 | F: 约 75 GB free；C: 约 9.6 GB free；D: 约 110.7 GB free |
| 内存 | 物理内存约 15.4 GB，预检时空闲约 2.3–2.5 GB（**警告**） |
| 验收库 | `security_model_ldd_20261118`，48 表 |
| 数据集 | ID 6，`VaB CIFAR-10 Runtime Root`，路径验证 `success`，样本统计仍为 NULL |
| 算法 | ID 14，`LDD (VaB)`，`vab://pre-defense/ldd` |
| 验收任务（1 epoch） | `2122913324293771266`，`success`，`progress=100`，run `af5f081a-6661-462c-b0bc-c70ea003597a` |
| 正式任务（5 epochs） | `2122901116713205761`，仍为 `pending`，未启动 |

### 2.1 不使用的环境

- PATH 中的 `python` 是沙箱运行时 Python 3.13.18，没有 torch，不能作为执行器。
- `D:\Anaconda\envs\pytorch\python.exe` 是 Python 3.6.13 / torch 1.10.2，但 numpy DLL 加载失败，torch 导入崩溃；不要重试。
- `D:\Anaconda\envs\Junior` 无 torch；`Meta-sift` 的 torch 1.13.0+cu116 不是本轮选定组合。

### 2.2 内存警告的含义

预检时空闲物理内存约 2.3–2.5 GB，低于脚本建议的 4 GB。它不一定导致失败，但会放大 CUDA OOM、Python 进程被杀和桌面卡顿的风险。正式训练前应关闭无关应用，并在训练中记录 `nvidia-smi` 显存与系统内存；不能因为预检 `ready_with_warnings` 或 1 epoch 通过就宣称 6 GB 显存足以跑完整 5 epochs。

## 3. 非训练验收步骤

### 3.1 环境预检（可重复）

在仓库根执行：

```powershell
Set-Location 'F:\Study\postgraduate\毕业论文\security_model'
& pwsh -NoProfile -File .\scripts\dev\Test-LddAcceptanceEnvironment.ps1 `
  -PythonExe 'D:\Anaconda\python.exe' `
  -VabRoot 'F:\Study\postgraduate\1下\VB backdoor defence\VaB-main' `
  -Workspace 'F:\security-model-data\ldd-acceptance-20261118\workspace' `
  -ProcessGuardWorkspace 'F:\security-model-data\ldd-acceptance-20261118\workspace' `
  -ResultRoot 'F:\security-model-data\ldd-acceptance-20261118\results' `
  -JsonOutput 'tmp\ldd-acceptance-environment.json'
```

脚本检查：解释器、torch/CUDA、依赖导入、VaB 源文件、CIFAR 批次文件、磁盘、内存、VaB 锁、工作/结果目录写权限、`SECURITY_MODEL_EXECUTOR_WORKSPACE` 与 `SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE` 是否一致，并执行一次 `algorithm_entry.py --validate-only`。退出码 0 表示没有硬错误；`status=ready_with_warnings` 表示只有警告。

2026-11-18 实测结果：加入 guard 一致性检查后重跑仍为 `status=ready_with_warnings`、`errors=0`、`warnings=1`，唯一警告是空闲内存约 2.3 GB。证据：

- `tmp\ldd-acceptance-environment-final.json`
- `tmp\ldd-acceptance-environment-final.log`
- `tmp\ldd-acceptance-environment.json`（加入 guard 检查前的首次快照）
- `F:\security-model-data\ldd-acceptance-20261118\workspace\validate-only-result.json`
- `F:\security-model-data\ldd-acceptance-20261118\workspace\validate-only.stdout.log`
- `F:\security-model-data\ldd-acceptance-20261118\workspace\validate-only.stderr.log`

`validate-only` 的结论只证明参数可解析、LDD 脚本可生成任务副本，不证明可以完成训练。

### 3.2 验收库与资源

独立库 `security_model_ldd_20261118` 已由 `InitializeSecurityModelDatabase.ps1` 初始化，未覆盖任何已有库：

- `tables=48`
- `dataset_count=6`；初始化时全部为 `pending`
- `LDD (VaB)` 算法 ID 14
- 初始化日志 `tmp\ldd-acceptance-init.log`

2026-11-18 通过 API `POST /api/dataset/6/validate` 返回：

```json
{"parseStatus":"success","parseMessage":"路径验证通过：存在且可读"}
```

该接口只检查路径存在且可读，**不解析样本**；`sampleCount/featureCount/classCount` 仍为 NULL。验收报告不得写“已解析 50000 样本/3072 特征/10 类”，除非另行执行真实解析并记录输出。

### 3.3 后端与调度就绪

验收栈曾在 `18300` 以 `db` 模式启动，pid 38256；启动前已在调用 shell 设置执行器变量：

```powershell
$env:SECURITY_MODEL_EXECUTOR_PYTHON_EXECUTABLE = 'D:\Anaconda\python.exe'
$env:SECURITY_MODEL_EXECUTOR_PYTHON_SCRIPT = 'F:\Study\postgraduate\毕业论文\security_model\algorithm-runtime\vab\algorithm_entry.py'
$env:SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE = 'F:\security-model-data\ldd-acceptance-20261118\workspace'
$env:SECURITY_MODEL_EXECUTOR_WORKSPACE = 'F:\security-model-data\ldd-acceptance-20261118\workspace'
$env:SECURITY_MODEL_EXECUTOR_PYTHON_TIMEOUT_SECONDS = '3600'
$env:SECURITY_MODEL_VAB_RESULT_ROOT = 'F:\security-model-data\ldd-acceptance-20261118\results'
$env:VAB_ROOT = 'F:\Study\postgraduate\1下\VB backdoor defence\VaB-main'
```

启动脚本本身不设置这些变量；变量必须由启动 shell 继承，并在启动日志中核对。`SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE` 负责创建 attempt 目录，`SECURITY_MODEL_EXECUTOR_WORKSPACE` 是进程记录的受控根；两个变量必须指向同一目录（或后者包含前者），否则执行器会在启动 Python 前以 `PROCESS_RECORD_FAILED` 失败。实测：

- `/actuator/health/liveness` UP
- `/actuator/health/readiness` UP
- `GET /api/observability/dispatch`：`mode=db`、`role=api`、`dispatchReady=true`、outbox/consumption 均为 0、`runningTasks=0`
- `GET /api/task-progress/executor-status`：pool core 2 / max 4 / queue 20、`acceptingTasks=true`、`runningTaskCount=0`、`gpuSlot.state=idle`
- `GET /api/dataset/available?datasetType=image` 只返回数据集 6
- `GET /api/algorithm/14` 返回 `LDD (VaB)` 和默认 128 batch / 5 epochs / SIG / all2one / 0.1 / seed 123

证据：`tmp\ldd-acceptance-api-evidence.json`、`tmp\ldd-acceptance-start.log`、`tmp\deploy-stack\security_model_ldd_20261118\db\api.log`。

### 3.4 创建任务但不启动

通过 `POST /api/pre-defense` 创建任务 `2122901116713205761`：

- 名称：`LDD acceptance preflight 20261118`
- `datasetId=6`、`algorithmId=14`
- 参数：`dataset=cifar10`、`trigger_type=SIG`、`trigger_label=0`、`poisoned_mode=all2one`、`poisoned_portion=0.1`、`batch_size=128`、`num_epochs=5`、`lr_poison=0.001`、`r=0.75`、`alpha1=0.1`、`alpha2=0.5`、`seed=123`
- 状态 `pending`、`progress=0`、`ownerUserId=1`

启动后核对数据库：

```sql
SELECT task_id,status,progress,current_run_id,current_attempt,execution_snapshot
FROM security_model_ldd_20261118.sys_pre_defense
WHERE task_id = 2122901116713205761;

SELECT COUNT(*) FROM security_model_ldd_20261118.sys_task_dispatch_intent
WHERE task_id = 2122901116713205761;

SELECT COUNT(*) FROM security_model_ldd_20261118.sys_dispatch_outbox
WHERE task_id = 2122901116713205761;

SELECT slot_id,task_id,run_id,attempt,acquired_at,released_at,blocked_at
FROM security_model_ldd_20261118.sys_gpu_lease;
```

实测结果：`pending/0`，`current_run_id=NULL`、`current_attempt=NULL`、`execution_snapshot=NULL`，意图 0、outbox 0，`default-gpu-0` 各任务字段均为 NULL。这说明“创建任务”没有触发训练、租约或派发。

验收栈随后通过 `Stop-SecurityModelStack.ps1` 正常停止，停止前 `running=0`；证据 `tmp\ldd-acceptance-stop.log` 和 `tmp\deploy-stack\security_model_ldd_20261118\db\state-stopped-20261118-115451710.json`。

### 3.5 真实 1 epoch 验收结果（2026-11-18）

第一次尝试在启动子进程前失败：任务 `2122910425962569730` 因只设置 `SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE`、未设置 `SECURITY_MODEL_EXECUTOR_WORKSPACE`，`AttemptProcessRegistry` 抛出“未配置 executor workspace”，任务以 `PROCESS_RECORD_FAILED` 结束，没有 Python 子进程。该失败也暴露出旧清理逻辑在 `process == null` 时会错误隔离 GPU 槽位；已修复为先判断子进程是否真的启动，未启动则直接释放租约，并新增 `PythonLddTaskExecutorCleanupOrderTest` 回归用例。

修复并重建 jar 后，第二次 1 epoch 任务 `2122913324293771266` 实测：

| 项 | 结果 |
| --- | --- |
| 参数 | `num_epochs=1`、`batch_size=128`、`SIG`、`all2one`、`poisoned_portion=0.1`、`seed=123` |
| 时间 | 12:42:33 启动，12:44:58 任务实际结束；12:45:03 轮询观察到 `success`；实际墙钟约 2 分 25 秒，LDD 自报 1.79 分钟 |
| 执行阶段 | `pending -> running(10) -> running(12) -> success(100)` |
| 训练输出 | 注入 5000 张中毒图 + 45000 张干净图；`clean_loss_avg≈0.4564`、`poison_loss_avg≈0.0036` |
| LDD 指标 | `clean_purity`：5%–40% 为 100.00%，45%–60% 为 99.98%–99.99% |
| 产物 | `0.1.pth` 42.69 MB、12 个 `selected_indices_*.npy`、`execution.log` 1967 B、`generated_LDD.py` 16212 B |
| 收尾 | 无残留 `python.exe`；GPU 0 MiB / 0%；租约 `released_at=12:44:57.730`、`blocked_at=NULL` |

产物目录：`F:\security-model-data\ldd-acceptance-20261118\results\20261118-124233-ldd-LDD-short-cycle-20261118-124232`；API/报告/日志/执行器快照：`tmp\ldd-short-run-real2-1epoch\run-summary.json`；启动日志 `tmp\ldd-real2-start.log`。

**口径**：1 epoch 成功只证明执行链路、租约、子进程、日志、报告和产物可用；单 epoch 指标不能作为论文效果结论，正式 5 epochs 仍需实验负责人确认配置和运行窗口。

### 3.5.1 运行脚本的启动与验收判定

`Invoke-LddAcceptanceRun.ps1` 已收紧为“先校验、再显式启动、最后分开判定”：

- 不传 `-Start` 时只读取任务、校验 `pending`、`datasetId`、`algorithmId` 和服务端 `customParams`，不调用 `start`；`-CreateOnly`/`-NoStart` 同样不启动。
- 复用 `-TaskId` 时，`-Epochs` 必须与服务端 `num_epochs` 一致；不传 `-Epochs` 时使用服务端值。创建新任务必须显式传 `-Epochs`。
- 汇总 JSON 分开记录 `task_status` 与 `acceptance`。`acceptance.state` 可取 `not_started`、`preflight_failed`、`passed`、`evidence_incomplete`、`task_failed`、`not_terminal`。
- 只有任务 `success` 且报告、日志、`taskId/runId/attempt`、非空产物、实际轮数、GPU 空闲、无运行任务、dispatch 就绪全部通过，脚本才退出 `0`；任务成功但证据不全退出 `4`，任务失败退出 `1`，超时退出 `3`，启动前校验失败退出 `2`。
- 产物检查先过滤空项，再逐项核验：模型、日志、`selected_indices_*.npy`、`generated_LDD.py` 必须存在且非空；文件修改时间必须落在本次 `started_at/finished_at` 窗口内，旧 attempt 的旧时间戳不得通过。
- `effective_config.num_epochs=5` 只是配置值。脚本从日志中的 `Epoch: 0` 到 `Epoch: 4` 统计实际完成轮次；缺轮、重轮或没有 `Epoch:` 行都判失败。LDD 的 5 轮口径为 `First Warmup Net2` 加 4 条 `Epoch` 记录。
- 报告和日志必须同时给出相同 `taskId`、`runId`、`attempt`；报告 `taskInfo.currentRunId/currentAttempt` 与 `cleaningResult.run_id/attempt` 也必须一致。
- 设置 `SECURITY_MODEL_EXECUTOR_WORKSPACE` 后，脚本按工作区匹配残留 `python.exe` 并判失败；`-SkipArtifactFileCheck` 仍会检查 API 日志中的轮次证据。
- 假 HTTP 回归 `scripts/dev/Test-LddAcceptanceRun.ps1` 覆盖“不传 `-Start` 不启动、参数不一致拒绝、正常 success、报告缺失、非 pending、空模型项、0 字节模型、日志缺失、旧产物时间、实际轮数不足、模型缺失”，不占 GPU。

### 3.6 正式训练前必须补的检查

以下项目本轮未执行，不能打勾为已完成：

- [ ] 由实验负责人确认正式配置：数据集版本、攻击设置、`batch_size`、`num_epochs`、`seed`、LDD 参数和指标口径。
- [ ] 关闭无关 GPU/CPU 进程，确认 `nvidia-smi` 无计算进程占用；保留启动前快照。
- [ ] 确认空闲物理内存不低于 4 GB，或明确接受 OOM 风险并准备失败重试。
- [ ] 在启动 shell 设置全部执行器变量，并核对 API 进程环境或启动日志。
- [x] 用一个短周期配置（1 epoch）验证 `pending -> running -> success`、GPU 租约、日志和产物；任务 `2122913324293771266` 已通过。短周期通过不等于正式 5 epochs 通过。
- [ ] 正式运行前记录任务的参数快照和数据集路径摘要，运行中按固定间隔记录 GPU 显存、系统内存、epoch 进度和日志 offset。
- [ ] 运行结束后核对 `.pth`、`selected_indices_*.npy`、`execution.log`、报告指标和产物清单；缺失项写失败，不用旧产物补齐。
- [ ] 停止栈，确认 `running=0`、租约已释放、无残留 Python 进程和 VaB 锁。
- [ ] 把正式结果放在独立目录，不覆盖或混入 2026-08-19 的工程验证产物。

2026-11-18 14:43 实测空闲 RAM 约 3.25 GB，低于 4 GB 建议值；是否在此条件下开跑必须由用户明确决定，不得用脚本默认值代替。

## 4. 验收判定

| 判定 | 条件 |
| --- | --- |
| 环境预检通过 | 脚本 `errors=0`；`validate-only` status=success；CUDA 可用 |
| 可进入短周期真实验证 | 环境预检通过、GPU 空闲、内存风险已确认、执行器变量已核对、参数已冻结 |
| 短周期真实验证通过 | 1 epoch 任务真实 `success`、产物与报告可读、租约释放、无残留子进程；仍不得称为正式实验通过 |
| 可进入正式实验 | 短周期真实验证通过，且正式配置和结果目录已由实验负责人确认 |
| 不得判定为真实 LDD 通过 | 只跑了 validate-only、只创建了 pending 任务、只使用旧工程产物、或指标缺失 |

## 5. 已知问题与边界

1. **无 captcha 的 `/auth/login` 会 500**：`AuthController.java:51` 在 `uuid=null` 时调用 `CAPTCHA_STORE.remove`，触发 NPE；第 52–54 行才检查验证码空/过期。正常前端先 `GET /auth/code` 再登录，不受影响；直接 API 调用必须先取 uuid/验证码，不能把 500 当作登录成功。该问题本轮未修复。
2. **`/auth/info` 曾返回 400“用户不存在”**：本轮未复核；token 对其他 API 可用。验收脚本和 API 证据使用已有管理员 Bearer token，没有重新走无 captcha 登录。
3. **数据集验证只到路径层**：`parseStatus=success` 不等于样本已解析；样本统计为空是当前事实。
4. **内存偏紧**：预检空闲约 2.3–2.5 GB；1 epoch 已跑通，但 5 epochs / 长时间运行的显存与系统内存峰值仍未实测。
5. **旧产物不是正式结论**：`...\VaB-main\security-model-results\20260819-232538-ldd-ldd-cifar10-sig-5epochs` 是 2026-08-19 工程验证，只可作为参照，不可替代论文实验。
6. **验收库已备份、尚未删除**：`security_model_ldd_20261118` 当前保留 5 epochs pending 任务、1 epoch success 任务和 1 条失败证据任务；已备份到 `formal-freeze-20261118`，删除/保留周期仍需确认。

## 6. 正式 5 epochs 配置与输入冻结（2026-11-18，未启动）

正式任务仍保持 `pending`，本轮只冻结输入和证据，不调用 `start`。

| 项 | 冻结值 |
| --- | --- |
| 任务 | `2122901116713205761`，`pending`、`progress=0`、`current_run_id=NULL`、`current_attempt=NULL` |
| 参数 | `dataset=cifar10`、`batch_size=128`、`num_epochs=5`、`SIG`、`all2one`、`poisoned_portion=0.1`、`trigger_label=0`、`lr_poison=0.001`、`r=0.75`、`alpha1=0.1`、`alpha2=0.5`、`seed=123` |
| 模板 | `hyper_id=NULL`；`hyperParameterFingerprint.available=false` 表示未绑定模板，不是最终参数丢失；最终参数以服务端 `custom_params` 为准 |
| 代码 | 分支 `feature/r3-dispatch-idempotency`，head `940c83a79a77ea60ca4177c74f2896ae9e190375`，工作区干净 |
| JAR | `migration-backend\target\security-model-backend-0.1.0-SNAPSHOT.jar`，SHA256 `39BEE64DAA64AC48525C5F9FBFE794DD965873D62E4A2778A1FCA0ACA9FEF17E` |
| 验收库 | `security_model_ldd_20261118`，48 表；冻结时 `pending=1`、`success=3`、`failed=3` |
| 数据库备份 | `F:\security-model-data\ldd-acceptance-20261118\formal-freeze-20261118\security_model_ldd_20261118-before-5epochs.sql`，788,998 B，SHA256 `5D3FC3D4092EBEB68BF002EBF92DB5BBDB9956F488D7AF68A47BFFD383DBD066` |
| 数据集清单 | 同目录 `formal-5epochs-freeze.json` 内 `dataset_manifest`；CIFAR-10 批次文件与 tar 均记录大小和 SHA256 |
| 1 epoch 产物 | 同目录 `artifacts-1epoch`，16 个文件、46,347,827 B；源目录与逐文件 SHA256 在 `one_epoch_artifact` |
| 环境预检 | 同目录 `environment-preflight.json`，`ready_with_warnings`、`errors=0`、`warnings=1`（空闲内存约 2.3 GB） |

数据集 6 的 `sample_count/feature_count/class_count` 保持 `NULL`；路径验证 `success` 只代表目录存在且可读，不代表已解析 50000 样本。

## 7. 复现命令索引

```powershell
# 环境预检
.\scripts\dev\Test-LddAcceptanceEnvironment.ps1 -PythonExe 'D:\Anaconda\python.exe' -VabRoot '<VaB根>' -Workspace '<工作目录>' -ProcessGuardWorkspace '<工作目录>' -ResultRoot '<结果根>'

# 短周期运行（必须显式 -Start 才会调用 start；创建新任务必须显式 -Epochs）
.\scripts\dev\Invoke-LddAcceptanceRun.ps1 -BaseUrl 'http://127.0.0.1:18300' -Epochs 1 -Start -OutputRoot 'tmp\ldd-short-run-real2-1epoch'

# 复用已有 pending 任务：脚本先核对 status/datasetId/algorithmId/服务端最终参数，再启动
.\scripts\dev\Invoke-LddAcceptanceRun.ps1 -BaseUrl 'http://127.0.0.1:18300' -TaskId 2122901116713205761 -Epochs 5 -Start -OutputRoot 'tmp\ldd-formal-5epochs'

# 启动验收栈（示例；JWT secret 从本地文件读取，不写入仓库）
$env:SECURITY_MODEL_JWT_SECRET = (Get-Content -Raw 'tmp\deploy-stack\jwt-secret.txt').Trim()
.\scripts\dev\Start-SecurityModelStack.ps1 -Mode db -SchemaName security_model_ldd_20261118 -ApiPort 18300 -DbPassword '<本机密码>'

# 停止验收栈（正常路径会先确认 running=0）
.\scripts\dev\Stop-SecurityModelStack.ps1 -StateRoot '<对应状态根>' -DbPassword '<本机密码>'
```

不要把数据库密码、JWT secret、Bearer token、绝对数据路径写入提交或公开材料。
