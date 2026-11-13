# Executor Contract

Updated: 2026-11-13 (R0 fact calibration; supersedes the 2026-10-26 text where they conflict).

This document is the frozen contract for the migration backend (`migration-backend`) and the
Python adapter (`algorithm-runtime/vab/algorithm_entry.py`). Every rule below is verified
against the code cited in the section it describes. Anything not in this document is not
guaranteed by the platform.

## Decision

`DefenseTaskExecutor` accepts a `DefenseTaskRequest` instead of a raw task id. The request is
a validated execution snapshot that carries: task id, name, stage, description, creator
(username snapshot only — **not** an identity claim), dataset reference, algorithm reference,
optional hyper-parameter template and optional model reference.

Each launch persists this request with its resources and custom parameters into
`sys_pre_defense.execution_snapshot` as an append-only attempt list (`version: 2`, entries
carrying `attempt`, `runId`, `launchedAt`, `request`, `runtime`, `quota`, `snapshotHash`).
Changing a resource after launch never rewrites history. `current_run_id` and
`current_attempt` mirror the live attempt.

## Configuration (migration backend)

The migration backend has **no** `python-executor` Spring profile. That profile exists only in
the legacy `eladmin` project (`@Profile("python-executor")` on its
`PythonDefenseTaskExecutor`). Setting `--spring.profiles.active=dev,python-executor` on
`migration-backend` changes nothing and must not be documented as the way to enable Python.

The adapter is configured through these properties (Spring relaxed binding, so the
environment variable name is the upper-snake-case form):

| Property | Environment variable | Default | Meaning |
| --- | --- | --- | --- |
| `security-model.executor.python.executable` | `SECURITY_MODEL_EXECUTOR_PYTHON_EXECUTABLE` | `python` | Interpreter that can import torch |
| `security-model.executor.python.script` | `SECURITY_MODEL_EXECUTOR_PYTHON_SCRIPT` | *(empty)* | Absolute path to `algorithm_entry.py` |
| `security-model.executor.python.workspace` | `SECURITY_MODEL_EXECUTOR_PYTHON_WORKSPACE` | `${java.io.tmpdir}/security-model-executor` | Attempt directory root |
| `security-model.executor.python.timeout-seconds` | `SECURITY_MODEL_EXECUTOR_PYTHON_TIMEOUT_SECONDS` | `3600` | Wall-clock timeout per attempt |
| `security-model.executor.python.fingerprint-content` | `SECURITY_MODEL_EXECUTOR_PYTHON_FINGERPRINT_CONTENT` | `true` | Hash dataset files ≤1GB |
| `security-model.executor.python.fingerprint-max-bytes` | `SECURITY_MODEL_EXECUTOR_PYTHON_FINGERPRINT_MAX_BYTES` | `1073741824` | Size limit for hashing |
| `security-model.executor.quota.max-running-tasks` | `SECURITY_MODEL_EXECUTOR_QUOTA_MAX_RUNNING_TASKS` | `4` | JVM quota, **not** a GPU budget |
| `security-model.executor.quota.max-running-tasks-per-user` | `SECURITY_MODEL_EXECUTOR_QUOTA_MAX_RUNNING_TASKS_PER_USER` | `2` | Quota keyed by username |
| `security-model.executor.quota.max-task-timeout-seconds` | `SECURITY_MODEL_EXECUTOR_QUOTA_MAX_TASK_TIMEOUT_SECONDS` | `3600` | Recorded in the quota snapshot |
| `SECURITY_MODEL_VAB_RESULT_ROOT` | same | unset | Optional external artifact root for VaB runs |
| `VAB_ROOT` | same | unset | Required by the adapter; points to the external `VaB-main` |

An empty `script` fails the attempt with `EXECUTOR_NOT_CONFIGURED`. There is no fallback to a
demo or mock executor in this backend.

The Java executor's own error message still says `未配置 SECURITY_MODEL_PYTHON_SCRIPT`; that
variable name is stale and is *not* bound to the property above. Use the table.

## Invocation

```bash
python algorithm_entry.py --stage pre_defense --request request.json \
  --result result.json --progress-file progress.json
```

The adapter also supports `--validate-only`, but the Java executor never passes it: every
platform launch runs training. `--validate-only` is a manual preflight tool only.

Adapter modes:

| Mode | Flag | Notes |
| --- | --- | --- |
| Task (training) | `--stage pre_defense` | Requires CUDA in preflight |
| Preflight only | `--validate-only` | Generates the single-task script, runs no training |
| Clean subset export | `--export-clean-subset` | Requires `--selection-file`, `--dataset-root`, `--dataset`, `--output`; numpy only |

