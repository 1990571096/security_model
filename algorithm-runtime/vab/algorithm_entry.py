#!/usr/bin/env python
"""Platform adapter for the external VaB training-pre-defense project."""

import argparse
import ast
import csv
import io
import json
import os
import pickle
import platform
import re
import struct
import subprocess
import sys
import uuid
import zipfile
import zlib
from datetime import datetime, timezone
from pathlib import Path


class IntegrationError(Exception):
    """Raised for a platform/VaB integration contract failure."""

    def __init__(self, message, error_code="VAB_INTEGRATION_ERROR"):
        super().__init__(message)
        self.error_code = error_code


SUPPORTED_ALGORITHMS = {
    "vab://pre-defense/ldd": {
        "source": "LDD.py",
        "config_keys": (
            "dataset",
            "trigger_type",
            "trigger_label",
            "poisoned_mode",
            "poisoned_portion",
            "batch_size",
            "num_epochs",
            "lr_poison",
            "r",
            "alpha1",
            "alpha2",
            "seed",
        ),
        "forced_args": ("batch_size",),
        "selection_variable": "perm",
        "selection_kind": "retained_clean_subset",
    },
}

ALGORITHM_ALIASES = {
    "vab://ldd": "vab://pre-defense/ldd",
}

ADAPTER_VERSION = "1.1.0"

DEPENDENCY_SPECS = (
    ("numpy", "numpy"),
    ("torch", "torch"),
    ("scipy", "scipy"),
    ("scikit-learn", "sklearn"),
    ("Pillow", "PIL"),
    ("opencv-python", "cv2"),
    ("h5py", "h5py"),
)


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def collect_dependency_manifest():
    """Record versions of packages used by the VaB runtime."""
    try:
        from importlib import metadata as metadata_module
    except ImportError:
        try:
            import importlib_metadata as metadata_module
        except ImportError:
            return {
                "source": "importlib.metadata",
                "available": False,
                "error": "metadata_module_unavailable",
                "packages": [],
            }

    packages = []
    for distribution_name, module_name in DEPENDENCY_SPECS:
        package = {
            "name": distribution_name,
            "module": module_name,
            "available": False,
        }
        try:
            package["version"] = metadata_module.version(distribution_name)
            package["available"] = True
        except metadata_module.PackageNotFoundError:
            package["version"] = None
            package["error"] = "not_installed"
        except Exception as exc:
            package["version"] = None
            package["error"] = exc.__class__.__name__
        packages.append(package)
    return {
        "source": "importlib.metadata",
        "available": True,
        "packages": packages,
    }


def collect_runtime_snapshot():
    """Capture the actual Python ML runtime used by this attempt."""
    runtime = {
        "adapterVersion": ADAPTER_VERSION,
        "pythonVersion": platform.python_version(),
        "pythonImplementation": platform.python_implementation(),
        "pythonExecutable": sys.executable,
        "platform": platform.platform(),
        "pythonPath": list(sys.path),
        "dependencyManifest": collect_dependency_manifest(),
    }
    try:
        import numpy as np

        runtime["numpyVersion"] = np.__version__
    except Exception as exc:
        runtime["numpyVersion"] = None
        runtime["numpyError"] = exc.__class__.__name__

    try:
        import torch

        runtime["torchVersion"] = torch.__version__
        runtime["cudaVersion"] = getattr(torch.version, "cuda", None)
        runtime["cudaAvailable"] = bool(torch.cuda.is_available())
        runtime["cudaDeviceCount"] = int(torch.cuda.device_count())
        runtime["cudaDevices"] = [
            torch.cuda.get_device_name(index)
            for index in range(runtime["cudaDeviceCount"])
        ]
    except Exception as exc:
        runtime["torchVersion"] = None
        runtime["cudaVersion"] = None
        runtime["cudaAvailable"] = False
        runtime["cudaDeviceCount"] = 0
        runtime["cudaDevices"] = []
        runtime["torchError"] = exc.__class__.__name__
    return runtime


def load_json_object(raw, field_name):
    if raw in (None, ""):
        return {}
    if not isinstance(raw, str):
        raise IntegrationError("{} must be a JSON string".format(field_name))
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise IntegrationError("{} is not valid JSON: {}".format(field_name, exc)) from exc
    if not isinstance(value, dict):
        raise IntegrationError("{} must contain a JSON object".format(field_name))
    return value


def get_algorithm_key(request):
    algorithm = request.get("algorithm") or {}
    path = algorithm.get("path")
    if not path:
        raise IntegrationError("request.algorithm.path is required")
    return ALGORITHM_ALIASES.get(path, path)


