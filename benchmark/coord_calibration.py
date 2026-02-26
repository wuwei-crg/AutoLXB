"""
Global VLM coordinate calibration for benchmark runs.

Uses two non-1000 probe images and infers coordinate mode:
- fixed_native_range: model outputs in a model-native space
- image_pixel: model outputs image-like pixel space
"""

from __future__ import annotations

import io
import json
import re
from typing import Any

from PIL import Image, ImageDraw

from benchmark.config import VLM_API_KEY, VLM_BASE_URL, VLM_MODEL
from benchmark.runners.base import InferenceCounter, LLMClient

_PROBE_CACHE: dict[str, Any] | None = None
_PROBE_SIZES: list[tuple[int, int]] = [(1280, 720), (720, 1280)]


def _shell_log(event: str, **kwargs: Any) -> None:
    payload = {"事件": event, **kwargs}
    print(f"[坐标校准] {json.dumps(payload, ensure_ascii=False)}", flush=True)


def _build_coord_probe_image(width: int, height: int) -> bytes:
    img = Image.new("RGB", (int(width), int(height)), (0, 0, 0))
    draw = ImageDraw.Draw(img)
    dot = max(18, min(width, height) // 24)

    # 四角色块（非 L 形）
    # TL red
    draw.rectangle([0, 0, dot, dot], fill=(255, 0, 0))
    # TR green
    draw.rectangle([width - dot - 1, 0, width - 1, dot], fill=(0, 255, 0))
    # BL blue
    draw.rectangle([0, height - dot - 1, dot, height - 1], fill=(0, 100, 255))
    # BR yellow
    draw.rectangle([width - dot - 1, height - dot - 1, width - 1, height - 1], fill=(255, 220, 0))

    out = io.BytesIO()
    img.save(out, format="PNG")
    return out.getvalue()


def _parse_coord_probe_response(raw: str) -> dict[str, tuple[float, float]]:
    text = (raw or "").strip()
    obj = None
    try:
        tmp = json.loads(text)
        if isinstance(tmp, dict):
            obj = tmp
    except Exception:
        m = re.search(r"\{[\s\S]*\}", text)
        if m:
            try:
                tmp = json.loads(m.group(0))
                if isinstance(tmp, dict):
                    obj = tmp
            except Exception:
                obj = None

    if not isinstance(obj, dict):
        return {}

    out: dict[str, tuple[float, float]] = {}
    for k in ("tl", "tr", "bl", "br"):
        v = obj.get(k)
        if not isinstance(v, (list, tuple)) or len(v) < 2:
            return {}
        try:
            x = float(v[0])
            y = float(v[1])
        except Exception:
            return {}
        out[k] = (x, y)
    return out


def _canonicalize_points(points: dict[str, tuple[float, float]]) -> dict[str, tuple[float, float]]:
    """
    Canonicalize corner labels by geometry, ignoring model-provided keys.
    This makes calibration robust when model mislabels tl/tr/bl/br.
    """
    vals = list(points.values())
    if len(vals) != 4:
        return {}

    # top two: smaller y; bottom two: larger y
    by_y = sorted(vals, key=lambda p: (p[1], p[0]))
    top = sorted(by_y[:2], key=lambda p: p[0])      # left->right
    bottom = sorted(by_y[2:], key=lambda p: p[0])   # left->right
    return {
        "tl": top[0],
        "tr": top[1],
        "bl": bottom[0],
        "br": bottom[1],
    }


def _is_valid_sample(sample: dict[str, Any]) -> bool:
    if not sample:
        return False
    x_min = float(sample.get("x_min") or 0.0)
    x_max = float(sample.get("x_max") or 0.0)
    y_min = float(sample.get("y_min") or 0.0)
    y_max = float(sample.get("y_max") or 0.0)
    span_x = float(sample.get("span_x") or 0.0)
    span_y = float(sample.get("span_y") or 0.0)
    if x_max <= x_min or y_max <= y_min:
        return False
    # Filter degenerate outputs (often caused by wrong corner parsing).
    if span_x < 80.0 or span_y < 80.0:
        return False
    return True


def _probe_one(llm: LLMClient, width: int, height: int) -> dict[str, Any]:
    image_bytes = _build_coord_probe_image(width, height)
    prompt = (
        "Coordinate Calibration Task.\n"
        "You are given a synthetic image with black background and four colored corner blocks:\n"
        "- top-left: RED\n"
        "- top-right: GREEN\n"
        "- bottom-left: BLUE\n"
        "- bottom-right: YELLOW\n"
        "Return ONLY JSON with this exact schema:\n"
        '{"tl":[x,y],"tr":[x,y],"bl":[x,y],"br":[x,y]}\n'
        "Rules:\n"
        "1) Output numbers only.\n"
        "2) Do NOT add markdown.\n"
        "3) Use your native coordinate space (do NOT convert on purpose).\n"
        "4) For each corner, return the colored point NEAREST to that image corner (corner-touch side), NOT the block center.\n"
        "5) If uncertain between multiple colored pixels, choose the one closest to the corner boundary.\n"
        "6) Be precise; this is for coordinate range calibration.\n"
    )
    raw = llm.complete_with_image(prompt, image_bytes)
    points_raw = _parse_coord_probe_response(raw)
    if not points_raw:
        return {}
    points = _canonicalize_points(points_raw)
    if not points:
        return {}

    x_min = (points["tl"][0] + points["bl"][0]) / 2.0
    x_max = (points["tr"][0] + points["br"][0]) / 2.0
    y_min = (points["tl"][1] + points["tr"][1]) / 2.0
    y_max = (points["bl"][1] + points["br"][1]) / 2.0
    span_x = max(1e-6, x_max - x_min)
    span_y = max(1e-6, y_max - y_min)
    max_x = max(v[0] for v in points.values())
    max_y = max(v[1] for v in points.values())
    return {
        "width": width,
        "height": height,
        "points": points,
        "points_raw": points_raw,
        "x_min": x_min,
        "x_max": x_max,
        "y_min": y_min,
        "y_max": y_max,
        "span_x": span_x,
        "span_y": span_y,
        "max_x": max_x,
        "max_y": max_y,
    }


def _detect_mode(samples: list[dict[str, Any]]) -> str:
    # Need two valid probes.
    if len(samples) < 2:
        return "fixed_native_range"

    s1, s2 = samples[0], samples[1]
    width_ratio = max(1e-6, float(s1["width"])) / max(1e-6, float(s2["width"]))
    height_ratio = max(1e-6, float(s1["height"])) / max(1e-6, float(s2["height"]))
    span_x_ratio = max(1e-6, float(s1["span_x"])) / max(1e-6, float(s2["span_x"]))
    span_y_ratio = max(1e-6, float(s1["span_y"])) / max(1e-6, float(s2["span_y"]))

    # If output spans scale with image sizes, model likely outputs image-like pixels.
    x_scale_err = abs((span_x_ratio / width_ratio) - 1.0)
    y_scale_err = abs((span_y_ratio / height_ratio) - 1.0)
    if x_scale_err <= 0.25 and y_scale_err <= 0.25:
        return "image_pixel"
    return "fixed_native_range"


def calibrate_once(verbose: bool = True) -> dict[str, Any]:
    """
    Run two-image coordinate probe once and cache globally.
    Verification calls are excluded from benchmark metrics.
    """
    global _PROBE_CACHE
    if _PROBE_CACHE:
        return _PROBE_CACHE

    try:
        llm = LLMClient(
            base_url=VLM_BASE_URL,
            api_key=VLM_API_KEY,
            model=VLM_MODEL,
            counter=InferenceCounter(),
            vision=True,
        )

        samples: list[dict[str, Any]] = []
        for w, h in _PROBE_SIZES:
            best: dict[str, Any] = {}
            best_score = -1.0
            for attempt in range(1, 4):
                if verbose:
                    _shell_log("开始探针", 图像宽=w, 图像高=h, 尝试=attempt)
                sample = _probe_one(llm, w, h)
                if not sample:
                    if verbose:
                        _shell_log("探针失败", 图像宽=w, 图像高=h, 尝试=attempt, 原因="解析失败")
                    continue
                score = float(sample["span_x"]) * float(sample["span_y"])
                valid = _is_valid_sample(sample)
                if verbose:
                    _shell_log(
                        "探针结果",
                        图像宽=w,
                        图像高=h,
                        尝试=attempt,
                        有效=valid,
                        模型原始四点={
                            "tl": [round(float(sample["points_raw"]["tl"][0]), 3), round(float(sample["points_raw"]["tl"][1]), 3)],
                            "tr": [round(float(sample["points_raw"]["tr"][0]), 3), round(float(sample["points_raw"]["tr"][1]), 3)],
                            "bl": [round(float(sample["points_raw"]["bl"][0]), 3), round(float(sample["points_raw"]["bl"][1]), 3)],
                            "br": [round(float(sample["points_raw"]["br"][0]), 3), round(float(sample["points_raw"]["br"][1]), 3)],
                        },
                        几何重排四点={
                            "tl": [round(float(sample["points"]["tl"][0]), 3), round(float(sample["points"]["tl"][1]), 3)],
                            "tr": [round(float(sample["points"]["tr"][0]), 3), round(float(sample["points"]["tr"][1]), 3)],
                            "bl": [round(float(sample["points"]["bl"][0]), 3), round(float(sample["points"]["bl"][1]), 3)],
                            "br": [round(float(sample["points"]["br"][0]), 3), round(float(sample["points"]["br"][1]), 3)],
                        },
                        x最小=round(float(sample["x_min"]), 3),
                        x最大=round(float(sample["x_max"]), 3),
                        y最小=round(float(sample["y_min"]), 3),
                        y最大=round(float(sample["y_max"]), 3),
                        x跨度=round(float(sample["span_x"]), 3),
                        y跨度=round(float(sample["span_y"]), 3),
                    )
                if valid and score > best_score:
                    best = sample
                    best_score = score
            if best:
                samples.append(best)
            elif verbose:
                _shell_log("探针放弃", 图像宽=w, 图像高=h, 原因="3次均无有效样本")

        if not samples:
            _PROBE_CACHE = {}
            return _PROBE_CACHE

        mode = _detect_mode(samples)
        if len(samples) >= 2 and verbose:
            s1, s2 = samples[0], samples[1]
            width_ratio = max(1e-6, float(s1["width"])) / max(1e-6, float(s2["width"]))
            height_ratio = max(1e-6, float(s1["height"])) / max(1e-6, float(s2["height"]))
            span_x_ratio = max(1e-6, float(s1["span_x"])) / max(1e-6, float(s2["span_x"]))
            span_y_ratio = max(1e-6, float(s1["span_y"])) / max(1e-6, float(s2["span_y"]))
            _shell_log(
                "模式判定",
                宽比=round(width_ratio, 4),
                高比=round(height_ratio, 4),
                x跨度比=round(span_x_ratio, 4),
                y跨度比=round(span_y_ratio, 4),
                判定模式=mode,
            )
        x_min = sum(s["x_min"] for s in samples) / len(samples)
        x_max = sum(s["x_max"] for s in samples) / len(samples)
        y_min = sum(s["y_min"] for s in samples) / len(samples)
        y_max = sum(s["y_max"] for s in samples) / len(samples)
        span_x = max(1e-6, x_max - x_min)
        span_y = max(1e-6, y_max - y_min)
        max_x = max(float(s["max_x"]) for s in samples)
        max_y = max(float(s["max_y"]) for s in samples)

        _PROBE_CACHE = {
            "mode": mode,
            "max_x": round(max_x, 4),
            "max_y": round(max_y, 4),
            "x_min": round(x_min, 4),
            "x_max": round(x_max, 4),
            "y_min": round(y_min, 4),
            "y_max": round(y_max, 4),
            "span_x": round(span_x, 4),
            "span_y": round(span_y, 4),
            "samples": [
                {
                    "width": int(s["width"]),
                    "height": int(s["height"]),
                    "span_x": round(float(s["span_x"]), 4),
                    "span_y": round(float(s["span_y"]), 4),
                    "max_x": round(float(s["max_x"]), 4),
                    "max_y": round(float(s["max_y"]), 4),
                }
                for s in samples
            ],
        }
        if verbose:
            _shell_log(
                "校准完成",
                模式=_PROBE_CACHE["mode"],
                max_x=round(float(_PROBE_CACHE["max_x"]), 3),
                max_y=round(float(_PROBE_CACHE["max_y"]), 3),
                x范围=[round(float(_PROBE_CACHE["x_min"]), 3), round(float(_PROBE_CACHE["x_max"]), 3)],
                y范围=[round(float(_PROBE_CACHE["y_min"]), 3), round(float(_PROBE_CACHE["y_max"]), 3)],
            )
        return _PROBE_CACHE
    except Exception as exc:
        if verbose:
            _shell_log("校准异常", 错误=str(exc))
        _PROBE_CACHE = {}
        return _PROBE_CACHE


def get_coord_probe() -> dict[str, Any]:
    return _PROBE_CACHE or {}


def map_point_by_probe(
    probe: dict[str, Any],
    raw_x: float,
    raw_y: float,
    screen_w: int,
    screen_h: int,
) -> tuple[int, int, str]:
    """
    Map VLM coordinates to screen coordinates using probe data.
    Returns (x, y, mode).
    """
    if screen_w <= 1 or screen_h <= 1:
        return int(round(raw_x)), int(round(raw_y)), "passthrough_no_screen"

    mode = str(probe.get("mode") or "fixed_native_range")
    if mode == "image_pixel":
        rx = max(0, min(screen_w - 1, int(round(raw_x))))
        ry = max(0, min(screen_h - 1, int(round(raw_y))))
        return rx, ry, "image_pixel"

    max_x = float(probe.get("max_x") or 0.0)
    max_y = float(probe.get("max_y") or 0.0)
    if max_x <= 0.0 or max_y <= 0.0:
        return int(round(raw_x)), int(round(raw_y)), "passthrough_no_probe"

    # Absolute pixel bypass
    if 0.0 <= raw_x <= float(screen_w - 1) and 0.0 <= raw_y <= float(screen_h - 1):
        if raw_x > max_x * 1.2 or raw_y > max_y * 1.2:
            rx = int(round(raw_x))
            ry = int(round(raw_y))
            return rx, ry, "probe_bypass_absolute"

    x_min = float(probe.get("x_min") or 0.0)
    x_max = float(probe.get("x_max") or 0.0)
    y_min = float(probe.get("y_min") or 0.0)
    y_max = float(probe.get("y_max") or 0.0)

    if x_max > x_min and y_max > y_min:
        nx = (raw_x - x_min) / (x_max - x_min)
        ny = (raw_y - y_min) / (y_max - y_min)
        rx = int(round(nx * float(screen_w - 1)))
        ry = int(round(ny * float(screen_h - 1)))
        out_mode = "range_affine"
    else:
        rx = int(round((raw_x / max_x) * float(screen_w - 1)))
        ry = int(round((raw_y / max_y) * float(screen_h - 1)))
        out_mode = "max_scale"

    rx = max(0, min(screen_w - 1, rx))
    ry = max(0, min(screen_h - 1, ry))
    return rx, ry, out_mode
