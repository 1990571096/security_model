# VaB Training-Pre-Defense Integration

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
  -> PythonDefenseTaskExecutor
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
5. Leaves the original VaB source untouched.

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
sample counts, and task-local log/index artifact paths whenever a VaB result is present.

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
- `security_model_vab_seed.sql` registers the developer-machine dataset root
  `VaB CIFAR-10 Runtime Root`. Production deployments must create an equivalent
  resource with their own local VaB dataset path.

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