## Attempt Directory Layout (v1)

```text
<workspace>/pre_defense-<taskId>/attempt-<n>/
├── request.json        # DefenseTaskRequest written by Java
├── result.json         # final envelope, written by the adapter
├── progress.json       # atomically replaced live snapshot
├── stdout.log          # adapter stdout
├── stderr.log          # adapter stderr
└── artifacts/          # default VaB output root (unless SECURITY_MODEL_VAB_RESULT_ROOT)
```

Attempt directories are keyed by task id, not by user. Ownership can only be enforced in the
service layer (see ADR-012 and `18-multiuser-ownership-and-resources.md`).

## Result Envelope (v1)

`result.json` is the **only** completion source of truth. Required keys: `executor`, `stage`,
`status`, `message`, `started_at`, `finished_at`, `run_id`, `attempt`, `artifacts`.

Rules enforced by `PythonLddTaskExecutor.validateResult()`:

- `stage` must equal `pre_defense`.
- `run_id` must equal the request's `runId`; `attempt` must equal the request's `attempt`.
- `status` must be `success` or `failed`.
- `artifacts` must be a JSON object.
- A `failed` result must additionally provide non-empty `error_code` and `failure_reason`.

Any violation becomes a platform failure (`RESULT_PROTOCOL_ERROR` / `RESULT_INVALID_JSON`); it is
never rendered as a successful LDD run. A malformed or missing `result.json` is a failure —
**there is no "minimal success envelope" fallback**. Specifically, when the process exits 0 but
`result.json` is absent, the executor raises `RESULT_MISSING` and the task is marked `failed`.

Optional but expected keys produced by the VaB adapter: `algorithm`, `effective_config`,
`metrics`, `runtime`.

```json
{
  "executor": "python-vab-adapter",
  "stage": "pre_defense",
  "status": "success",
  "message": "VaB LDD completed",
  "run_id": "<uuid>",
  "attempt": 1,
  "started_at": "2026-08-19T…Z",
  "finished_at": "…Z",
  "algorithm": "vab://pre-defense/ldd",
  "effective_config": {"dataset": "cifar10", "trigger_type": "SIG", "…": "…"},
  "metrics": {
    "clean_purity_percent_by_isolation_ratio": {"5": 96.4},
    "selected_sample_count_by_isolation_ratio": {"5": 47500},
    "metric_note": "clean_purity uses VaB's known poisoned-sample labels…"
  },
  "artifacts": {
    "type": "vab-task-output",
    "available": true,
    "path": "<absolute artifact root>",
    "generated_script": "<path>",
    "selection_kind": "retained_clean_subset",
    "logs": ["<path>", "…"],
    "images": ["<path>", "…"],
    "model_files": ["<path>", "…"],
    "selection_files": ["<path>/selected_indices_5.npy"]
  },
  "runtime": {
    "adapterVersion": "1.1.0",
    "pythonVersion": "3.x",
    "pythonExecutable": "<path>",
    "numpyVersion": "…",
    "torchVersion": "…",
    "cudaVersion": "…",
    "cudaAvailable": true,
    "cudaDeviceCount": 1,
    "cudaDevices": ["NVIDIA GeForce RTX 2060"],
    "dependencyManifest": {"packages": […]}
  }
}
```

`metrics.clean_purity_percent_by_isolation_ratio` is an oracle metric computed from VaB's known
poisoned indices; it must not be described as an unlabeled production-data metric.

Failure envelope (adapter): `status: "failed"`, `error_code: "VAB_INTEGRATION_ERROR"`,
`failure_reason: "<message>"`, and the process exits 1. The adapter collapses every internal
failure into this single code; the platform's own codes are in §"Error Code Registry".

## Progress Snapshot (v1)

`progress.json` is supplemental. It never replaces `result.json`, and it must never be used to
decide that a task failed. Fields written by the adapter/generated script:

| Field | Type | Notes |
| --- | --- | --- |
| `run_id` | string \| null | `null` in the very first `preparing` snapshot, before the request is parsed |
| `attempt` | int \| null | Same as above |
| `phase` | string | `preparing`, `training`, `finalizing`, `completed`, `failed` |
| `phase_label` | string | Human-readable Chinese label |
| `progress` | int | 0–100 as written; Java clamps to 0–99 while running |
| `message` | string | Latest status text |
| `heartbeat_at` | ISO-8601 UTC | Rewritten on every snapshot |
| `epoch`, `total_epochs` | int | Only during `training` |

