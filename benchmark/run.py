"""
Benchmark main entry point.

Usage:
    python -m benchmark.run
    python -m benchmark.run --methods lxb vlm_react
    python -m benchmark.run --tasks taobao_s1 taobao_s2
    python -m benchmark.run --trials 5
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import os
import sys
import time
from datetime import datetime

# Ensure src/ is importable when running without pip install -e .
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from lxb_link import LXBLinkClient

from benchmark.config import (
    DEVICE_IP,
    DEVICE_PORT,
    TRIALS_PER_TASK,
    RESULTS_DIR,
    TEXT_LLM_MODEL,
    VLM_MODEL,
)
from benchmark.coord_calibration import calibrate_once
from benchmark.tasks import TASKS
from benchmark.runners.lxb_runner import LXBRunner
from benchmark.runners.vlm_react import VLMReActRunner
from benchmark.runners.text_react import TextReActRunner
from benchmark.runners.vlm_semantic_map import VLMSemanticMapRunner


RUNNERS = {
    "lxb": LXBRunner(),
    "vlm_react": VLMReActRunner(),
    "text_react": TextReActRunner(),
    "vlm_semantic_map": VLMSemanticMapRunner(),
}

_METHOD_MODEL = {
    "lxb": TEXT_LLM_MODEL,
    "text_react": TEXT_LLM_MODEL,
    "vlm_react": VLM_MODEL,
    "vlm_semantic_map": VLM_MODEL,
}


def _append_result(result, filepath: str) -> None:
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, "a", encoding="utf-8") as f:
        f.write(json.dumps(dataclasses.asdict(result), ensure_ascii=False) + "\n")


def _connect() -> LXBLinkClient:
    client = LXBLinkClient(DEVICE_IP, DEVICE_PORT)
    client.connect()
    client.handshake()
    return client


def run_benchmark(
    methods: list[str],
    task_ids: list[str] | None,
    trials: int,
    run_id: str,
    out_dir: str,
) -> None:
    selected_tasks = [t for t in TASKS if task_ids is None or t.task_id in task_ids]
    if not selected_tasks:
        print("No tasks matched the filter.")
        return

    selected_runners = {k: v for k, v in RUNNERS.items() if k in methods}
    if not selected_runners:
        print("No valid methods specified.")
        return

    total = len(selected_tasks) * len(selected_runners) * trials
    done = 0
    results_file = os.path.join(out_dir, f"{run_id}.jsonl")

    print(f"\n{'=' * 60}")
    print(
        f"  Benchmark: {len(selected_tasks)} tasks x "
        f"{len(selected_runners)} methods x {trials} trials"
    )
    print(f"  Device:    {DEVICE_IP}:{DEVICE_PORT}")
    print(f"  Run ID:    {run_id}")
    print(f"  Output:    {results_file}")
    print(f"{'=' * 60}\n")

    client = _connect()
    print("Connected to device.\n")

    calib = calibrate_once(verbose=True)
    if calib:
        print(
            f"[coord] calibration mode={calib.get('mode')} "
            f"range=({calib.get('max_x')},{calib.get('max_y')})"
        )
    else:
        print("[coord] calibration unavailable, fallback to passthrough mapping")

    try:
        for task in selected_tasks:
            for method_name, runner in selected_runners.items():
                for trial in range(1, trials + 1):
                    done += 1
                    print(
                        f"[{done}/{total}] {method_name:12s} | "
                        f"{task.task_id:18s} | trial {trial}/{trials} ...",
                        end=" ",
                        flush=True,
                    )

                    t_start = time.perf_counter()
                    try:
                        result = runner.run(client, task, trial)
                    except Exception as exc:
                        from benchmark.runners.base import RunResult

                        result = RunResult(
                            method=method_name,
                            model=_METHOD_MODEL.get(method_name, ""),
                            task_id=task.task_id,
                            trial=trial,
                            success=False,
                            error=f"runner_crash: {exc}",
                        )

                    elapsed = time.perf_counter() - t_start
                    status = "OK" if result.success else "FAIL"
                    print(
                        f"{status}  "
                        f"llm={result.llm_calls} vlm={result.vlm_calls} "
                        f"steps={result.steps}  "
                        f"lat={elapsed:.1f}s  "
                        f"cost=${result.estimated_cost:.5f}"
                    )

                    _append_result(result, results_file)

                    if trial < trials:
                        time.sleep(2.0)

    finally:
        client.disconnect()
        print("\nDisconnected. Results saved to:", results_file)


def main() -> None:
    parser = argparse.ArgumentParser(description="LXB Navigation Benchmark")
    parser.add_argument(
        "--methods",
        nargs="+",
        default=list(RUNNERS.keys()),
        choices=list(RUNNERS.keys()),
        help="Which methods to run (default: all)",
    )
    parser.add_argument(
        "--tasks",
        nargs="+",
        default=None,
        metavar="TASK_ID",
        help="Specific task IDs to run (default: all)",
    )
    parser.add_argument(
        "--trials",
        type=int,
        default=TRIALS_PER_TASK,
        help=f"Trials per task (default: {TRIALS_PER_TASK})",
    )
    parser.add_argument(
        "--run-id",
        default=None,
        help="Identifier for this run (default: timestamp)",
    )
    parser.add_argument(
        "--out-dir",
        default=RESULTS_DIR,
        help=f"Result output directory (default: {RESULTS_DIR})",
    )

    args = parser.parse_args()
    run_id = args.run_id or datetime.now().strftime("%Y%m%d_%H%M%S")

    run_benchmark(
        methods=args.methods,
        task_ids=args.tasks,
        trials=args.trials,
        run_id=run_id,
        out_dir=args.out_dir,
    )


if __name__ == "__main__":
    main()
