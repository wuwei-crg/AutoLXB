"""
Base classes shared by all runners.

InferenceCounter   – wraps raw API calls, counts calls and tokens
LLMClient          – thin HTTP wrapper for text + vision completion
BaseRunner         – interface every runner must implement
RunResult          – structured result returned by each runner
"""

from __future__ import annotations

import base64
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Optional

import requests

from benchmark.config import PRICING
from benchmark.tasks import BenchmarkTask


# ── Result ────────────────────────────────────────────────────────────────────

@dataclass
class RunResult:
    method:           str
    model:            str
    task_id:          str
    trial:            int
    success:          bool
    llm_calls:        int   = 0   # text LLM calls
    vlm_calls:        int   = 0   # vision LLM calls
    steps:            int   = 0   # device taps executed
    latency_sec:      float = 0.0
    input_tokens:     int   = 0
    output_tokens:    int   = 0
    estimated_cost:   float = 0.0  # USD
    error:            Optional[str] = None
    final_package:    str   = ""   # from get_activity() after run
    final_activity:   str   = ""


# ── Inference counter / LLM client ────────────────────────────────────────────

class InferenceCounter:
    """Accumulates call counts and token usage across multiple API calls."""

    def __init__(self) -> None:
        self.llm_calls     = 0
        self.vlm_calls     = 0
        self.input_tokens  = 0
        self.output_tokens = 0

    def record(self, *, vision: bool, usage: dict) -> None:
        if vision:
            self.vlm_calls += 1
        else:
            self.llm_calls += 1
        self.input_tokens  += usage.get("prompt_tokens", 0)
        self.output_tokens += usage.get("completion_tokens", 0)

    def estimated_cost(self, text_model: str, vision_model: str) -> float:
        text_p   = PRICING.get(text_model,   {"input": 0, "output": 0})
        vision_p = PRICING.get(vision_model, {"input": 0, "output": 0})
        # Approximation: attribute all tokens to the primary model used.
        # For mixed-model runs, a more precise split would require per-call tracking.
        if self.vlm_calls == 0:
            p = text_p
        elif self.llm_calls == 0:
            p = vision_p
        else:
            # Weighted average (rough)
            total = self.llm_calls + self.vlm_calls
            p = {
                "input":  (text_p["input"]   * self.llm_calls + vision_p["input"]   * self.vlm_calls) / total,
                "output": (text_p["output"]  * self.llm_calls + vision_p["output"]  * self.vlm_calls) / total,
            }
        return self.input_tokens * p["input"] + self.output_tokens * p["output"]


class LLMClient:
    """Thin HTTP wrapper around any OpenAI-compatible API endpoint."""

    def __init__(
        self,
        base_url:  str,
        api_key:   str,
        model:     str,
        counter:   InferenceCounter,
        timeout:   int = 60,
        vision:    bool = False,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key  = api_key
        self.model    = model
        self.counter  = counter
        self.timeout  = timeout
        self.vision   = vision

    def complete(self, prompt: str) -> str:
        """Text-only completion."""
        resp = requests.post(
            f"{self.base_url}/chat/completions",
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            json={
                "model": self.model,
                "messages": [{"role": "user", "content": prompt}],
                "temperature": 0.1,
            },
            timeout=self.timeout,
        )
        resp.raise_for_status()
        data = resp.json()
        self.counter.record(vision=False, usage=data.get("usage", {}))
        return data["choices"][0]["message"]["content"]

    def complete_with_image(self, prompt: str, image_bytes: bytes) -> str:
        """Vision completion: prompt + screenshot."""
        b64 = base64.b64encode(image_bytes).decode()
        resp = requests.post(
            f"{self.base_url}/chat/completions",
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            json={
                "model": self.model,
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": prompt},
                            {
                                "type": "image_url",
                                "image_url": {
                                    "url": f"data:image/jpeg;base64,{b64}"
                                },
                            },
                        ],
                    }
                ],
                "temperature": 0.1,
            },
            timeout=self.timeout,
        )
        resp.raise_for_status()
        data = resp.json()
        self.counter.record(vision=True, usage=data.get("usage", {}))
        return data["choices"][0]["message"]["content"]


# ── Abstract runner ───────────────────────────────────────────────────────────

class BaseRunner(ABC):
    """Every method runner implements this interface."""

    METHOD_NAME: str = "base"

    @abstractmethod
    def run(
        self,
        client,          # LXBLinkClient (already connected)
        task: BenchmarkTask,
        trial: int,
    ) -> RunResult:
        ...

    # -- helpers --

    @staticmethod
    def _reset_app(client, package: str) -> None:
        """Go home and stop the app, without launching.
        Use this before handing off to an engine that launches the app itself."""
        from lxb_link.constants import KEY_HOME
        client.key_event(KEY_HOME)
        time.sleep(1.5)
        client.stop_app(package)
        time.sleep(1.5)

    @staticmethod
    def _launch_and_wait(client, package: str, wait: float) -> None:
        """Full reset: HOME → stop → launch, with 1.5s between each step."""
        from lxb_link.constants import KEY_HOME
        client.key_event(KEY_HOME)
        time.sleep(1.5)
        client.stop_app(package)
        time.sleep(1.5)
        client.launch_app(package)
        time.sleep(wait)

    @staticmethod
    def _get_final_activity(client) -> tuple[str, str]:
        try:
            ok, pkg, act = client.get_activity()
            if ok:
                return pkg, act
        except Exception:
            pass
        return "", ""