def build_config(request, spec):
    algorithm = request.get("algorithm") or {}
    hyper = request.get("hyperParameter") or {}
    config = load_json_object(algorithm.get("configJson"), "algorithm.configJson")
    config.update(load_json_object(hyper.get("configJson"), "hyperParameter.configJson"))
    config.update(load_json_object(request.get("customParams"), "request.customParams"))

    missing = [key for key in ("dataset", "trigger_type", "poisoned_portion") if key not in config]
    if missing:
        raise IntegrationError("missing required VaB config: {}".format(", ".join(missing)))

    dataset_ref = request.get("dataset") or {}
    data_path = config.pop("data_path", dataset_ref.get("path"))
    if not data_path:
        raise IntegrationError(
            "VaB requires a dataset root directory. Set dataset.filePath or hyperParameter.data_path."
        )

    unsupported = sorted(set(config) - set(spec["config_keys"]))
    if unsupported:
        raise IntegrationError(
            "unsupported {} config keys: {}".format(
                Path(spec["source"]).stem, ", ".join(unsupported)
            )
        )

    config["data_path"] = str(Path(data_path).expanduser().resolve())
    config.setdefault("trigger_label", 0)
    config.setdefault("poisoned_mode", "all2one")
    config.setdefault("batch_size", 128)
    config.setdefault("seed", 123)
    return config


def attr_name(node):
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name):
        return "{}.{}".format(node.value.id, node.attr)
    return None


def platform_config_value(key):
    return ast.Subscript(
        value=ast.Name(id="PLATFORM_CONFIG", ctx=ast.Load()),
        slice=ast.Constant(value=key),
        ctx=ast.Load(),
    )


class SingleTaskTransformer(ast.NodeTransformer):
    """Convert a VaB experiment script into an isolated platform task script."""

    def __init__(self, spec):
        self.spec = spec

    def visit_For(self, node):
        node = self.generic_visit(node)
        if attr_name(node.target) != "epoch":
            return node
        call = ast.Expr(
            value=ast.Call(
                func=ast.Name(id="platform_report_epoch", ctx=ast.Load()),
                args=[
                    ast.Name(id="epoch", ctx=ast.Load()),
                    ast.Attribute(
                        value=ast.Name(id="args", ctx=ast.Load()),
                        attr="num_epochs",
                        ctx=ast.Load(),
                    ),
                ],
                keywords=[],
            )
        )
        node.body.insert(0, call)
        return node

    def visit_Assign(self, node):
        node = self.generic_visit(node)
        if len(node.targets) != 1:
            return node

        target = node.targets[0]
        target_name = attr_name(target)

        if target_name in ("poison_rate", "data_name", "attack_mode"):
            config_key = {
                "poison_rate": "poisoned_portion",
                "data_name": "dataset",
                "attack_mode": "trigger_type",
            }[target_name]
            node.value = ast.List(elts=[platform_config_value(config_key)], ctx=ast.Load())
            return node

        if target_name in ("args.batch_size", "args.tuning_epochs"):
            key = target_name.split(".", 1)[1]
            if key in self.spec["forced_args"]:
                node.value = platform_config_value(key)
            return node

        if target_name == "test_log_path":
            node.value = ast.Call(
                func=ast.Attribute(
                    value=ast.Attribute(
                        value=ast.Name(id="os", ctx=ast.Load()),
                        attr="path",
                        ctx=ast.Load(),
                    ),
                    attr="join",
                    ctx=ast.Load(),
                ),
                args=[
                    ast.Name(id="PLATFORM_OUTPUT_ROOT", ctx=ast.Load()),
                    ast.Attribute(value=ast.Name(id="args", ctx=ast.Load()), attr="model_name", ctx=ast.Load()),
                    ast.Attribute(value=ast.Name(id="args", ctx=ast.Load()), attr="dataset", ctx=ast.Load()),
                    ast.Attribute(value=ast.Name(id="args", ctx=ast.Load()), attr="trigger_type", ctx=ast.Load()),
                ],
                keywords=[],
            )
            return node

        if target_name == self.spec.get("selection_variable"):
            save_selection = ast.Expr(
                value=ast.Call(
                    func=ast.Attribute(
                        value=ast.Name(id="np", ctx=ast.Load()),
                        attr="save",
                        ctx=ast.Load(),
                    ),
                    args=[
                        ast.Call(
                            func=ast.Attribute(
                                value=ast.Attribute(
                                    value=ast.Name(id="os", ctx=ast.Load()),
                                    attr="path",
                                    ctx=ast.Load(),
                                ),
                                attr="join",
                                ctx=ast.Load(),
                            ),
                            args=[
                                ast.Name(id="test_log_path", ctx=ast.Load()),
                                ast.Call(
                                    func=ast.Attribute(
                                        value=ast.Constant(value="selected_indices_{}.npy"),
                                        attr="format",
                                        ctx=ast.Load(),
                                    ),
                                    args=[
                                        ast.Attribute(
                                            value=ast.Name(id="args", ctx=ast.Load()),
                                            attr="isolation_ratio",
                                            ctx=ast.Load(),
                                        )
                                    ],
                                    keywords=[],
                                ),
                            ],
                            keywords=[],
                        ),
                        ast.Name(id=target_name, ctx=ast.Load()),
                    ],
                    keywords=[],
                )
            )
            return [node, save_selection]

        if target_name == "args":
            value = node.value
            if (
                isinstance(value, ast.Call)
                and isinstance(value.func, ast.Attribute)
                and attr_name(value.func) == "parser.parse_args"
            ):
                updates = [
                    ast.Assign(
                        targets=[
                            ast.Attribute(
                                value=ast.Name(id="args", ctx=ast.Load()),
                                attr=(
                                    "posioned_portion"
                                    if key == "poisoned_portion"
                                    else key
                                ),
                                ctx=ast.Store(),
                            )
                        ],
                        value=platform_config_value(key),
                    )
                    for key in self.spec["config_keys"]
                    if key not in ("dataset", "trigger_type")
                ]
                updates.append(
                    ast.Assign(
                        targets=[
                            ast.Attribute(
                                value=ast.Name(id="args", ctx=ast.Load()),
                                attr="resume",
                                ctx=ast.Store(),
                            )
                        ],
                        value=ast.Constant(value=False),
                    )
                )
                return [node] + updates

        return node

    def visit_Expr(self, node):
        node = self.generic_visit(node)
        if (
            isinstance(node.value, ast.Call)
            and isinstance(node.value.func, ast.Attribute)
            and attr_name(node.value.func) == "plt.show"
        ):
            return ast.Pass()
        return node


