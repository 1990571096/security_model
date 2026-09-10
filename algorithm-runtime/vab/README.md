# VaB Algorithm Adapter

This directory integrates the external VaB project with the platform Python executor.

The VaB source, datasets, checkpoints, and visualization outputs remain outside this
repository. Set `VAB_ROOT` to the VaB project root:

```powershell
$env:VAB_ROOT='F:\Study\postgraduate\1下\VB backdoor defence\VaB-main'
```

Supported implementation path:

- `vab://pre-defense/ldd`

`algorithm_entry.py` reads the platform request JSON, creates an AST-transformed
single-task LDD script, and writes the standard result JSON consumed by
`PythonDefenseTaskExecutor`.

The original VaB scripts iterate over fixed experimental settings. The adapter changes
only the generated task copy. It limits a platform task to one dataset, one trigger
type, and one poisoned-data proportion; it also stores logs and selected sample
indices in the task artifact directory. The result JSON also records the concrete
Python, PyTorch, NumPy, CUDA and GPU runtime used by that attempt.

The adapter is intentionally limited to LDD in this iteration. Future algorithms must
reuse the pre-defense request/result contract and be explicitly allow-listed after their
input, output, and artifact semantics have been confirmed.

## Runtime Requirements

- Python 3.9
- PyTorch with CUDA support
- A CUDA-capable GPU
- VaB datasets available below `VAB_ROOT\dataset`

The current local environment was checked with:

```text
torch=2.1.1+cu118
cuda=True
scipy=1.7.1
sklearn=0.24.2
Pillow=9.4.0
opencv=4.7.0
h5py=3.6.0
```

Do not install VaB's `requirements.txt` directly. It contains incompatible duplicate
pins collected from multiple experiments. Use a dedicated environment only if the
current verified environment changes.

## Preflight

```powershell
python algorithm-runtime\vab\algorithm_entry.py `
  --stage pre_defense `
  --request algorithm-runtime\vab\sample-request.json `
  --result $env:TEMP\vab-preflight-result.json `
  --validate-only
```

## Important Notes

- Dataset `filePath` must be a VaB-compatible dataset root directory. For this local
  project, it is normally `F:\Study\postgraduate\1下\VB backdoor defence\VaB-main\dataset`.
- The selected indices are the retained clean subset used by the legacy scripts, not
  a generic "removed sample" list.
- `clean_purity` is an evaluation metric based on VaB's known poisoned indices. It is
  appropriate for controlled experiments, not unlabeled production data.
- A runtime lock serializes VaB tasks because the legacy scripts share the local GPU.
- LDD completed one local engineering verification run with CIFAR-10, SIG, batch size
  128, and 5 epochs. Its output is an implementation verification artifact, not a
  paper experiment conclusion.
