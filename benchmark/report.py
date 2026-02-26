"""
Benchmark result aggregation and reporting.

Usage:
    python -m benchmark.report
    python -m benchmark.report --file benchmark/results/<run_id>.jsonl
    python -m benchmark.report --csv benchmark/summary.csv
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from collections import defaultdict
from statistics import mean, stdev

from benchmark.config import RESULTS_DIR


def _resolve_results_file(path_or_none: str | None) -> str | None:
    if path_or_none:
        p = Path(path_or_none)
        if p.is_dir():
            files = sorted(p.glob("*.jsonl"), key=lambda x: x.stat().st_mtime, reverse=True)
            return str(files[0]) if files else None
        return str(p)

    d = Path(RESULTS_DIR)
    files = sorted(d.glob("*.jsonl"), key=lambda x: x.stat().st_mtime, reverse=True) if d.exists() else []
    return str(files[0]) if files else None


def load_results(filepath: str) -> list[dict]:
    results = []
    with open(filepath, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                results.append(json.loads(line))
    return results


def aggregate(results: list[dict]) -> dict:
    """
    Group results by (method, task_id) and compute per-group statistics.
    Returns a nested dict: stats[method][task_id] = {...metrics...}
    """
    groups: dict[tuple, list[dict]] = defaultdict(list)
    for r in results:
        groups[(r["method"], r["task_id"])].append(r)

    stats = {}
    for (method, task_id), trials in groups.items():
        n = len(trials)
        successes = [t for t in trials if t["success"]]
        sr = len(successes) / n

        def avg(key):
            vals = [t[key] for t in trials if t[key] is not None]
            return round(mean(vals), 3) if vals else None

        def sd(key):
            vals = [t[key] for t in trials if t[key] is not None]
            return round(stdev(vals), 3) if len(vals) > 1 else 0.0

        stats.setdefault(method, {})[task_id] = {
            "trials":           n,
            "success_rate":     round(sr, 3),
            "avg_llm_calls":    avg("llm_calls"),
            "avg_vlm_calls":    avg("vlm_calls"),
            "total_calls_avg":  round((avg("llm_calls") or 0) + (avg("vlm_calls") or 0), 2),
            "avg_steps":        avg("steps"),
            "avg_latency_sec":  avg("latency_sec"),
            "sd_latency_sec":   sd("latency_sec"),
            "avg_cost_usd":     avg("estimated_cost"),
            "avg_input_tokens": avg("input_tokens"),
            "avg_output_tokens": avg("output_tokens"),
        }
    return stats


def print_table(stats: dict) -> None:
    methods  = sorted(stats.keys())
    all_tasks = sorted({tid for m in stats.values() for tid in m})

    W = 100
    # ── Per-task detail ───────────────────────────────────────────────────────
    print("\n" + "=" * W)
    print("PER-TASK RESULTS")
    print("=" * W)
    header = (
        f"{'Task':<22} {'Method':<14} {'SR':>6} {'LLM':>5} {'VLM':>5}"
        f" {'Calls':>6} {'Steps':>6} {'Lat(s)':>8} {'In-tok':>8} {'Out-tok':>8} {'Total-tok':>10}"
    )
    print(header)
    print("-" * W)

    for task_id in all_tasks:
        for method in methods:
            s = stats.get(method, {}).get(task_id)
            if s is None:
                continue
            total_tok = (s["avg_input_tokens"] or 0) + (s["avg_output_tokens"] or 0)
            print(
                f"{task_id:<22} {method:<14} "
                f"{s['success_rate']:>6.2f} "
                f"{s['avg_llm_calls']:>5.1f} "
                f"{s['avg_vlm_calls']:>5.1f} "
                f"{s['total_calls_avg']:>6.1f} "
                f"{s['avg_steps']:>6.1f} "
                f"{s['avg_latency_sec']:>8.1f} "
                f"{s['avg_input_tokens']:>8.0f} "
                f"{s['avg_output_tokens']:>8.0f} "
                f"{total_tok:>10.0f}"
            )
        print()

    # ── Aggregate by method ───────────────────────────────────────────────────
    print("=" * W)
    print("AGGREGATE BY METHOD (mean across all tasks)")
    print("=" * W)
    print(header)
    print("-" * W)

    for method in methods:
        task_stats = list(stats[method].values())
        if not task_stats:
            continue

        def gmean(key):
            vals = [s[key] for s in task_stats if s.get(key) is not None]
            return round(mean(vals), 3) if vals else None

        g_in  = gmean("avg_input_tokens")  or 0
        g_out = gmean("avg_output_tokens") or 0
        print(
            f"{'ALL':22} {method:<14} "
            f"{gmean('success_rate'):>6.2f} "
            f"{gmean('avg_llm_calls'):>5.1f} "
            f"{gmean('avg_vlm_calls'):>5.1f} "
            f"{gmean('total_calls_avg'):>6.1f} "
            f"{gmean('avg_steps'):>6.1f} "
            f"{gmean('avg_latency_sec'):>8.1f} "
            f"{g_in:>8.0f} "
            f"{g_out:>8.0f} "
            f"{g_in + g_out:>10.0f}"
        )

    # ── Breakdown by depth ────────────────────────────────────────────────────
    from benchmark.tasks import TASKS
    depth_map = {t.task_id: t.depth for t in TASKS}

    print("\n" + "=" * W)
    print("AGGREGATE BY DEPTH × METHOD")
    print("=" * W)
    print(f"{'Depth':<10} {header}")
    print("-" * W)

    for depth in ("shallow", "medium", "deep"):
        depth_tasks = [tid for tid, d in depth_map.items() if d == depth]
        for method in methods:
            depth_stats = [s for tid, s in stats.get(method, {}).items() if tid in depth_tasks]
            if not depth_stats:
                continue

            def dm(key):
                vals = [s[key] for s in depth_stats if s.get(key) is not None]
                return round(mean(vals), 3) if vals else 0.0

            d_in  = dm("avg_input_tokens")
            d_out = dm("avg_output_tokens")
            print(
                f"{depth:<10} {' ':<22} {method:<14} "
                f"{dm('success_rate'):>6.2f} "
                f"{dm('avg_llm_calls'):>5.1f} "
                f"{dm('avg_vlm_calls'):>5.1f} "
                f"{dm('total_calls_avg'):>6.1f} "
                f"{dm('avg_steps'):>6.1f} "
                f"{dm('avg_latency_sec'):>8.1f} "
                f"{d_in:>8.0f} "
                f"{d_out:>8.0f} "
                f"{d_in + d_out:>10.0f}"
            )
        print()


def save_csv(stats: dict, filepath: str) -> None:
    import csv
    rows = []
    from benchmark.tasks import TASKS
    depth_map = {t.task_id: t.depth for t in TASKS}
    app_map   = {t.task_id: t.app_name for t in TASKS}

    for method, tasks in stats.items():
        for task_id, s in tasks.items():
            rows.append({
                "method":          method,
                "task_id":         task_id,
                "app":             app_map.get(task_id, ""),
                "depth":           depth_map.get(task_id, ""),
                "trials":          s["trials"],
                "success_rate":    s["success_rate"],
                "avg_llm_calls":   s["avg_llm_calls"],
                "avg_vlm_calls":   s["avg_vlm_calls"],
                "total_calls":     s["total_calls_avg"],
                "avg_steps":       s["avg_steps"],
                "avg_latency_s":   s["avg_latency_sec"],
                "avg_in_tokens":   s["avg_input_tokens"],
                "avg_out_tokens":  s["avg_output_tokens"],
                "avg_total_tokens": (s["avg_input_tokens"] or 0) + (s["avg_output_tokens"] or 0),
            })

    os.makedirs(os.path.dirname(filepath) or ".", exist_ok=True)
    with open(filepath, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"\nCSV saved to: {filepath}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Aggregate benchmark results")
    parser.add_argument(
        "--file",
        default=None,
        help="Path to JSONL results file. If omitted, uses latest file under RESULTS_DIR.",
    )
    parser.add_argument(
        "--csv",
        default=None,
        metavar="PATH",
        help="Save aggregated stats to CSV",
    )
    args = parser.parse_args()

    result_file = _resolve_results_file(args.file)
    if not result_file or not os.path.exists(result_file):
        print(f"Results file not found. --file={args.file!r}, RESULTS_DIR={RESULTS_DIR!r}")
        return

    results = load_results(result_file)
    print(f"Loaded {len(results)} result records from: {result_file}")

    stats = aggregate(results)
    print_table(stats)

    if args.csv:
        save_csv(stats, args.csv)


if __name__ == "__main__":
    main()
