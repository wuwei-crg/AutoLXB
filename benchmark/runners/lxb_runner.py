"""
LXB Route-Then-Act runner (our method).

Runtime cost: 1 text LLM call (MapPromptPlanner) + 0 VLM calls.
The map navigation itself is pure BFS + XML locator matching.
"""

from __future__ import annotations

import sys
import os
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from lxb_link import LXBLinkClient
from cortex import RouteThenActCortex, MapPromptPlanner, RouteConfig

from benchmark.config import (
    TEXT_LLM_BASE_URL, TEXT_LLM_API_KEY, TEXT_LLM_MODEL,
    VLM_MODEL,
)
from benchmark.runners.base import BaseRunner, InferenceCounter, LLMClient, RunResult
from benchmark.tasks import BenchmarkTask
from benchmark.verification import ExternalVisualVerifier


class LXBRunner(BaseRunner):
    """
    Runs RouteThenActCortex with MapPromptPlanner.

    The planner makes exactly 1 text LLM call to identify the target page;
    all subsequent navigation is deterministic (BFS + XML locator).
    """

    METHOD_NAME = "LXB-Route"

    def run(self, client: LXBLinkClient, task: BenchmarkTask, trial: int) -> RunResult:
        counter = InferenceCounter()
        text_client = LLMClient(
            base_url=TEXT_LLM_BASE_URL,
            api_key=TEXT_LLM_API_KEY,
            model=TEXT_LLM_MODEL,
            counter=counter,
        )

        # Inject counting wrapper into MapPromptPlanner
        planner = MapPromptPlanner(llm_complete=text_client.complete)

        route_config = RouteConfig(
            max_route_restarts=2,
            use_vlm_takeover=False,   # disable VLM takeover to keep cost at 0 VLM calls
        )

        engine = RouteThenActCortex(
            client=client,
            planner=planner,
            action_engine=None,       # routing only, no action execution
            config=route_config,
        )
        verifier = ExternalVisualVerifier(task)

        # Reset app state (HOME + stop), then let Cortex handle launch_app internally.
        # Do NOT call launch_app here — _execute_route() starts with launch_app(clear_task=True).
        self._reset_app(client, task.package)

        t0 = time.perf_counter()
        try:
            result = engine.run(
                user_task=task.user_task,
                map_path=task.map_path,
            )
            verify = verifier.verify(client)
            success = verify.success
            if success:
                error = None
            else:
                route_err = result.get("reason", "unknown")
                error = (
                    f"external_verify_failed:"
                    f" target={verify.target_page}"
                    f" observed={verify.observed_page}"
                    f" conf={verify.confidence:.2f}"
                    f" verify_reason={verify.reason}"
                    f" route_status={result.get('status')}"
                    f" route_reason={route_err}"
                )
            steps = len(result.get("route_trace", []))
        except Exception as exc:
            success = False
            error = str(exc)
            steps = 0
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
