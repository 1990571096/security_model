# VaB Training-Pre-Defense Integration

Java 按 `vab://pre-defense/ldd` 识别真实 LDD，启动仓库内的 `algorithm_entry.py`。适配器将任务参数转换成 VaB 运行所需配置，在 attempt 中生成脚本，启动训练子进程并归档结果。[文件和进程协议](07-executor-contract.md) · [适配器](../../algorithm-runtime/vab/algorithm_entry.py)

## 历史记录

<!-- knowledge:historical -->
Date: 2026-08-24

## Goal

Integrate the user's external VaB project into the platform as real training-pre-defense
algorithms without copying VaB datasets, checkpoints, or experiment outputs into this
repository.

## Scope

This iteration registers only the proposed LDD method. The platform integration is
deliberately separate from experimental acceptance: no training result is considered
valid until the user finalizes the dataset, epochs, and research configuration for that
experiment.

## Design

```text
Pre-defense task
  -> DefenseTaskRequest snapshot
  -> PythonLddTaskExecutor
  -> algorithm-runtime/vab/algorithm_entry.py
  -> generated single-task VaB script
  -> VaB data loader / model / training code
  -> result.json + logs + selected-index artifacts
```

The platform repository owns the adapter and result contract. The external VaB
directory continues to own the algorithm source and experimental assets.

Supported implementation paths:

| Implementation path | VaB source | Stage |
| --- | --- | --- |
| `vab://pre-defense/ldd` | `LDD.py` | training-pre-defense |

Future comparison methods will be added only after their input parameters, output
artifacts, and result metrics are mapped to the platform contract.

## Compatibility Strategy

The original VaB scripts execute at module load, hard-code loops over datasets, trigger
types, and poisoned proportions, and write to shared relative directories. Modifying
those research scripts would make paper-code comparison harder.

The adapter parses the source with Python AST and writes a generated task copy that:

1. Replaces the hard-coded experiment matrix with one task configuration.
2. Uses a task-scoped output directory.
3. Writes selected sample indices for every legacy isolation ratio.
4. Disables interactive Matplotlib display calls.
5. Injects an epoch callback into the generated training loop.
6. Leaves the original VaB source untouched.

The generated script atomically writes a task-level `progress.json` at preparation,
training, result-finalization, and terminal phases. The training-loop callback reports
the current epoch and total effective epochs. The adapter also starts the generated
script with `PYTHONUNBUFFERED=1` and forwards child stdout into both `execution.log`
and the adapter stdout, so the backend's task-local `stdout.log` receives incremental
training output while the process is running.

## Request Mapping

The adapter merges `sys_algorithm.default_config` first and
`sys_hyper.parameter_config` second. Hyperparameter values override algorithm defaults.
The VaB dataset root comes from `DefenseTaskRequest.dataset.path`, unless a hyperparameter
JSON explicitly supplies `data_path`.

LDD accepts `dataset`, `trigger_type`, `trigger_label`, `poisoned_mode`,
`poisoned_portion`, `batch_size`, `num_epochs`, `lr_poison`, `r`, `alpha1`, `alpha2`,
and `seed`.

## Result Artifacts

The result JSON follows `07-executor-contract.md` and contains:

- Effective parameters after configuration merging.
- VaB text logs.
- The generated single-task script.
- `selected_indices_<ratio>.npy` files.
- `clean_purity_percent_by_isolation_ratio`.

`clean_purity` uses VaB's known poisoned sample indices. It is a controlled-experiment
oracle metric and must not be described as an unlabeled production-data metric.

The task-detail view renders effective configuration, purity by isolation ratio, selected
sample counts, task-local log/index artifact paths and the actual Python/PyTorch/CUDA
runtime whenever a VaB result is present.

## Cleaned Dataset Export

