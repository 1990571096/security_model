import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--stage', required=True)
    parser.add_argument('--request', required=True)
    parser.add_argument('--result', required=True)
    parser.add_argument('--progress-file', required=True)
    args = parser.parse_args()

    request = json.loads(Path(args.request).read_text(encoding='utf-8'))
    task_id = request.get('taskId')
    run_id = request.get('runId')
    attempt = request.get('attempt')
    mode = os.environ.get('R4_PYTHON_MODE', 'success')
    marker = Path(os.environ.get('R4_PYTHON_MARKER', 'r4-python-invocations.jsonl'))
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker_line = {
        'taskId': task_id,
        'runId': run_id,
        'attempt': attempt,
        'mode': mode,
        'startedAt': utc_now(),
        'pid': os.getpid(),
    }
    with marker.open('a', encoding='utf-8') as handle:
        handle.write(json.dumps(marker_line, ensure_ascii=False) + '\n')
        handle.flush()
        os.fsync(handle.fileno())

    Path(args.progress_file).write_text(json.dumps({'progress': 10, 'stage': args.stage}), encoding='utf-8')
    task_dir = Path(args.progress_file).parent
    children_path = task_dir / 'python-children.json'
    def write_children():
        children_path.write_text(
            json.dumps({'pids': [], 'updated_at': utc_now()}, ensure_ascii=False),
            encoding='utf-8')
    write_children()
    if mode == 'crash':
        os._exit(9)
    if mode == 'sleep':
        time.sleep(int(os.environ.get('R4_PYTHON_SLEEP_SECONDS', '10')))
    if mode == 'fail':
        result = {
            'executor': 'r4-python-stub',
            'stage': args.stage,
            'message': 'injected failure',
            'status': 'failed',
            'error_code': 'R4_STUB_FAILED',
            'started_at': marker_line['startedAt'],
            'finished_at': utc_now(),
            'task_id': task_id,
            'run_id': run_id,
            'attempt': attempt,
            'artifacts': {},
        }
        Path(args.result).write_text(json.dumps(result, ensure_ascii=False), encoding='utf-8')
        sys.exit(0)

    result = {
        'executor': 'r4-python-stub',
        'stage': args.stage,
        'message': 'R4 short protocol stub',
        'status': 'success',
        'started_at': marker_line['startedAt'],
        'finished_at': utc_now(),
        'task_id': task_id,
        'run_id': run_id,
        'attempt': attempt,
        'artifacts': {'marker': str(marker)},
    }
    Path(args.result).write_text(json.dumps(result, ensure_ascii=False), encoding='utf-8')
    write_children()


if __name__ == '__main__':
    main()
