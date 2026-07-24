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
- `started_at`
- `finished_at`
- `artifacts`

Failure result keys:

- `error_code`
- `failure_reason`

The MVP mock executor also writes a `task` snapshot into result JSON for protocol debugging.

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

## VaB Adapter

The first real training-pre-defense integration uses
`algorithm-runtime/vab/algorithm_entry.py`. It accepts the standard command above and
dispatches only allow-listed VaB implementation paths. See
[VaB training-pre-defense integration](10-vab-integration.md) for supported algorithms,
parameter mapping, artifacts, and runtime requirements.