LDD's `selected_indices_<ratio>.npy` is interpreted as the retained clean-subset index
set for the corresponding isolation ratio. For successful CIFAR-10 LDD tasks, the report
page offers a ratio-specific download. The backend verifies that the index file is inside
the task artifact root, then invokes the adapter in export-only mode.

The exported ZIP contains the selected raw CIFAR-10 training samples as PNG files, their
labels, the original NPY index file and reconstruction metadata. This is a reproducible
dataset subset for downstream platform demonstration and training. It deliberately does
not serialize the trigger-modified tensors constructed inside VaB at runtime, so the ZIP
must be described as “the raw CIFAR-10 subset reconstructed from LDD retained indices”.

## Runtime Constraints

- `VAB_ROOT` must point to the external `VaB-main` directory.
- VaB calls CUDA APIs directly, so a CUDA-capable GPU is required.
- The adapter serializes VaB tasks with an exclusive runtime lock.
- The Java executor timeout must match the selected dataset and epoch count.
- `progress.json` is written beside `request.json` and `result.json` in the Java attempt
  directory; it is a live diagnostic snapshot, not a completion result.
- `security_model_vab_seed.sql` registers the deployment dataset root
  `VaB CIFAR-10 Runtime Root` only when the caller supplies `@vab_dataset_root`
  (the initializer reads `-VabDatasetRoot` / `SECURITY_MODEL_VAB_DATASET_ROOT`, checks
  the directory exists, is readable/non-empty, and contains `cifar-10-batches-py` plus
  `batches.meta`, `data_batch_1..5` and `test_batch`). A directory check is not a sample
  parse: the row is always inserted as `pending` with NULL sample/feature/class counts.
  An administrator promotes it with `POST /api/dataset/{id}/validate`; until then it is
  not in the available-dataset list. Without the variable the seed registers the LDD
  algorithm and its default hyper-parameter template but no dataset, so a deployment
  never gets a guessed or fake-parsed path.
- The seed is additive only: existing dataset, algorithm and hyper-template rows are
  never updated by a rerun. A changed path, default config, enabled flag or template
  requires the resource API or an explicit versioned migration, not initialization.
- Full-initialization idempotency is proved separately from the VaB seed: the isolated
  MySQL test runs `InitializeSecurityModelDatabase.ps1` twice and compares all business
  rows including `update_time`; it then edits path/parameters/enabled flags (and inserts
  an unknown no-context running task) and proves the third run preserves those values and
  only prints the unknown row in the review list. Legacy running convergence is limited to
  the exact `sql/eladmin.sql` rows `task_id=3/4`, names `MNIST数据预处理`/`文本数据清洗任务`,
  and `create_by=test`.
- The normal MVP seed is additive as well. Historical builtin-algorithm disable, model
  deprecation and menu visibility changes moved to the non-automatic
  `sql/security_model_mvp_legacy_convergence.sql`; builtin rows may stay visible, but the
  LDD-only command allowlist prevents creating or starting a task for them.

## Acceptance Criteria

1. A pre-defense task using `vab://pre-defense/ldd` creates a task-scoped result JSON.
2. A task executes one configured dataset, trigger type, and poisoned-data proportion.
3. Original VaB source files remain unmodified.
4. Logs and selected-index files are available through the task artifact path.
5. A non-zero VaB process exit produces a structured failed result.
6. A successful CIFAR-10 LDD task can export a ratio-specific raw clean subset from its
   retained-index artifact.

## Engineering Verification

The adapter was statically validated by parsing the LDD source, generating a task-scoped
Python copy, and compiling the adapter. One local engineering run completed on
2026-08-19 with CIFAR-10, SIG, batch size 128, and 5 epochs; it produced task-local
logs, a model checkpoint, and selected-index files. This run verifies the integration
path only and is not a paper experiment conclusion.

## Related Documentation

See [Current platform implementation](11-platform-implementation.md) for the current
business flow, frontend result presentation, and future expansion boundary.
<!-- /knowledge:historical -->
