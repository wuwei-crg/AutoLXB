#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generate charts from benchmark results - Simplified version.
"""

import json
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
from pathlib import Path
from collections import defaultdict

# Set UTF-8 encoding for stdout
import sys
import io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

# Read results
results_file = Path(__file__).parent / "results" / "20260226_110543.jsonl"
results = []
with open(results_file, encoding='utf-8') as f:
    for line in f:
        if line.strip():
            results.append(json.loads(line))

# Task categories
shallow_tasks = ["tb_s1", "tb_s3", "bili_s1"]  # Direct from home
deep_tasks = ["tb_d1", "tb_d2", "bili_m1", "bili_m3", "bili_d2"]  # >=2 hops

methods = ["LXB-Route", "VLM-ReAct", "Text-ReAct", "VLM+SemanticMap"]
method_colors = {"LXB-Route": "#2E7D32", "VLM-ReAct": "#1976D2", "Text-ReAct": "#F57C00", "VLM+SemanticMap": "#7B1FA2"}
method_labels = {
    "LXB-Route": "LXB-Route\n(Ours)",
    "VLM-ReAct": "VLM-ReAct",
    "Text-ReAct": "Text-ReAct",
    "VLM+SemanticMap": "VLM+Map",
}

# Output directory
output_dir = Path(__file__).parent / "figures"
output_dir.mkdir(exist_ok=True)

# Set style
plt.rcParams['font.size'] = 11
plt.rcParams['axes.labelsize'] = 12
plt.rcParams['axes.titlesize'] = 13
plt.rcParams['legend.fontsize'] = 10
plt.rcParams['figure.titlesize'] = 14
plt.rcParams['font.family'] = 'DejaVu Sans'

# Calculate statistics
def calc_stats(task_list):
    stats_by_method = {}
    for method in methods:
        method_runs = [r for r in results if r["method"] == method and r["task_id"] in task_list]
        if not method_runs:
            continue

        success_runs = [r for r in method_runs if r["success"]]
        # Exclude token=0 runs (abnormal failures) for token averaging
        valid_token_runs = [r for r in method_runs if (r["input_tokens"] + r["output_tokens"]) > 0]

        stats_by_method[method] = {
            "success_rate": len(success_runs) / len(method_runs),
            "avg_latency": np.mean([r["latency_sec"] for r in method_runs]),
            "avg_vlm_calls": np.mean([r["vlm_calls"] for r in method_runs]),
            "avg_llm_calls": np.mean([r["llm_calls"] for r in method_runs]),
            "avg_total_calls": np.mean([r["vlm_calls"] + r["llm_calls"] for r in method_runs]),
            "avg_input_tokens": np.mean([r["input_tokens"] for r in valid_token_runs]) if valid_token_runs else 0,
            "avg_output_tokens": np.mean([r["output_tokens"] for r in valid_token_runs]) if valid_token_runs else 0,
            "avg_total_tokens": np.mean([r["input_tokens"] + r["output_tokens"] for r in valid_token_runs]) if valid_token_runs else 0,
        }
    return stats_by_method

shallow_stats = calc_stats(shallow_tasks)
deep_stats = calc_stats(deep_tasks)

# =============================================================================
# Figure 1: Success Rate Comparison (Shallow vs Deep)
# =============================================================================
fig, ax = plt.subplots(figsize=(9, 6))

x = np.arange(len(methods))
width = 0.35

shallow_rates = [shallow_stats[m]["success_rate"] * 100 for m in methods]
deep_rates = [deep_stats[m]["success_rate"] * 100 for m in methods]

bars1 = ax.bar(x - width/2, shallow_rates, width, label="Shallow Tasks\n(Direct from Home)",
              color="#4CAF50", alpha=0.85, edgecolor='white', linewidth=1)
bars2 = ax.bar(x + width/2, deep_rates, width, label="Deep Tasks\n($\\geq$2 Navigation Hops)",
              color="#FF9800", alpha=0.85, edgecolor='white', linewidth=1)

# Add value labels
for bars in [bars1, bars2]:
    for bar in bars:
        height = bar.get_height()
        ax.text(bar.get_x() + bar.get_width()/2., height + 1,
                f'{height:.1f}%', ha='center', va='bottom', fontweight='bold', fontsize=9)

ax.set_ylabel("Success Rate (%)", fontsize=12)
ax.set_xlabel("Method", fontsize=12)
ax.set_title("Success Rate: Shallow vs Deep Tasks", fontsize=14, fontweight='bold')
ax.set_xticks(x)
ax.set_xticklabels([method_labels[m] for m in methods], fontsize=10)
ax.set_ylim(0, 105)
ax.grid(axis='y', alpha=0.3)
ax.legend(loc='lower right', fontsize=10)

plt.tight_layout()
plt.savefig(output_dir / "fig1_success_rate.png", dpi=150, bbox_inches='tight')
plt.savefig(output_dir / "fig1_success_rate.pdf", bbox_inches='tight')
print("[OK] Saved: fig1_success_rate.png/pdf")
plt.close()

# =============================================================================
# Figure 2: Token Consumption Comparison
# =============================================================================
fig, ax = plt.subplots(figsize=(9, 6))

# Calculate overall average tokens
overall_tokens = {}
for method in methods:
    method_results = [r for r in results if r["method"] == method]
    overall_tokens[method] = np.mean([r["input_tokens"] + r["output_tokens"] for r in method_results])

# Also calculate by category for reference
shallow_tokens = [shallow_stats[m]["avg_total_tokens"] for m in methods]
deep_tokens = [deep_stats[m]["avg_total_tokens"] for m in methods]

# Create grouped bar chart
x = np.arange(len(methods))
width = 0.25

bars1 = ax.bar(x - width, shallow_tokens, width, label="Shallow Tasks",
              color="#4CAF50", alpha=0.85, edgecolor='white', linewidth=1)
bars2 = ax.bar(x, deep_tokens, width, label="Deep Tasks",
              color="#FF9800", alpha=0.85, edgecolor='white', linewidth=1)
bars3 = ax.bar(x + width, [overall_tokens[m] for m in methods], width, label="Overall",
              color="#2196F3", alpha=0.85, edgecolor='white', linewidth=1)

# Add value labels (only for overall to avoid clutter)
for bar, val in zip(bars3, [overall_tokens[m] for m in methods]):
    height = bar.get_height()
    ax.text(bar.get_x() + bar.get_width()/2., height + 500,
            f'{int(val)}', ha='center', va='bottom', fontweight='bold', fontsize=9)

ax.set_ylabel("Average Token Count", fontsize=12)
ax.set_xlabel("Method", fontsize=12)
ax.set_title("Token Consumption Comparison", fontsize=14, fontweight='bold')
ax.set_xticks(x - width/2)  # Adjust for the first bar
ax.set_xticklabels([method_labels[m] for m in methods], fontsize=10)
ax.legend(fontsize=10, loc='upper left')
ax.grid(axis='y', alpha=0.3)

plt.tight_layout()
plt.savefig(output_dir / "fig2_token_consumption.png", dpi=150, bbox_inches='tight')
plt.savefig(output_dir / "fig2_token_consumption.pdf", bbox_inches='tight')
print("[OK] Saved: fig2_token_consumption.png/pdf")
plt.close()

# =============================================================================
# Figure 3: Model Call Count Comparison
# =============================================================================
fig, ax = plt.subplots(figsize=(9, 6))

shallow_total = [shallow_stats[m]["avg_total_calls"] for m in methods]
deep_total = [deep_stats[m]["avg_total_calls"] for m in methods]

x = np.arange(len(methods))
width = 0.35

bars1 = ax.bar(x - width/2, shallow_total, width, label="Shallow Tasks",
              color="#4CAF50", alpha=0.85, edgecolor='white', linewidth=1)
bars2 = ax.bar(x + width/2, deep_total, width, label="Deep Tasks",
              color="#FF9800", alpha=0.85, edgecolor='white', linewidth=1)

for bars in [bars1, bars2]:
    for bar in bars:
        height = bar.get_height()
        ax.text(bar.get_x() + bar.get_width()/2., height + 0.05,
                f'{height:.2f}', ha='center', va='bottom', fontsize=9)

ax.set_ylabel("Average Model Calls (qwen3-vl-30b-a3b-instruct)", fontsize=12)
ax.set_xlabel("Method", fontsize=12)
ax.set_title("Model Call Count Comparison", fontsize=14, fontweight='bold')
ax.set_xticks(x)
ax.set_xticklabels([method_labels[m] for m in methods], fontsize=10)
ax.legend(fontsize=10, loc='upper left')
ax.grid(axis='y', alpha=0.3)

plt.tight_layout()
plt.savefig(output_dir / "fig3_model_calls.png", dpi=150, bbox_inches='tight')
plt.savefig(output_dir / "fig3_model_calls.pdf", bbox_inches='tight')
print("[OK] Saved: fig3_model_calls.png/pdf")
plt.close()

# =============================================================================
# Print Summary Statistics
# =============================================================================
print("\n" + "="*70)
print("BENCHMARK SUMMARY STATISTICS")
print("="*70)

print("\n--- Shallow Tasks (Direct from Home: tb_s1, tb_s3, bili_s1) ---")
print(f"{'Method':<20} {'Success':<10} {'VLM Calls':<12} {'LLM Calls':<12} {'Tokens':<10}")
print("-" * 70)
for method in methods:
    s = shallow_stats[method]
    print(f"{method:<20} {s['success_rate']*100:>6.1f}%    {s['avg_vlm_calls']:>8.2f}      {s['avg_llm_calls']:>8.2f}      {int(s['avg_total_tokens']):>8}")

print("\n--- Deep Tasks (>=2 Hops: tb_d1, tb_d2, bili_m1, bili_m3, bili_d2) ---")
print(f"{'Method':<20} {'Success':<10} {'VLM Calls':<12} {'LLM Calls':<12} {'Tokens':<10}")
print("-" * 70)
for method in methods:
    s = deep_stats[method]
    print(f"{method:<20} {s['success_rate']*100:>6.1f}%    {s['avg_vlm_calls']:>8.2f}      {s['avg_llm_calls']:>8.2f}      {int(s['avg_total_tokens']):>8}")

print("\n--- Overall (All 8 Tasks) ---")
print(f"{'Method':<20} {'Success':<10} {'VLM Calls':<12} {'LLM Calls':<12} {'Tokens':<10}")
print("-" * 70)
for method in methods:
    all_runs = [r for r in results if r["method"] == method]
    success_runs = [r for r in all_runs if r["success"]]
    s_rate = len(success_runs) / len(all_runs) * 100
    avg_vlm = np.mean([r["vlm_calls"] for r in all_runs])
    avg_llm = np.mean([r["llm_calls"] for r in all_runs])
    avg_tok = np.mean([r["input_tokens"] + r["output_tokens"] for r in all_runs])
    print(f"{method:<20} {s_rate:>6.1f}%    {avg_vlm:>8.2f}      {avg_llm:>8.2f}      {int(avg_tok):>8}")

print("\n--- LXB-Route vs Baselines (Deep Tasks) ---")
lx_vlm = deep_stats["LXB-Route"]["avg_vlm_calls"]
lx_tokens = deep_stats["LXB-Route"]["avg_total_tokens"]

for baseline in ["VLM-ReAct", "Text-ReAct", "VLM+SemanticMap"]:
    b_vlm = deep_stats[baseline]["avg_vlm_calls"]
    b_tokens = deep_stats[baseline]["avg_total_tokens"]

    vlm_reduction = (1 - lx_vlm / b_vlm) * 100 if b_vlm > 0 else 0
    token_reduction = (1 - lx_tokens / b_tokens) * 100 if b_tokens > 0 else 0

    print(f"\nvs {baseline}:")
    print(f"  VLM Calls:  {lx_vlm:.2f} -> {b_vlm:.2f} ({vlm_reduction:+.1f}%)")
    print(f"  Tokens:    {int(lx_tokens)} -> {int(b_tokens)} ({token_reduction:+.1f}%)")

print("\n" + "="*70)
print(f"Figures saved to: {output_dir}/")
print("="*70)