def make_task_script(source_path, vab_root, output_root, config, spec, progress_path, run_id, attempt):
    try:
        source_tree = ast.parse(source_path.read_text(encoding="utf-8"), filename=str(source_path))
    except (OSError, SyntaxError) as exc:
        raise IntegrationError("unable to parse VaB source {}: {}".format(source_path, exc)) from exc

    source_tree = SingleTaskTransformer(spec).visit(source_tree)
    prelude_source = (
        "import sys as _platform_sys\n"
        "_platform_sys.path.insert(0, __PLATFORM_VAB_ROOT__)\n"
        "PLATFORM_OUTPUT_ROOT = __PLATFORM_OUTPUT_ROOT__\n"
        "PLATFORM_CONFIG = __PLATFORM_CONFIG__\n"
        "PLATFORM_PROGRESS_PATH = __PLATFORM_PROGRESS_PATH__\n"
        "PLATFORM_RUN_ID = __PLATFORM_RUN_ID__\n"
        "PLATFORM_ATTEMPT = __PLATFORM_ATTEMPT__\n"
        "def platform_report_epoch(epoch, total):\n"
        "    import json as _platform_json, os as _platform_os, tempfile as _platform_tempfile\n"
        "    total = max(1, int(total) - 1)\n"
        "    payload = {'run_id': PLATFORM_RUN_ID, 'attempt': PLATFORM_ATTEMPT, "
        "'phase': 'training', 'phase_label': 'LDD 双网络训练', "
        "'epoch': int(epoch), 'total_epochs': total, "
        "'progress': min(85, 15 + int((max(0, int(epoch) - 1) / total) * 70)), "
        "'message': '正在执行第 {}/{} 轮训练'.format(int(epoch), total)}\n"
        "    payload['heartbeat_at'] = __import__('datetime').datetime.now("
        "__import__('datetime').timezone.utc).isoformat()\n"
        "    directory = _platform_os.path.dirname(PLATFORM_PROGRESS_PATH)\n"
        "    descriptor, temporary = _platform_tempfile.mkstemp(dir=directory, "
        "prefix='.progress-', suffix='.tmp')\n"
        "    try:\n"
        "        with _platform_os.fdopen(descriptor, 'w', encoding='utf-8') as handle:\n"
        "            _platform_json.dump(payload, handle, ensure_ascii=False)\n"
        "        _platform_os.replace(temporary, PLATFORM_PROGRESS_PATH)\n"
        "    finally:\n"
        "        if _platform_os.path.exists(temporary): _platform_os.unlink(temporary)\n"
    )
    # Do not call str.format on this source: the embedded helper contains
    # dictionary literals and a format string of its own.
    prelude_source = prelude_source.replace("__PLATFORM_VAB_ROOT__", repr(str(vab_root)))
    prelude_source = prelude_source.replace("__PLATFORM_OUTPUT_ROOT__", repr(str(output_root)))
    prelude_source = prelude_source.replace("__PLATFORM_CONFIG__", repr(config))
    prelude_source = prelude_source.replace("__PLATFORM_PROGRESS_PATH__", repr(str(progress_path)))
    prelude_source = prelude_source.replace("__PLATFORM_RUN_ID__", repr(run_id))
    prelude_source = prelude_source.replace("__PLATFORM_ATTEMPT__", repr(attempt))
    prelude = ast.parse(prelude_source).body

    insert_at = 0
    while (
        insert_at < len(source_tree.body)
        and isinstance(source_tree.body[insert_at], ast.ImportFrom)
        and source_tree.body[insert_at].module == "__future__"
    ):
        insert_at += 1
    source_tree.body[insert_at:insert_at] = prelude
    source_tree = ast.fix_missing_locations(source_tree)

    try:
        generated = ast.unparse(source_tree)
        compile(generated, str(source_path), "exec")
    except (ValueError, SyntaxError) as exc:
        raise IntegrationError("unable to build single-task VaB script: {}".format(exc)) from exc

    generated_path = output_root / "generated_{}".format(source_path.name)
    generated_path.write_text(generated + "\n", encoding="utf-8")
    return generated_path


