"""Unit tests for the VaB adapter runtime lock and failure-code mapping."""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import algorithm_entry  # noqa: E402


class RuntimeLockTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.lock_path = Path(self.directory.name) / ".security-model-vab.lock"

    def tearDown(self):
        self.directory.cleanup()

    def test_acquire_writes_metadata_and_releases_with_token(self):
        token = algorithm_entry.acquire_lock(self.lock_path, task_id=7, run_id="run-7")

        metadata = json.loads(self.lock_path.read_text(encoding="utf-8"))
        self.assertEqual(os.getpid(), metadata["pid"])
        self.assertEqual(token, metadata["token"])
        self.assertEqual("7", metadata["task_id"])
        self.assertEqual("run-7", metadata["run_id"])

        algorithm_entry.release_lock(self.lock_path, token)
        self.assertFalse(self.lock_path.exists())

    def test_live_holder_blocks_with_gpu_lock_busy(self):
        metadata = {
            "version": 1,
            "pid": os.getpid(),
            "token": "held-by-current-process",
            "task_id": "1",
            "run_id": "run-1",
        }
        self.lock_path.write_text(json.dumps(metadata), encoding="utf-8")

        with self.assertRaises(algorithm_entry.IntegrationError) as context:
            algorithm_entry.acquire_lock(self.lock_path)

        self.assertEqual("GPU_LOCK_BUSY", context.exception.error_code)
        self.assertTrue(self.lock_path.exists())

    def test_dead_holder_is_reclaimed(self):
        metadata = {
            "version": 1,
            "pid": 999999999,
            "token": "dead-token",
            "task_id": "2",
            "run_id": "run-2",
        }
        self.lock_path.write_text(json.dumps(metadata), encoding="utf-8")

        token = algorithm_entry.acquire_lock(self.lock_path, task_id=3, run_id="run-3")

        self.assertNotEqual("dead-token", token)
        metadata = json.loads(self.lock_path.read_text(encoding="utf-8"))
        self.assertEqual(os.getpid(), metadata["pid"])
        self.assertEqual("3", metadata["task_id"])

    def test_legacy_pid_only_file_with_dead_pid_is_reclaimed(self):
        self.lock_path.write_text("999999999\n", encoding="utf-8")

        token = algorithm_entry.acquire_lock(self.lock_path, task_id=4, run_id="run-4")

        self.assertTrue(token)
        self.assertEqual(os.getpid(), algorithm_entry.read_lock_metadata(self.lock_path)["pid"])

    def test_release_with_foreign_token_keeps_lock(self):
        metadata = {"version": 1, "pid": 123456, "token": "successor"}
        self.lock_path.write_text(json.dumps(metadata), encoding="utf-8")

        algorithm_entry.release_lock(self.lock_path, "ours")

        self.assertTrue(self.lock_path.exists())

    def test_unreadable_lock_without_pid_uses_age_threshold(self):
        self.lock_path.write_text("garbage\n", encoding="utf-8")

        with self.assertRaises(algorithm_entry.IntegrationError) as context:
            algorithm_entry.acquire_lock(self.lock_path, stale_after_seconds=3600)

        self.assertEqual("GPU_LOCK_BUSY", context.exception.error_code)


class FailureCodeTest(unittest.TestCase):
    def test_cuda_out_of_memory(self):
        text = "RuntimeError: CUDA out of memory. Tried to allocate 2.00 GiB"
        self.assertEqual("GPU_OOM", algorithm_entry.classify_runtime_failure(text))

    def test_cudnn_alloc_failure_is_oom(self):
        self.assertEqual(
            "GPU_OOM",
            algorithm_entry.classify_runtime_failure("CUDNN_STATUS_ALLOC_FAILED"),
        )

    def test_cuda_driver_mismatch_is_unavailable(self):
        text = "RuntimeError: CUDA driver version is insufficient for CUDA runtime version"
        self.assertEqual("CUDA_UNAVAILABLE", algorithm_entry.classify_runtime_failure(text))

    def test_no_device_is_unavailable(self):
        self.assertEqual(
            "CUDA_UNAVAILABLE",
            algorithm_entry.classify_runtime_failure("AssertionError: Torch not compiled with CUDA enabled"),
        )

    def test_other_failures_keep_integration_error(self):
        self.assertEqual(
            "VAB_INTEGRATION_ERROR",
            algorithm_entry.classify_runtime_failure("FileNotFoundError: LDD.py"),
        )


if __name__ == "__main__":
    unittest.main()
