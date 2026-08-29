#!/usr/bin/env python
"""Platform adapter for the external VaB training-pre-defense project."""

import argparse
import ast
import csv
import io
import json
import os
import pickle
import re
import struct
import subprocess
import sys
import zipfile
import zlib
from datetime import datetime, timezone
from pathlib import Path


class IntegrationError(Exception):
    """Raised for a platform/VaB integration contract failure."""


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


def utc_now():
    return datetime.now(timezone.utc).isoformat()


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


def make_task_script(source_path, vab_root, output_root, config, spec):
    try:
        source_tree = ast.parse(source_path.read_text(encoding="utf-8"), filename=str(source_path))
    except (OSError, SyntaxError) as exc:
        raise IntegrationError("unable to parse VaB source {}: {}".format(source_path, exc)) from exc

    source_tree = SingleTaskTransformer(spec).visit(source_tree)
    prelude = ast.parse(
        "import sys as _platform_sys\n"
        "_platform_sys.path.insert(0, {!r})\n"
        "PLATFORM_OUTPUT_ROOT = {!r}\n"
        "PLATFORM_CONFIG = {!r}\n".format(str(vab_root), str(output_root), config)
    ).body

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


def acquire_lock(lock_path):
    try:
        descriptor = os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError as exc:
        raise IntegrationError(
            "another VaB task is already using the shared GPU/runtime lock: {}".format(lock_path)
        ) from exc
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write("{}\n".format(os.getpid()))


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
    }
    result.update(extra)
    return result


def run(args):
    started_at = utc_now()
    request_path = Path(args.request).resolve()
    result_path = Path(args.result).resolve()

    try:
        if args.stage != "pre_defense":
            raise IntegrationError("VaB adapter only supports stage=pre_defense")
        request = json.loads(request_path.read_text(encoding="utf-8"))
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
        generated_script = make_task_script(source_path, vab_root, output_root, config, spec)

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
            acquire_lock(lock_path)
            try:
                execution_log = output_root / "execution.log"
                with execution_log.open("w", encoding="utf-8") as log_file:
                    completed = subprocess.run(
                        build_command(generated_script, config),
                        cwd=str(vab_root),
                        check=False,
                        stdout=log_file,
                        stderr=subprocess.STDOUT,
                    )
            finally:
                if lock_path.exists():
                    lock_path.unlink()
            if completed.returncode != 0:
                raise IntegrationError("VaB process exited with code {}".format(completed.returncode))

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
    except Exception as exc:
        result = build_result(
            "failed",
            "VaB task failed",
            started_at,
            run_id=request.get("runId") if "request" in locals() else None,
            attempt=request.get("attempt") if "request" in locals() else None,
            error_code="VAB_INTEGRATION_ERROR",
            failure_reason=str(exc),
        )

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