def build_command(script, config):
    return [
        sys.executable,
        str(script),
        "--data_path",
        str(config["data_path"]),
    ]


LOCK_STALE_SECONDS_DEFAULT = 6 * 60 * 60


def _pid_alive(pid):
    """Best-effort liveness probe that never signals the target process."""
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return False
    if pid <= 0:
        return False
    if os.name == "nt":
        import ctypes

        kernel32 = ctypes.windll.kernel32
        handle = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return False
        try:
            exit_code = ctypes.c_ulong()
            if kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code)) == 0:
                return False
            return exit_code.value == 259  # STILL_ACTIVE
        finally:
            kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def read_lock_metadata(lock_path):
    """Return the recorded lock metadata; empty dict when unreadable or corrupt."""
    try:
        raw = Path(lock_path).read_text(encoding="utf-8", errors="replace").strip()
    except FileNotFoundError:
        return None
    except OSError:
        return {}
    if not raw:
        return {}
    try:
        data = json.loads(raw)
    except ValueError:
        data = None
    if isinstance(data, dict):
        return data
    first_line = raw.splitlines()[0].strip()
    try:
        return {"pid": int(first_line), "token": None}
    except ValueError:
        return {}


def _lock_is_stale(lock_path, metadata, stale_after_seconds):
    pid = metadata.get("pid")
    if pid is not None and not _pid_alive(pid):
        return True
    if pid is not None:
        return False
    try:
        age = datetime.now(timezone.utc).timestamp() - Path(lock_path).stat().st_mtime
    except OSError:
        return False
    return age > stale_after_seconds


def acquire_lock(lock_path, task_id=None, run_id=None, stale_after_seconds=None):
    """Take the adapter lock, reclaiming it only when the recorded holder is dead.

    Returns the lease token so release can avoid deleting a successor's lock.
    """
    lock_path = Path(lock_path)
    if stale_after_seconds is None:
        try:
            stale_after_seconds = int(
                os.environ.get("SECURITY_MODEL_VAB_LOCK_STALE_SECONDS", LOCK_STALE_SECONDS_DEFAULT)
            )
        except ValueError:
            stale_after_seconds = LOCK_STALE_SECONDS_DEFAULT
    payload = {
        "version": 1,
        "pid": os.getpid(),
        "host": platform.node(),
        "task_id": None if task_id is None else str(task_id),
        "run_id": None if run_id is None else str(run_id),
        "token": uuid.uuid4().hex,
        "acquired_at": datetime.now(timezone.utc).isoformat(),
    }
    for _ in range(3):
        try:
            descriptor = os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            metadata = read_lock_metadata(lock_path)
            if metadata is None:
                continue
            holder = metadata.get("pid")
            if not _lock_is_stale(lock_path, metadata, stale_after_seconds):
                raise IntegrationError(
                    "GPU_LOCK_BUSY: another VaB task is using the shared GPU/runtime lock: "
                    "pid={}, task_id={}, run_id={}".format(
                        holder, metadata.get("task_id"), metadata.get("run_id")
                    ),
                    error_code="GPU_LOCK_BUSY",
                )
            quarantine = lock_path.with_name(
                "{}.stale.{}.{}".format(lock_path.name, os.getpid(), uuid.uuid4().hex)
            )
            try:
                os.replace(str(lock_path), str(quarantine))
            except OSError:
                continue
            moved = read_lock_metadata(quarantine)
            if moved is not None and moved.get("token") != metadata.get("token"):
                # Another process refreshed the lock between read and replace: give it back.
                try:
                    os.replace(str(quarantine), str(lock_path))
                except OSError:
                    pass
                raise IntegrationError(
                    "GPU_LOCK_BUSY: lock changed hands during stale reclaim",
                    error_code="GPU_LOCK_BUSY",
                )
            try:
                quarantine.unlink()
            except OSError:
                pass
            continue
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(payload, handle)
            handle.write("\n")
        return payload["token"]
    raise IntegrationError(
        "GPU_LOCK_BUSY: could not reclaim the VaB runtime lock after retries",
        error_code="GPU_LOCK_BUSY",
    )