Phases and percentages in the current adapter: `preparing` 5 → 10, `training` 12, then
`min(85, 15 + 70·(epoch−1)/max(1, num_epochs−1))`, `finalizing` 90, terminal `completed` 100 /
`failed` 0. Note the quirk: the generated callback reports `total_epochs = num_epochs − 1`
(`platform_report_epoch` does `total = max(1, int(total) - 1)`), so the UI shows “epoch x / (n−1)”
while LDD actually runs `n` epochs. This is a display inconsistency, not a training change; fixing
it must not alter the frozen 128 batch / 5 epochs configuration.

Consumer rules (implemented in `PythonLddTaskExecutor.updateProgressFromSnapshot` and
`PreDefenseLogQueryService`):

- A snapshot whose `run_id` differs from the active run is ignored; `null` is tolerated.
- A snapshot whose `attempt` is missing (`-1`) is tolerated; a mismatch is ignored.
- Only `progress` is written to MySQL; the rest is exposed through the log endpoint.
- Heartbeat staleness is **display-only**. The frontend shows “较久未更新” after 60 s; it does
  not mark the task failed. There is no server-side stale-heartbeat judgement.

## Claim, Retry, Cancel, Recovery

- Claim: atomic `pending → running` with `run_id`, `attempt`, `execution_snapshot`.
- Progress/result/finish updates require `status = 'running'` and the same `current_run_id`.
- Retry: only `failed → pending` (`resetForRetry`), which clears runtime fields but keeps the
  attempt list, so `nextAttempt` continues to grow. The old attempt's files stay on disk.
- Cancel: only `running`; the terminal `canceled` row is written first, then the process tree is
  destroyed after commit (`process.descendants()` + `destroyForcibly`).
- Dispatch rejection: `markDispatchFailure` writes `TASK_QUEUE_FULL`, guarded by
  `task_id + run_id + attempt + status='running'`.
- Timeout: `PythonProcessWaiter` destroys the process tree and the executor fails the attempt
  with `TASK_TIMEOUT`.
- Startup recovery: every row still `running` becomes `failed` with `EXECUTOR_RESTARTED`
  (`failOrphanedForRun` matches `current_run_id`, so a task that finished first wins). This is a
  single-instance assumption, not a lease.

## Error Code Registry (v1)

| Code | Producer | Meaning | Auto-retryable |
| --- | --- | --- | --- |
| `EXECUTOR_NOT_CONFIGURED` | Java | `script` property empty | No (config fix) |
| `TASK_TIMEOUT` | Java | Exceeded `timeout-seconds` | Manual, after inspection |
| `PYTHON_PROCESS_EXITED` | Java | Non-zero exit without a `failed` result | Manual |
| `RESULT_MISSING` | Java | Exit 0 but no `result.json` | Manual |
| `RESULT_INVALID_JSON` | Java | `result.json` unparsable | Manual |
| `RESULT_PROTOCOL_ERROR` | Java | Missing/mismatched required fields | Manual |
| `PYTHON_EXECUTION_ERROR` | Java | Unclassified exception | Manual |
| `TASK_INTERRUPTED` | Java | Executor thread interrupted | Manual |
| `TASK_QUEUE_FULL` | Java | Bounded pool rejected dispatch | Yes (retry) |
| `TASK_QUEUE_UNAVAILABLE` | Java | Pool shut down | No |
| `TASK_QUOTA_EXCEEDED` | Java | Global running quota hit | Yes (later) |
| `TASK_USER_QUOTA_EXCEEDED` | Java | Per-username quota hit | Yes (later) |
| `EXECUTOR_RESTARTED` | Java | Recovery after app restart | Yes (retry) |
| `TASK_CANCELED` | Java | Cancelled by operator | No |
| `VAB_INTEGRATION_ERROR` | Python adapter | Adapter failure with no more specific code (script/config error, non-zero child exit) | Manual |
| `GPU_LOCK_BUSY` | Python adapter / Java fallback | Adapter runtime lock held by a live process (or unreclaimable) | Retry later |
| `GPU_OOM` | Python adapter / Java fallback | CUDA/CUBLAS/CUDNN allocation failure | Manual, lower batch |
| `CUDA_UNAVAILABLE` | Python adapter / Java fallback | No CUDA device or driver/runtime mismatch | No automatic retry |
| `EXPORT_QUEUE_FULL` | Java | Export dispatch rejected | Yes |
| `EXPORT_FAILED` | Java | Export process failed/timeout/no ZIP | Manual |
| `EXPORT_INTERRUPTED` | Java | Export thread interrupted | Manual |

