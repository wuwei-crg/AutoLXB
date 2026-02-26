"""
Text-ReAct runner (baseline B).

Each navigation step: dump_actions() → text LLM → parse node index or DONE.
No screenshots are used — only the XML accessibility tree (text-only).
Cost per task: N text LLM calls, where N = number of taps taken.
"""

from __future__ import annotations

import json
import re
import sys
import os
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from lxb_link import LXBLinkClient

from benchmark.config import (
    TEXT_LLM_BASE_URL, TEXT_LLM_API_KEY, TEXT_LLM_MODEL,
    VLM_MODEL, STEP_PAUSE_SEC, APP_LAUNCH_WAIT,
)
from benchmark.runners.base import BaseRunner, InferenceCounter, LLMClient, RunResult
from benchmark.tasks import BenchmarkTask
from benchmark.verification import ExternalVisualVerifier


_STEP_PROMPT = """\
You are navigating an Android app step by step to reach a target page.
Respond ONLY with a JSON object — no markdown, no explanation.

App: {app_name}  |  Package: {package}
Goal: {user_task}
Step: {step}/{max_steps}
Current activity: {activity}
History: {history}

Clickable UI nodes:
{nodes}

Tap a node by its index, or press BACK:
  → {{"done": false, "node": <index>, "reason": "<one sentence>"}}
  → {{"done": false, "node": "BACK", "reason": "<one sentence>"}}

Prefer bottom nav tabs, top tabs, and fixed menu items.
Do NOT tap content cards, article titles, or list items.
Use BACK when you navigated to a wrong page or are stuck.

Respond with ONLY the JSON object."""


def _format_nodes(dump: dict) -> tuple[str, list[dict]]:
    """
    Format dump_actions() output into a numbered text list.
    Returns (formatted_string, node_list) where node_list preserves order.
    """
    clickable_nodes = [n for n in dump.get("nodes", []) if n.get("clickable")]
    lines = []
    for i, node in enumerate(clickable_nodes):
        text    = (node.get("text") or "").strip()[:40]
        rid     = (node.get("resource_id") or "")[:40]
        desc    = (node.get("content_desc") or "").strip()[:30]
        bounds  = node.get("bounds", [])
        label   = text or desc or rid or node.get("class", "")
        lines.append(f"[{i}] label={label!r}  rid={rid!r}  bounds={bounds}")
    return "\n".join(lines) if lines else "(no clickable nodes)", clickable_nodes


def _parse_response(raw: str) -> dict:
    raw = re.sub(r"```(?:json)?", "", raw).strip().strip("`")
    match = re.search(r"\{.*\}", raw, re.DOTALL)
    if match:
        return json.loads(match.group())
    raise ValueError(f"No JSON in response: {raw!r}")


class TextReActRunner(BaseRunner):
    """
    Text-only step-by-step navigation via XML accessibility tree.
    No vision — uses dump_actions() to list UI elements.
    Each step consumes 1 text LLM call.
    """

    METHOD_NAME = "Text-ReAct"
    MAX_STEPS = 10

    def run(self, client: LXBLinkClient, task: BenchmarkTask, trial: int) -> RunResult:
        counter = InferenceCounter()
        llm = LLMClient(
            base_url=TEXT_LLM_BASE_URL,
            api_key=TEXT_LLM_API_KEY,
            model=TEXT_LLM_MODEL,
            counter=counter,
        )

        self._launch_and_wait(client, task.package, APP_LAUNCH_WAIT)
        verifier = ExternalVisualVerifier(task)

        success = False
        steps = 0
        error = None
        history: list[str] = []
        t0 = time.perf_counter()

        verify_init = verifier.verify(client)
        if verify_init.success:
            success = True
            steps = 0
        for step in range(1, self.MAX_STEPS + 1):
            if success:
                break

            # Get current activity for context
            current_pkg, current_act = self._get_final_activity(client)

            # Dump clickable UI nodes
            try:
                dump = client.dump_actions()
            except Exception as exc:
                error = f"dump_failed_step{step}: {exc}"
                break

            nodes_text, node_list = _format_nodes(dump)
            history_str = "; ".join(history[-5:]) if history else "none"

            prompt = _STEP_PROMPT.format(
                app_name=task.app_name,
                package=task.package,
                user_task=task.user_task,
                step=step,
                max_steps=self.MAX_STEPS,
                activity=current_act or "unknown",
                history=history_str,
                nodes=nodes_text,
            )

            try:
                raw = llm.complete(prompt)
                action = _parse_response(raw)
            except Exception as exc:
                error = f"parse_error_step{step}: {exc}"
                break

            node_idx = action.get("node")

            if node_idx == "BACK":
                client.key_event(4)   # KEYCODE_BACK
                history.append(f"step{step}:BACK({action.get('reason','')})"[:60])
                steps = step
                time.sleep(STEP_PAUSE_SEC)
                verify_after = verifier.verify(client)
                if verify_after.success:
                    success = True
                    break
                continue

            if node_idx is None or not isinstance(node_idx, int):
                # Backward compatibility: old prompts may still return "done".
                if action.get("done") is True:
                    history.append(f"step{step}:DONE_IGNORED")
                    steps = step
                    time.sleep(STEP_PAUSE_SEC)
                    verify_after = verifier.verify(client)
                    if verify_after.success:
                        success = True
                        break
                    continue
                error = f"invalid_node_idx_step{step}: {node_idx!r}"
                break

            if node_idx < 0 or node_idx >= len(node_list):
                error = f"node_out_of_range_step{step}: {node_idx} / {len(node_list)}"
                break

            node = node_list[node_idx]
            bounds = node.get("bounds", [0, 0, 0, 0])
            cx = (bounds[0] + bounds[2]) // 2
            cy = (bounds[1] + bounds[3]) // 2
            client.tap(cx, cy)
            label = (node.get("text") or node.get("content_desc") or "")[:20]
            history.append(f"step{step}:tap[{node_idx}]{label!r} {action.get('reason','')}"[:60])
            steps = step
            time.sleep(STEP_PAUSE_SEC)
            verify_after = verifier.verify(client)
            if verify_after.success:
                success = True
                break
        else:
            error = "max_steps_exceeded"

        latency = time.perf_counter() - t0
        final_pkg, final_act = self._get_final_activity(client)
        cost = counter.estimated_cost(TEXT_LLM_MODEL, VLM_MODEL)

        return RunResult(
            method=self.METHOD_NAME,
            model=TEXT_LLM_MODEL,
            task_id=task.task_id,
            trial=trial,
            success=success,
            llm_calls=counter.llm_calls,
            vlm_calls=counter.vlm_calls,
            steps=steps,
            latency_sec=round(latency, 2),
            input_tokens=counter.input_tokens,
            output_tokens=counter.output_tokens,
            estimated_cost=round(cost, 6),
            error=error,
            final_package=final_pkg,
            final_activity=final_act,
        )