def release_lock(lock_path, token):
    """Release only when the on-disk lock still belongs to this run."""
    metadata = read_lock_metadata(lock_path)
    if metadata is None:
        return
    if metadata.get("token") not in (None, token):
        return
    try:
        Path(lock_path).unlink()
    except OSError:
        pass


def classify_runtime_failure(output_text):
    """Map child-process output to a stable failure code without guessing."""
    lowered = (output_text or "").lower()
    if (
        "cuda out of memory" in lowered
        or "outofmemoryerror" in lowered
        or "cublas_status_alloc_failed" in lowered
        or "cudnn_status_alloc_failed" in lowered
    ):
        return "GPU_OOM"
    if (
        "no cuda-capable device" in lowered
        or "cuda driver version" in lowered
        or "torch not compiled with cuda" in lowered
        or "cuda_error_no_device" in lowered
        or "no device of requested type" in lowered
    ):
        return "CUDA_UNAVAILABLE"
    return "VAB_INTEGRATION_ERROR"


def parse_metrics(output_root):
    purity = {}
    selected_counts = {}
    logs = sorted(output_root.rglob("*.txt"))
    for log_path in logs:
        content = log_path.read_text(encoding="utf-8", errors="replace")
        for ratio, value in re.findall(
            r"Current isolation_ratio\s*:\s*(\d+).*?Clean_purity\s*:\s*([0-9.]+)%",
            content,
            flags=re.DOTALL,
        ):
            purity[ratio] = float(value)

    for index_path in sorted(output_root.rglob("selected_indices_*.npy")):
        match = re.search(r"selected_indices_(\d+)\.npy$", index_path.name)
        if not match:
            continue
        try:
            import numpy as np

            selected_counts[match.group(1)] = int(np.load(str(index_path)).size)
        except Exception:
            selected_counts[match.group(1)] = None
    return purity, selected_counts, logs


def task_output_root(result_path, request, algorithm_key):
    """Use a stable external artifact root when one is configured for VaB runs."""
    configured_root = os.environ.get("SECURITY_MODEL_VAB_RESULT_ROOT")
    if not configured_root:
        return result_path.parent / "artifacts"

    task_name = request.get("taskName") or "task-{}".format(request.get("taskId", "manual"))
    safe_task_name = re.sub(r"[^A-Za-z0-9._-]+", "-", task_name).strip("-")
    safe_algorithm = re.sub(r"[^A-Za-z0-9._-]+", "-", algorithm_key.rsplit("/", 1)[-1])
    run_name = "{}-{}-{}".format(
        datetime.now().strftime("%Y%m%d-%H%M%S"),
        safe_algorithm or "vab",
        safe_task_name or "task",
    )
    return Path(configured_root).expanduser().resolve() / run_name


def preflight(vab_root, source_path, config):
    if not vab_root.is_dir():
        raise IntegrationError("VAB_ROOT does not exist: {}".format(vab_root))
    if not source_path.is_file():
        raise IntegrationError("VaB algorithm source does not exist: {}".format(source_path))
    if not Path(config["data_path"]).is_dir():
        raise IntegrationError(
            "VaB data_path must be a directory containing the required dataset files: {}".format(
                config["data_path"]
            )
        )
    try:
        import torch
    except ImportError as exc:
        raise IntegrationError("PyTorch is not available in the configured Python environment") from exc
    if not torch.cuda.is_available():
        raise IntegrationError("VaB scripts use CUDA directly; an available CUDA device is required")


def build_result(status, message, started_at, **extra):
    result = {
        "executor": "python-vab-adapter",
        "stage": "pre_defense",
        "status": status,
        "message": message,
        "started_at": started_at,
        "finished_at": utc_now(),
        "artifacts": {},
        "runtime": collect_runtime_snapshot(),
    }
    result.update(extra)
    return result


