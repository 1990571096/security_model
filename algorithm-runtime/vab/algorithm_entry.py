#!/usr/bin/env python
"""Platform adapter for the external VaB training-pre-defense project."""

import argparse
import ast
import json
import os
import re
import subprocess
import sys
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
    "vab://pre-defense/vs-dcm": {
        "source": "VS_DCM.py",
        "config_keys": (
            "dataset",
            "trigger_type",
            "trigger_label",
            "poisoned_mode",
            "poisoned_portion",
            "batch_size",
            "tuning_epochs",
            "lr",
            "momentum",
            "weight_decay",
            "gamma",
            "seed",
        ),
        "forced_args": ("batch_size", "tuning_epochs"),
        "selection_variable": "perm",
        "selection_kind": "retained_clean_subset",
    },
    "vab://pre-defense/vs-abl": {
        "source": "VS_ABL.py",
        "config_keys": (
            "dataset",
            "trigger_type",
            "trigger_label",
            "poisoned_mode",
            "poisoned_portion",
            "batch_size",
            "tuning_epochs",
            "num_epochs",
            "lr",
            "lr_finetuning_init",
            "momentum",
            "weight_decay",
            "gradient_ascent_type",
            "gamma",
            "flooding",
            "threshold_clean",
            "threshold_bad",
            "r",
            "seed",
        ),
        "forced_args": ("batch_size", "tuning_epochs"),
        "selection_variable": "perm",
        "selection_kind": "retained_clean_subset",
    },
    "vab://pre-defense/vs-meta-sift": {
        "source": "VS_Meta_Sift.py",
        "config_keys": (
            "dataset",
            "trigger_type",
            "trigger_label",
            "poisoned_mode",
            "poisoned_portion",
            "batch_size",
            "lr",
            "momentum",
            "weight_decay",
            "isolation_ratio",
            "seed",
            "num_sifter",
            "res_epochs",
            "warmup_epochs",
            "num_workers",
            "v_lr",
            "meta_lr",
            "top_k",
            "go_lr",
            "num_act",
            "nesterov",
        ),
        "forced_args": ("batch_size",),
        "selection_variable": "new_idx",
        "selection_kind": "retained_clean_subset",
    },
    "vab://pre-defense/vs-mi-dcm": {
        "source": "VS_MI_DCM.py",
        "config_keys": (
            "dataset",
            "trigger_type",
            "trigger_label",
            "poisoned_mode",
            "poisoned_portion",
            "batch_size",
            "tuning_epochs",
            "lr",
            "momentum",
            "weight_decay",
            "isolation_ratio",
            "r",
            "seed",
        ),
        "forced_args": ("batch_size", "tuning_epochs"),
        "selection_variable": "perm",
        "selection_kind": "retained_clean_subset",
    },
    "vab://pre-defense/vs-spectral-signature": {
        "source": "VS_Spectral Signature.py",
        "config_keys": (
            "dataset",
            "trigger_type",
            "trigger_label",
            "poisoned_mode",
            "poisoned_portion",
            "batch_size",
            "tuning_epochs",
            "lr",
            "momentum",
            "weight_decay",
            "isolation_ratio",
            "r",
            "seed",
        ),
        "forced_args": ("batch_size", "tuning_epochs"),
        "selection_variable": "perm",
        "selection_kind": "retained_clean_subset",
    },
    "vab://pre-defense/vs-spectre": {
        "source": "VS_Spectre.py",
        "config_keys": (
            "dataset",
            "trigger_type",
            "trigger_label",
            "poisoned_mode",
            "poisoned_portion",
            "batch_size",
            "tuning_epochs",
            "lr",
            "momentum",
            "weight_decay",
            "isolation_ratio",
            "r",
            "seed",
        ),
        "forced_args": ("batch_size", "tuning_epochs"),
        "selection_variable": "perm",
        "selection_kind": "retained_clean_subset",
    },
    "vab://pre-defense/vs-telltale": {
        "source": "VS_Telltale.py",
        "config_keys": (
            "dataset",
            "trigger_type",
            "trigger_label",
            "poisoned_mode",
            "poisoned_portion",
            "batch_size",
            "tuning_epochs",
            "lr",
            "momentum",
            "weight_decay",
            "isolation_ratio",
            "r",
            "seed",
        ),
        "forced_args": ("batch_size", "tuning_epochs"),
        "selection_variable": "perm",
        "selection_kind": "retained_clean_subset",
    },
}

ALGORITHM_ALIASES = {
    "vab://ldd": "vab://pre-defense/ldd",
    "vab://vs-dcm": "vab://pre-defense/vs-dcm",
    "vab://vs-abl": "vab://pre-defense/vs-abl",
    "vab://vs-meta-sift": "vab://pre-defense/vs-meta-sift",
    "vab://vs-mi-dcm": "vab://pre-defense/vs-mi-dcm",
    "vab://vs-spectral-signature": "vab://pre-defense/vs-spectral-signature",
    "vab://vs-spectre": "vab://pre-defense/vs-spectre",
    "vab://vs-telltale": "vab://pre-defense/vs-telltale",
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
    output_root = result_path.parent / "artifacts"

    try:
        if args.stage != "pre_defense":
            raise IntegrationError("VaB adapter only supports stage=pre_defense")
        request = json.loads(request_path.read_text(encoding="utf-8"))
        algorithm_key = get_algorithm_key(request)
        if algorithm_key not in SUPPORTED_ALGORITHMS:
            raise IntegrationError("unsupported VaB implementation path: {}".format(algorithm_key))
        spec = SUPPORTED_ALGORITHMS[algorithm_key]
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
                completed = subprocess.run(
                    build_command(generated_script, config),
                    cwd=str(vab_root),
                    check=False,
                )
            finally:
                if lock_path.exists():
                    lock_path.unlink()
            if completed.returncode != 0:
                raise IntegrationError("VaB process exited with code {}".format(completed.returncode))

            purity, selected_counts, logs = parse_metrics(output_root)
            result = build_result(
                "success",
                "VaB {} completed".format(Path(spec["source"]).stem),
                started_at,
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
                    "logs": [str(path) for path in logs],
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
            error_code="VAB_INTEGRATION_ERROR",
            failure_reason=str(exc),
        )

    result_path.parent.mkdir(parents=True, exist_ok=True)
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if result["status"] == "success" else 1


def main():
    parser = argparse.ArgumentParser(description="Security model platform VaB adapter")
    parser.add_argument("--stage", required=True)
    parser.add_argument("--request", required=True)
    parser.add_argument("--result", required=True)
    parser.add_argument("--validate-only", action="store_true")
    return run(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
