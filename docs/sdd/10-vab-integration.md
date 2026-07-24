# VaB Training-Pre-Defense Integration

Date: 2026-07-24

## Goal

Integrate the user's external VaB project into the platform as real training-pre-defense
algorithms without copying VaB datasets, checkpoints, or experiment outputs into this
repository.

## Scope

This iteration registers the proposed LDD method and every VaB comparison baseline
that has a standalone argparse entry point. The platform integration is deliberately
separate from experimental acceptance: no training result is considered valid until
the user finalizes the dataset, epochs, and research configuration for that experiment.

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
| `vab://pre-defense/vs-dcm` | `VS_DCM.py` | training-pre-defense |
| `vab://pre-defense/vs-abl` | `VS_ABL.py` | training-pre-defense |
| `vab://pre-defense/vs-meta-sift` | `VS_Meta_Sift.py` | training-pre-defense |
| `vab://pre-defense/vs-mi-dcm` | `VS_MI_DCM.py` | training-pre-defense |
| `vab://pre-defense/vs-spectral-signature` | `VS_Spectral Signature.py` | training-pre-defense |
| `vab://pre-defense/vs-spectre` | `VS_Spectre.py` | training-pre-defense |
| `vab://pre-defense/vs-telltale` | `VS_Telltale.py` | training-pre-defense |

`VS_CT.py` is excluded because it has no standalone CLI/main entry. `VS_ABL_temp.py`
is excluded because it is a temporary research variant rather than a platform-facing
baseline.

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

All registered baselines accept the common task settings `dataset`, `trigger_type`,
`trigger_label`, `poisoned_mode`, `poisoned_portion`, `batch_size`, and `seed`, with
their original training and detector parameters retained in their platform parameter
schema. For example, VS-ABL exposes ascent and threshold settings, VS-Meta-Sift exposes
sifter and meta-learning settings, and VS-DCM keeps `gamma`.

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

## Verification Boundary

The integration is statically validated by parsing the allow-listed source files,
generating a task-scoped Python copy, and compiling the adapter. Formal experimental
verification is intentionally deferred: it requires the user-selected dataset,
training rounds, and algorithm configuration.