def write_progress(path, payload):
    payload = dict(payload)
    payload["heartbeat_at"] = utc_now()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(".{}-{}.tmp".format(path.name, os.getpid()))
    temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    os.replace(str(temporary), str(path))


def write_children(children_path, pids):
    """Record spawned training pids so a restart can reason about the whole tree.

    The caller aborts the child if this record cannot be written; a missing record is
    treated as an unknown process tree by the Java recovery path, never as an empty tree.
    """
    payload = {"pids": [int(pid) for pid in pids], "updated_at": utc_now()}
    temporary = Path(str(children_path) + ".tmp")
    temporary.write_text(json.dumps(payload), encoding="utf-8")
    os.replace(str(temporary), str(children_path))


def mark_children_exited(children_path):
    """Keep positive evidence that the recorded child exited.

    Deleting the file made a normal success indistinguishable from a missing/corrupt
    record, which the runtime guard must treat as unknown. Write an empty pids array
    after the child has exited; only fall back to deletion if that write fails.
    """
    try:
        write_children(children_path, [])
    except OSError:
        try:
            Path(children_path).unlink()
        except OSError:
            pass


def run(args):
    started_at = utc_now()
    request_path = Path(args.request).resolve()
    result_path = Path(args.result).resolve()
    progress_path = Path(args.progress_file).resolve() if args.progress_file else result_path.with_name("progress.json")
    request = {}

    try:
        write_progress(progress_path, {
            "run_id": None, "attempt": None, "phase": "preparing",
            "phase_label": "检查运行环境", "progress": 5, "message": "正在检查算法、数据集与 CUDA 环境",
        })
        if args.stage != "pre_defense":
            raise IntegrationError("VaB adapter only supports stage=pre_defense")
        request = json.loads(request_path.read_text(encoding="utf-8"))
        write_progress(progress_path, {
            "run_id": request.get("runId"), "attempt": request.get("attempt"),
            "phase": "preparing", "phase_label": "准备训练任务", "progress": 10,
            "message": "运行环境检查完成，正在生成隔离任务脚本",
        })
        algorithm_key = get_algorithm_key(request)
        if algorithm_key not in SUPPORTED_ALGORITHMS:
            raise IntegrationError("unsupported VaB implementation path: {}".format(algorithm_key))
        spec = SUPPORTED_ALGORITHMS[algorithm_key]
        output_root = task_output_root(result_path, request, algorithm_key)
        vab_root_value = os.environ.get("VAB_ROOT")
        if not vab_root_value:
            raise IntegrationError("VAB_ROOT must point to the external VaB-main directory")
        vab_root = Path(vab_root_value).expanduser().resolve()
        source_path = vab_root / spec["source"]
        config = build_config(request, spec)
        preflight(vab_root, source_path, config)
        output_root.mkdir(parents=True, exist_ok=True)
        generated_script = make_task_script(
            source_path, vab_root, output_root, config, spec, progress_path,
            request.get("runId"), request.get("attempt"),
        )

        if args.validate_only:
            result = build_result(
                "success",
                "VaB preflight passed; generated single-task script was validated without training",
                started_at,
                run_id=request.get("runId"),
                attempt=request.get("attempt"),
                algorithm=algorithm_key,
                effective_config=config,
                artifacts={
                    "type": "vab-preflight",
                    "available": True,
                    "path": str(output_root),
                    "generated_script": str(generated_script),
                },
            )
        else:
            lock_path = vab_root / ".security-model-vab.lock"
            lock_token = acquire_lock(
                lock_path,
                task_id=request.get("taskId"),
                run_id=request.get("runId"),
            )
            try:
                execution_log = output_root / "execution.log"
                write_progress(progress_path, {
                    "run_id": request.get("runId"), "attempt": request.get("attempt"),
                    "phase": "training", "phase_label": "等待训练进程启动",
                    "progress": 12, "message": "LDD 训练进程已提交",
                })
                with execution_log.open("w", encoding="utf-8", buffering=1) as log_file:
                    child_environment = os.environ.copy()
                    child_environment["PYTHONUNBUFFERED"] = "1"
                    process = subprocess.Popen(
                        build_command(generated_script, config),
                        cwd=str(vab_root),
                        env=child_environment,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT,
                        text=True,
                        encoding="utf-8",
                        errors="replace",
                        bufsize=1,
                    )
                    children_path = result_path.with_name("python-children.json")
                    try:
                        write_children(children_path, [process.pid])
                    except OSError as record_error:
                        # A child that cannot be traced must not run on the shared GPU.
                        process.kill()
                        process.wait()
                        raise IntegrationError(
                            "无法记录训练子进程，已终止执行: {}".format(record_error)
                        )
                    try:
                        for line in process.stdout:
                            log_file.write(line)
                            print(line, end="", flush=True)
                        completed = subprocess.CompletedProcess(
                            args=process.args,
                            returncode=process.wait(),
                        )
                    finally:
                        # Only positive evidence after the child is really gone; a broken
                        # pipe must not leave an empty record while the child still runs.
                        try:
                            if process.poll() is None:
                                process.kill()
                            process.wait()
                        finally:
                            mark_children_exited(children_path)
            finally:
                release_lock(lock_path, lock_token)
            if completed.returncode != 0:
                log_text = ""
                try:
                    log_text = execution_log.read_text(encoding="utf-8", errors="replace")
                except OSError:
                    pass
                raise IntegrationError(
                    "VaB process exited with code {}".format(completed.returncode),
                    error_code=classify_runtime_failure(log_text),
                )

            purity, selected_counts, logs = parse_metrics(output_root)
            images = sorted(output_root.rglob("*.png"))
            model_files = sorted(output_root.rglob("*.pth"))
            result = build_result(
                "success",
                "VaB {} completed".format(Path(spec["source"]).stem),
                started_at,
                run_id=request.get("runId"),
                attempt=request.get("attempt"),
                algorithm=algorithm_key,
                effective_config=config,
                metrics={
                    "clean_purity_percent_by_isolation_ratio": purity,
                    "selected_sample_count_by_isolation_ratio": selected_counts,
                    "metric_note": (
                        "clean_purity uses VaB's known poisoned-sample labels and is intended "
                        "for experimental evaluation."
                    ),
                },
                artifacts={
                    "type": "vab-task-output",
                    "available": True,
                    "path": str(output_root),
                    "generated_script": str(generated_script),
                    "selection_kind": spec["selection_kind"],
                    "logs": [str(execution_log)] + [str(path) for path in logs],
                    "images": [str(path) for path in images],
                    "model_files": [str(path) for path in model_files],
                    "selection_files": [
                        str(path) for path in output_root.rglob("selected_indices_*.npy")
                    ],
                },
            )
            write_progress(progress_path, {
                "run_id": request.get("runId"), "attempt": request.get("attempt"),
                "phase": "finalizing", "phase_label": "整理实验结果",
                "progress": 90, "message": "训练完成，正在解析指标与实验产物",
            })
    except Exception as exc:
        result = build_result(
            "failed",
            "VaB task failed",
            started_at,
            run_id=request.get("runId") if "request" in locals() else None,
            attempt=request.get("attempt") if "request" in locals() else None,
            error_code=getattr(exc, "error_code", "VAB_INTEGRATION_ERROR"),
            failure_reason=str(exc),
        )

    if request:
        write_progress(progress_path, {
            "run_id": request.get("runId"), "attempt": request.get("attempt"),
            "phase": "completed" if result["status"] == "success" else "failed",
            "phase_label": "执行完成" if result["status"] == "success" else "执行失败",
            "progress": 100 if result["status"] == "success" else 0,
            "message": result.get("message") or result.get("failure_reason", "任务结束"),
        })
    result_path.parent.mkdir(parents=True, exist_ok=True)
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if result["status"] == "success" else 1


