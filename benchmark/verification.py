"""
Unified external visual verifier.
The verifier judges completion from user_task + screenshot.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any

from benchmark.config import VERIFY_API_KEY, VERIFY_BASE_URL, VERIFY_MODEL
from benchmark.runners.base import InferenceCounter, LLMClient
from benchmark.tasks import BenchmarkTask


@dataclass
class VerifyResult:
    success: bool
    confidence: float
    reason: str
    observed_page: str
    target_page: str  # log identifier only


class ExternalVisualVerifier:
    """
    Uses configured VLM to answer:
    "For this user_task, has the goal been reached on current screenshot?"
    """

    def __init__(self, task: BenchmarkTask) -> None:
        self.task = task
        self.vlm = LLMClient(
            base_url=VERIFY_BASE_URL,
            api_key=VERIFY_API_KEY,
            model=VERIFY_MODEL,
            counter=InferenceCounter(),  # excluded from benchmark metrics
            vision=True,
        )

    @staticmethod
    def _parse_json(raw: str) -> dict[str, Any]:
        raw = re.sub(r"```(?:json)?", "", raw).strip().strip("`")
        m = re.search(r"\{.*\}", raw, re.DOTALL)
        if not m:
            raise ValueError(f"no_json: {raw[:160]!r}")
        return json.loads(m.group())

    def _build_prompt(self) -> str:
        return (
            "你是一个严格的移动端任务验收器。\n"
            "你会看到一张当前页面截图，请根据用户任务判断任务是否已经达成。只判断是否到达目标页面，关注具体页面功能，而不是页面内容\n"
            "比如，用户任务是查看消息页面，那么就判断目标页面是不是消息页面，而不关注页面有没有消息\n"
            "比如，用户的任务是查看购物车，那么就判断目标页面是不是购物车页面，而不关注页面有没有商品\n"
            "只输出 JSON，不要输出解释文本。\n\n"
            f"应用包名: {self.task.package}\n"
            f"用户任务: {self.task.user_task}\n\n"
            "输出格式:\n"
            '{"reached": true|false, "confidence": 0.0-1.0, '
            '"observed_page": "<你判断的当前页面>", "reason": "<简短原因>"}'
        )

    def verify(self, client, screenshot: bytes | None = None) -> VerifyResult:
        try:
            if screenshot is None:
                screenshot = client.screenshot()
            if not screenshot:
                return VerifyResult(
                    success=False,
                    confidence=0.0,
                    reason="screenshot_failed",
                    observed_page="",
                    target_page=self.task.target_page,
                )

            raw = self.vlm.complete_with_image(self._build_prompt(), screenshot)
            payload = self._parse_json(raw)
            reached = bool(payload.get("reached"))
            confidence = float(payload.get("confidence", 0.0) or 0.0)
            observed = str(payload.get("observed_page", "") or "")
            reason = str(payload.get("reason", "") or "")
            return VerifyResult(
                success=reached,
                confidence=max(0.0, min(confidence, 1.0)),
                reason=reason or ("reached" if reached else "not_reached"),
                observed_page=observed,
                target_page=self.task.target_page,
            )
        except Exception as exc:
            return VerifyResult(
                success=False,
                confidence=0.0,
                reason=f"verifier_error:{exc}",
                observed_page="",
                target_page=self.task.target_page,
            )
