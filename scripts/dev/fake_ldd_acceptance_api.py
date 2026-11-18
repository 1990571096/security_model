#!/usr/bin/env python3
"""Small fake HTTP API for the LDD acceptance-run script tests.

It never starts Python/LDD and never touches a GPU. The scenario JSON controls the
task status, parameters, report availability and artifact paths.
"""
import argparse
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse


def load_scenario(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


class FakeState:
    def __init__(self, scenario):
        self.scenario = scenario
        self.started = False
        self.start_count = 0
        self.created = None


def send_json(handler, code, payload):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def task_detail(state):
    scenario = state.scenario
    params = scenario["params"]
    status = "success" if state.started else scenario.get("initial_status", "pending")
    progress = 100 if state.started else 0
    return {
        "taskId": str(scenario["task_id"]),
        "taskName": scenario.get("task_name", "fake LDD acceptance"),
        "description": "fake-api; no GPU",
        "taskType": "pre_defense",
        "status": status,
        "progress": progress,
        "datasetId": str(scenario.get("dataset_id", 6)),
        "algorithmId": str(scenario.get("algorithm_id", 14)),
        "customParams": json.dumps(params, separators=(",", ":")),
        "startTime": "2026-11-18T00:00:00",
        "createTime": "2026-11-18T00:00:00",
    }


def report_payload(state):
    scenario = state.scenario
    run_id = scenario["run_id"]
    params = scenario["params"]
    artifact_path = scenario.get("artifact_path", "")
    model_file = scenario.get("model_file", "")
    log_file = scenario.get("log_file", "")
    return {
        "taskInfo": {
            "taskId": str(scenario["task_id"]),
            "taskName": scenario.get("task_name", "fake LDD acceptance"),
            "status": "success",
            "progress": 100,
            "currentRunId": run_id,
            "currentAttempt": 1,
        },
        "cleaningResult": {
            "stage": "pre_defense",
            "run_id": run_id,
            "status": "success",
            "attempt": 1,
            "message": "fake LDD completed",
            "effective_config": params,
            "artifacts": {
                "type": "vab-task-output",
                "available": True,
                "path": artifact_path,
                "model_files": [model_file] if model_file else [],
                "logs": [log_file] if log_file else [],
                "selection_files": [],
                "images": [],
            },
        },
    }


def logs_payload(state):
    scenario = state.scenario
    run_id = scenario["run_id"]
    log_file = scenario.get("log_file", "")
    return {
        "taskId": str(scenario["task_id"]),
        "available": True,
        "logs": [
            {
                "name": "execution.log",
                "path": log_file,
                "relativePath": "execution.log",
                "content": "fake execution log",
                "size": 19,
                "truncated": False,
            }
        ],
        "incremental": False,
        "progress": {
            "run_id": run_id,
            "attempt": 1,
            "phase": "completed",
            "progress": 100,
            "message": "fake LDD completed",
        },
    }


def executor_status(state):
    return {
        "mode": "spring-boot-python",
        "poolSize": 1,
        "activeCount": 0,
        "corePoolSize": 2,
        "maxPoolSize": 4,
        "queueSize": 0,
        "queueCapacity": 20,
        "completedTaskCount": 1 if state.started else 0,
        "taskCount": 1 if state.started else 0,
        "saturated": False,
        "acceptingTasks": True,
        "runningTaskCount": 0,
        "maxRunningTasks": 4,
        "gpuSlot": {"slotId": "default-gpu-0", "state": "idle"},
    }


def dispatch_payload(state):
    return {
        "mode": "db",
        "role": "api",
        "outboxNew": 0,
        "outboxPublishing": 0,
        "outboxFailed": 0,
        "outboxPublished": 0,
        "outboxDead": 0,
        "outboxBacklog": 0,
        "expiredConsumptionClaims": 0,
        "consumptionProcessing": 0,
        "consumptionRetry": 0,
        "consumptionTerminal": 0,
        "outboxAvailable": True,
        "dispatchReady": True,
        "workerConsumerExpected": False,
        "runningTasks": 0,
        "runningTasksAvailable": True,
        "gpuSlot": {"available": True, "slotId": "default-gpu-0", "state": "idle"},
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "FakeLddAcceptance/1.0"

    def log_message(self, fmt, *args):
        pass

    def _path(self):
        return urlparse(self.path).path

    def do_GET(self):
        path = self._path()
        state = self.server.state
        if path == "/actuator/health/readiness":
            send_json(self, 200, {"status": "UP"})
        elif path == "/api/pre-defense/start-count":
            send_json(self, 200, {"start_count": state.start_count})
        elif path.startswith("/api/task-progress/pre_defense/"):
            send_json(self, 200, task_detail(state))
        elif path == "/api/task-progress/executor-status":
            send_json(self, 200, executor_status(state))
        elif path == "/api/observability/dispatch":
            send_json(self, 200, dispatch_payload(state))
        elif path.startswith("/api/pre-defense/report/"):
            if state.scenario.get("report_available", True):
                send_json(self, 200, report_payload(state))
            else:
                send_json(self, 404, {"message": "report unavailable"})
        elif path.startswith("/api/pre-defense/logs/"):
            send_json(self, 200, logs_payload(state))
        else:
            send_json(self, 404, {"message": "not found"})

    def do_POST(self):
        path = self._path()
        state = self.server.state
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw.decode("utf-8")) if raw else {}
        except Exception:
            send_json(self, 400, {"message": "invalid json"})
            return

        if path == "/api/pre-defense":
            state.created = body
            scenario = state.scenario
            send_json(self, 200, {
                "taskId": str(scenario["task_id"]),
                "taskName": body.get("taskName", scenario.get("task_name", "fake LDD acceptance")),
                "status": "pending",
                "progress": 0,
                "datasetId": str(body.get("datasetId", scenario.get("dataset_id", 6))),
                "algorithmId": str(body.get("algorithmId", scenario.get("algorithm_id", 14))),
                "customParams": body.get("customParams", ""),
                "ownerUserId": "1",
            })
        elif path.startswith("/api/pre-defense/start/"):
            state.started = True
            state.start_count += 1
            send_json(self, 200, {"message": "fake LDD start accepted", "taskId": str(state.scenario["task_id"])})
        else:
            send_json(self, 404, {"message": "not found"})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--port-file", default="")
    args = parser.parse_args()
    scenario = load_scenario(args.scenario)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.state = FakeState(scenario)
    if args.port_file:
        with open(args.port_file, "w", encoding="utf-8") as handle:
            handle.write(str(server.server_address[1]))
    print(f"fake LDD API on 127.0.0.1:{server.server_address[1]}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    sys.exit(main())
