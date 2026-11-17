#!/usr/bin/env python3
"""Scripted OpenAI-compatible model for the isolated assistant browser fixture.

It deliberately returns one tool call on the first turn and a plain final answer on the
second turn. No external model or credential is used.

Run:
    python scripts/dev/assistant_browser_fake_model.py --port 8090

The captured request bodies are written to tmp/browser-fixture/model-requests.jsonl.
"""

import argparse
import json
import os
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()
REQUESTS = []


def tool_call(name, arguments):
    return {
        "model": "fake-browser-model",
        "choices": [{
            "message": {
                "content": None,
                "tool_calls": [{
                    "id": "call_browser_1",
                    "type": "function",
                    "function": {"name": name, "arguments": json.dumps(arguments, ensure_ascii=False)},
                }],
            }
        }],
    }


def answer(content):
    return {"model": "fake-browser-model", "choices": [{"message": {"content": content}}]}


def last_user_text(body):
    for message in reversed(body.get("messages", [])):
        if message.get("role") == "user":
            return message.get("content") or ""
    return ""


def tool_names(body):
    names = []
    for tool in body.get("tools", []):
        function = tool.get("function", {})
        if function.get("name"):
            names.append(function["name"])
    return names


def has_tool_result(body):
    return any(
        message.get("role") == "tool"
        or (message.get("role") == "user" and "工具结果" in (message.get("content") or ""))
        for message in body.get("messages", [])
    )


def last_tool_name(body):
    for message in reversed(body.get("messages", [])):
        content = message.get("content") or ""
        match = re.search(r'"tool"\s*:\s*"([^"]+)"', content)
        if match:
            return match.group(1)
        match = re.search(r"tool\s+([\w_]+)\s+已执行", content)
        if match:
            return match.group(1)
    return ""


def requested_task_id(text, default=2121793576624254978):
    """Use the oversized fixture ID named anywhere in the request, if any."""
    match = re.search(r"\b(9\d{15})\b", text)
    return int(match.group(1)) if match else default


def all_text(body):
    return "\n".join(str(message.get("content") or "") for message in body.get("messages", []))


def choose_reply(body):
    question = last_user_text(body)
    names = tool_names(body)
    task_id = requested_task_id(all_text(body))
    if "模型故障" in question:
        return 503, {"error": "scripted model outage"}
    if has_tool_result(body):
        if ("确认" in question or "预览启动" in question) and "preview_start_task" in names:
            last_tool = last_tool_name(body)
            if last_tool != "preview_start_task":
                return 200, tool_call("preview_start_task", {"taskId": task_id})
        return 200, answer("已根据服务端工具证据完成本轮说明。")

    if "越界" in question or "越权" in question:
        return 200, tool_call("get_my_report_summary", {"taskId": 1})

    if "get_my_task_summary" in names and "查看任务" in question:
        return 200, tool_call("get_my_task_summary", {"taskId": task_id})

    if "validate_ldd_parameters" in names:
        custom = {}
        if "unknown-dataset" in question:
            custom = {"dataset": "unknown"}
        elif "unknown-trigger" in question:
            custom = {"trigger_type": "unknown"}
        elif "bad-type" in question:
            custom = {"dataset": 123}
        elif "bad-boolean" in question:
            custom = {"dataset": True}
        elif "bad-object" in question:
            custom = {"dataset": {"nested": "value"}}
        elif "batch_size=999" in question:
            custom = {"batch_size": 999}
        return 200, tool_call("validate_ldd_parameters", {
            "datasetId": 1,
            "algorithmId": 14,
            "customParams": custom,
        })

    if "get_my_task_summary" in names:
        # The seeded pending task is owned by admin. Its executor state is unknown in
        # rabbit/api mode, which is exactly the worker_unknown case under test.
        return 200, tool_call("get_my_task_summary", {"taskId": task_id})

    if "get_my_report_summary" in names:
        # The pending, admin-owned task has no cleaning_result; this exercises the
        # "metrics unavailable" path without touching a demo/foreign row.
        return 200, tool_call("get_my_report_summary", {"taskId": task_id})

    return 200, answer("脚本化模型没有匹配到可调用工具。")


class Handler(BaseHTTPRequestHandler):
    server_version = "AssistantFakeModel/1.0"

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8", "replace")
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            self.send_error(400, "invalid JSON")
            return
        with LOCK:
            REQUESTS.append(body)
            out_dir = os.path.join(os.getcwd(), "tmp", "browser-fixture")
            os.makedirs(out_dir, exist_ok=True)
            with open(os.path.join(out_dir, "model-requests.jsonl"), "a", encoding="utf-8") as handle:
                handle.write(json.dumps(body, ensure_ascii=False) + "\n")
        status, payload = choose_reply(body)
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        print("[fake-model] " + fmt % args, flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8090)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"[fake-model] listening on http://127.0.0.1:{args.port}/chat/completions", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
