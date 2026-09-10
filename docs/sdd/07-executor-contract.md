# Executor Contract

Date: 2026-07-11

## Decision

`DefenseTaskExecutor` accepts a `DefenseTaskRequest` instead of a raw task id.

The request is a validated execution snapshot containing:

- task id, task name, stage, description, creator
- dataset resource reference
- algorithm resource reference
- optional hyper-parameter template reference
- optional input model reference

Each task launch persists this request together with its dataset, algorithm, hyperparameter
template and task custom parameters in `sys_pre_defense.execution_snapshot`. The field is
an append-only attempt list: changing a resource after launch never changes the historical
experiment record. The active `run_id` and `attempt` are also stored in
`current_run_id` and `current_attempt`.

## Reason

Real security algorithms will often run through Python, PyTorch, a shell process, RPC, or a task queue. They need stable paths, configs, model metadata, and task metadata at task start time. Passing only a task id would force each executor to query business tables again and would couple runtime algorithm code to CRUD services.

## Result Envelope

Executors should keep writing result JSON into the existing task result fields:

- pre-defense: `cleaning_result`
- during-defense: `training_log`

Required result keys:

- `executor`
- `stage`
- `status`
- `message`
- `run_id`
- `attempt`
- `started_at`
- `finished_at`
- `artifacts`

Python/PyTorch executors should additionally write a `runtime` object containing the
actual interpreter and machine-learning environment used by the attempt, such as
Python, PyTorch, NumPy, CUDA, GPU and key dependency versions. This is separate from the Java-side
launch snapshot: the launch snapshot records what the platform intended to run, while
`runtime` records what the external process actually loaded.

Failure result keys:

- `error_code`
- `failure_reason`

The backend treats the result envelope as untrusted external-process output. Before persisting
the result, it verifies `executor`, `stage`, `run_id`, `attempt`, `message`, timestamps and
the `artifacts` object. A failed result must additionally provide a non-empty `error_code` and
`failure_reason`; malformed or mismatched output is converted into a platform failure result
instead of being shown as a successful LDD execution.

## Retry Contract

Only a `failed` pre-defense task can be retried. Retry clears the previous runtime result,
progress, timestamps and output dataset reference, returns the task to `pending`, and then
starts it as a new attempt. The original failure result remains available only through the
previous entry in the execution snapshot; retry does not overwrite prior launch parameters.

The MVP mock executor also writes a `task` snapshot into result JSON for protocol debugging.

Each launch receives a UUID `run_id` and a monotonically increasing `attempt` value.
The Python executor stores request/result/log files below an attempt-specific directory,
so retrying a task cannot overwrite the previous attempt's runtime files.

The task claim is an atomic `pending -> running` update. Every progress, result and finish
callback includes the `task_id` and `run_id`, and the database update additionally requires
`status = 'running'` and the same `current_run_id`. A late callback from an older timed-out
or retried attempt therefore updates zero rows and is ignored instead of overwriting the
current run.

The external executor is dispatched after the surrounding transaction commits. This keeps
the persisted task claim and immutable snapshot ahead of process startup.

## Cancel Contract

Only a `running` task can be canceled. The service uses `task_id + current_run_id + status=running`
as the cancellation condition and atomically writes the terminal `canceled` result first. The
external process is then terminated after transaction commit. The executor remembers the canceled
`run_id`; progress, result and finish callbacks require the same `run_id` and `status=running`, so
late callbacks update zero rows and are discarded.

## Extension Rule

Future real executors should implement `DefenseTaskExecutor` and treat `DefenseTaskRequest` as the input contract. Controllers and task CRUD services should not directly depend on algorithm implementation code.

## Python Process Executor

The default MVP runtime uses `MockDefenseTaskExecutor`.

Enable the local Python process executor only when an algorithm project is ready:

```bash
--spring.profiles.active=dev,python-executor
```

Configuration keys:

- `security-model.executor.python.executable`
- `security-model.executor.python.script`
- `security-model.executor.python.workspace`
- `security-model.executor.python.timeout-seconds`

The Python script is called as:

```bash
python algorithm_entry.py --stage pre_defense --request request.json --result result.json
```

The script should read `request.json`, run the algorithm, and write `result.json` using the result envelope defined above. If `result.json` is missing but the process exits with code `0`, the backend records a minimal successful envelope with the executor workspace as an artifact.

## Clean Subset Export Contract

For LDD CIFAR-10 tasks, the adapter can receive an export-only request:

```bash
python algorithm_entry.py --export-clean-subset \
  --selection-file selected_indices_5.npy \
  --dataset-root <cifar-root> \
  --dataset cifar10 \
  --output clean-subset-ratio-5.zip \
  --metadata export-metadata.json
```

The selection file is validated as a task artifact. The exporter reconstructs the retained
raw CIFAR-10 training samples by their original indices and writes a ZIP containing metadata,
the original index file, CSV labels and PNG images. It does not claim to reproduce VaB's
in-memory poisoned tensors.

## VaB Adapter

The first real training-pre-defense integration uses
`algorithm-runtime/vab/algorithm_entry.py`. It accepts the standard command above and
dispatches only allow-listed VaB implementation paths. See
[VaB training-pre-defense integration](10-vab-integration.md) for supported algorithms,
parameter mapping, artifacts, and runtime requirements.