Note: since 2026-11-13 the adapter classifies lock conflicts, CUDA OOM and missing-CUDA
separately (`GPU_LOCK_BUSY` / `GPU_OOM` / `CUDA_UNAVAILABLE`); Java applies the same text
classification only when the child died without writing `result.json`. `GPU_OOM` and
`CUDA_UNAVAILABLE` are not automatically retried; `GPU_LOCK_BUSY` may be retried later by the
operator (no queue yet).

## Clean Subset Export Contract

For LDD CIFAR-10 tasks the adapter can run in export-only mode:

```bash
python algorithm_entry.py --export-clean-subset \
  --selection-file selected_indices_5.npy \
  --dataset-root <cifar-root> \
  --dataset cifar10 \
  --output clean-subset-ratio-5.zip.part \
  --metadata clean-subset-ratio-5.metadata.json.part
```

The Java side (`CleanSubsetExportExecutor`) writes `<artifactRoot>/clean-subset-exports/`, redirects
stdout+stderr to `*.export.log`, then renames `.part` → final only when the process exited 0 and
the `.part` ZIP is non-empty. Timeouts destroy the process tree (descendants first, then the
direct process). `ExportRecoveryRunner` converges rows left `pending`/`running` after a restart
to `EXPORT_NOT_STARTED` / `EXPORT_INTERRUPTED`; it never marks a half-written ZIP as success.

Selection files must resolve inside the task artifact root and match `selected_indices_(\d+).npy`;
the export is idempotent per `(task_id, isolation_ratio)` via a unique key.

## Attempt Artifacts and Retention

Each run writes into `<executor-workspace>/pre_defense-<taskId>/attempt-<n>/`. The directory is the
artifact boundary: a retry increments `attempt` and never overwrites an earlier directory.

After the child process ends (success, failure or cancel), `ExecutorArtifactPolicy`:

- trims `stdout.log` / `stderr.log` to the last `security-model.executor.artifacts.max-log-bytes`
  (default 20 MiB) and prefixes a truncation marker with the original byte count;
- writes `manifest.json` listing every top-level file with `bytes` and `sha256`;
- never changes the task outcome if trimming or hashing fails (warn only).

`ExecutorArtifactRetentionJob` deletes an attempt directory only when all hold: retention is
enabled (`retention-days > 0`), the task exists with status `success|failed|canceled`, the attempt
is not `current_attempt`, the directory is older than the retention window, and the path stays
inside the configured workspace. All other directories are left untouched.



Concurrency is enforced by the MySQL single-slot lease (`sys_gpu_lease`, see ADR-014). The adapter
lock at `VAB_ROOT/.security-model-vab.lock` remains as a second line of defence:

- The lock file now records JSON metadata: `pid`, `host`, `task_id`, `run_id`, `token`,
  `acquired_at` (legacy files containing a bare pid are still readable).
- Two concurrent LDD runs do not queue; the loser fails with `GPU_LOCK_BUSY`
  (“another VaB task is using the shared GPU/runtime lock”).
- A lock whose recorded pid is no longer alive is reclaimed: the file is moved aside, its
  metadata token is re-checked, and only then is a new lock created. If the token changed
  between read and move (another process just refreshed it), the file is restored and the new
  caller gets `GPU_LOCK_BUSY`.
- Liveness is checked without signalling: Windows uses `OpenProcess` + `GetExitCodeProcess`,
  POSIX uses `os.kill(pid, 0)`.
- Release deletes the file only when its `token` still matches the caller, so a slow run cannot
  delete a successor's lock.
- Residual risk: pid reuse looks like a live holder (conservative: stays busy); a file with
  unparsable content and no pid is reclaimed only after
  `SECURITY_MODEL_VAB_LOCK_STALE_SECONDS` (default 21600). On POSIX, a dead holder's pid may
  have been reused by an unrelated process, which also stays conservative (busy).

The platform quota does not model the GPU. A database-backed single-slot lease is proposed in
`18-multiuser-ownership-and-resources.md` §5 and is **not implemented**.

## Extension Rule

Future real executors implement `DefenseTaskExecutor` and accept `DefenseTaskRequest`. Controllers
and task CRUD services must not depend on algorithm implementation code. Only
`vab://pre-defense/ldd` is allow-listed; every other `implementation_path` is rejected before
claim.