def export_clean_subset(args):
    """Rebuild a raw clean CIFAR-10 subset from LDD retained training indices."""
    selection_path = Path(args.selection_file).expanduser().resolve()
    dataset_root = Path(args.dataset_root).expanduser().resolve()
    output_path = Path(args.output).expanduser().resolve()
    metadata_path = Path(args.metadata).expanduser().resolve() if args.metadata else None
    try:
        if args.dataset.lower() != "cifar10":
            raise IntegrationError("clean subset export currently supports dataset=cifar10 only")
        if not selection_path.is_file():
            raise IntegrationError("selection file does not exist: {}".format(selection_path))
        if not dataset_root.is_dir():
            raise IntegrationError("dataset root does not exist: {}".format(dataset_root))

        import numpy as np

        selected_indices = np.load(str(selection_path))
        if selected_indices.ndim != 1 or selected_indices.size == 0:
            raise IntegrationError("selection file must contain a non-empty one-dimensional index array")
        if not np.issubdtype(selected_indices.dtype, np.integer):
            raise IntegrationError("selection file must contain integer indices")

        images, labels = load_cifar10_training_data(dataset_root, np)
        selected_indices = selected_indices.astype(np.int64, copy=False)
        if selected_indices.min() < 0 or selected_indices.max() >= len(images):
            raise IntegrationError(
                "selection indices must be within the CIFAR-10 training range [0, {}]".format(
                    len(images) - 1
                )
            )

        output_path.parent.mkdir(parents=True, exist_ok=True)
        metadata = {
            "format_version": 1,
            "generated_at": utc_now(),
            "dataset": "cifar10",
            "source_split": "train",
            "sample_count": int(selected_indices.size),
            "selection_file": str(selection_path),
            "selection_kind": "retained_clean_subset",
            "reconstruction_note": (
                "This archive reconstructs the raw clean CIFAR-10 training subset selected by "
                "LDD retained indices. It does not serialize the in-memory trigger-modified "
                "training samples used by the VaB experiment."
            ),
        }
        with zipfile.ZipFile(str(output_path), "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("metadata.json", json.dumps(metadata, ensure_ascii=False, indent=2))
            archive.writestr("selected_indices.npy", selection_path.read_bytes())

            index_stream = io.StringIO()
            csv.writer(index_stream).writerow(["position", "dataset_index"])
            index_writer = csv.writer(index_stream)
            for position, dataset_index in enumerate(selected_indices.tolist()):
                index_writer.writerow([position, dataset_index])
            archive.writestr("selected_indices.csv", index_stream.getvalue())

            label_stream = io.StringIO()
            label_writer = csv.writer(label_stream)
            label_writer.writerow(["position", "dataset_index", "label", "image_file"])
            for position, dataset_index in enumerate(selected_indices.tolist()):
                label = int(labels[dataset_index])
                image_name = "images/{:08d}_label_{}.png".format(dataset_index, label)
                archive.writestr(image_name, encode_png(images[dataset_index]))
                label_writer.writerow([position, dataset_index, label, image_name])
            archive.writestr("labels.csv", label_stream.getvalue())

        if metadata_path:
            metadata_path.parent.mkdir(parents=True, exist_ok=True)
            metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
        return 0
    except Exception as exc:
        print("clean subset export failed: {}".format(exc), file=sys.stderr)
        return 1


def load_cifar10_training_data(dataset_root, np):
    """Load CIFAR-10 Python batches without requiring torchvision."""
    batch_root = dataset_root / "cifar-10-batches-py"
    if not batch_root.is_dir():
        raise IntegrationError(
            "CIFAR-10 Python batches were not found under {}".format(batch_root)
        )
    image_batches = []
    labels = []
    for batch_number in range(1, 6):
        batch_path = batch_root / "data_batch_{}".format(batch_number)
        if not batch_path.is_file():
            raise IntegrationError("CIFAR-10 batch is missing: {}".format(batch_path))
        with batch_path.open("rb") as handle:
            batch = pickle.load(handle, encoding="bytes")
        data = batch.get(b"data")
        batch_labels = batch.get(b"labels")
        if data is None or batch_labels is None:
            raise IntegrationError("CIFAR-10 batch format is invalid: {}".format(batch_path))
        image_batches.append(
            np.asarray(data, dtype=np.uint8).reshape(-1, 3, 32, 32).transpose(0, 2, 3, 1)
        )
        labels.extend(batch_labels)
    return np.concatenate(image_batches, axis=0), labels


def encode_png(image):
    """Encode one CIFAR-10 RGB image as PNG using only standard-library modules."""
    if image.shape != (32, 32, 3):
        raise IntegrationError("expected CIFAR-10 RGB image shape (32, 32, 3)")
    raw = b"".join(b"\x00" + image[row].tobytes() for row in range(image.shape[0]))

    def png_chunk(chunk_type, data):
        return (
            struct.pack(">I", len(data))
            + chunk_type
            + data
            + struct.pack(">I", zlib.crc32(chunk_type + data) & 0xFFFFFFFF)
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 32, 32, 8, 2, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(raw))
        + png_chunk(b"IEND", b"")
    )


def main():
    parser = argparse.ArgumentParser(description="Security model platform VaB adapter")
    parser.add_argument("--stage")
    parser.add_argument("--request")
    parser.add_argument("--result")
    parser.add_argument("--progress-file")
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--export-clean-subset", action="store_true")
    parser.add_argument("--selection-file")
    parser.add_argument("--dataset-root")
    parser.add_argument("--dataset")
    parser.add_argument("--output")
    parser.add_argument("--metadata")
    args = parser.parse_args()
    if args.export_clean_subset:
        required = {
            "--selection-file": args.selection_file,
            "--dataset-root": args.dataset_root,
            "--dataset": args.dataset,
            "--output": args.output,
        }
        missing = [flag for flag, value in required.items() if not value]
        if missing:
            parser.error("export mode requires {}".format(", ".join(missing)))
        return export_clean_subset(args)
    if not args.stage or not args.request or not args.result:
        parser.error("task mode requires --stage, --request and --result")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
