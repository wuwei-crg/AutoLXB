import json
import random
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Protocol, Set

from .fsm_instruction import Instruction, InstructionError, parse_instructions, validate_allowed
from .route_then_act import FixedPlanPlanner, HeuristicPlanner, RouteConfig, RouteThenActCortex


class CortexState(str, Enum):
    INIT = "INIT"
    APP_RESOLVE = "APP_RESOLVE"
    ROUTE_PLAN = "ROUTE_PLAN"
    ROUTING = "ROUTING"
    VISION_ACT = "VISION_ACT"
    FINISH = "FINISH"
    FAIL = "FAIL"


@dataclass
class FSMConfig:
    max_turns: int = 30
    max_commands_per_turn: int = 1
    max_vision_turns: int = 20
    action_interval_sec: float = 0.8
    screenshot_settle_sec: float = 0.6
    tap_bind_clickable: bool = False
    tap_jitter_sigma_px: float = 0.0
    swipe_jitter_sigma_px: float = 0.0
    swipe_duration_jitter_ratio: float = 0.0
    xml_stable_interval_sec: float = 0.3
    xml_stable_samples: int = 4
    xml_stable_timeout_sec: float = 4.0


@dataclass
class CortexContext:
    task_id: str
    user_task: str
    map_path: str
    start_page: Optional[str] = None
    selected_package: str = ""
    target_page: str = ""
    route_trace: List[str] = field(default_factory=list)
    command_log: List[Dict[str, Any]] = field(default_factory=list)
    route_result: Dict[str, Any] = field(default_factory=dict)
    error: str = ""
    output: Dict[str, Any] = field(default_factory=dict)
    vision_turns: int = 0
    app_candidates: List[Dict[str, Any]] = field(default_factory=list)
    page_candidates: List[Dict[str, Any]] = field(default_factory=list)
    device_info: Dict[str, Any] = field(default_factory=dict)
    current_activity: Dict[str, Any] = field(default_factory=dict)
    last_command: str = ""
    same_command_streak: int = 0
    last_activity_sig: str = ""
    same_activity_streak: int = 0


class CommandPlanner(Protocol):
    def plan(self, state: CortexState, prompt: str, context: CortexContext) -> str:
        ...


class RuleBasedPlanner:
    def __init__(self, route_loader: Callable[[str], Any]):
        self._route_loader = route_loader
        self._heuristic = HeuristicPlanner()

    def plan(self, state: CortexState, prompt: str, context: CortexContext) -> str:
        route_map = self._route_loader(context.map_path)
        if state == CortexState.APP_RESOLVE:
            return f"SET_APP {route_map.package}"
        if state == CortexState.ROUTE_PLAN:
            plan = self._heuristic.plan(context.user_task, route_map)
            return f"ROUTE {plan.package_name} {plan.target_page}"
        if state == CortexState.VISION_ACT:
            return "DONE"
        return "FAIL unsupported_state"


class PromptBuilder:
    def _common_output_rules(self) -> str:
        return (
            "Output Contract:\n"
            "1) Output MUST be exactly one DSL instruction line.\n"
            "2) No JSON, no markdown, no explanations.\n"
            "3) If you cannot decide safely, output: FAIL <reason>.\n"
            "4) FAIL must include a non-empty reason.\n"
        )

    def _dsl_semantics(self, context: CortexContext) -> str:
        w = int(context.device_info.get("width") or 0)
        h = int(context.device_info.get("height") or 0)
        return (
            "DSL Semantics:\n"
            "- SET_APP <package_name>: choose exactly one package from AppCandidates.\n"
            "- ROUTE <package_name> <target_page>: package must match selected app; target_page must come from PageCandidates.\n"
            "- TAP <x> <y>: tap absolute pixel coordinates.\n"
            "- SWIPE <x1> <y1> <x2> <y2> <duration_ms>: absolute pixel coordinates + duration in ms.\n"
            "- INPUT \"<text>\": input text into focused field.\n"
            "- WAIT <ms>: wait milliseconds.\n"
            "- DONE: task complete.\n"
            "- FAIL <reason>: stop with explicit reason.\n"
            f"Screen Bounds: x in [0,{max(0, w - 1)}], y in [0,{max(0, h - 1)}].\n"
            "Coordinate Rule: if you reason with normalized coordinates (0..1), convert to absolute pixels before output.\n"
            "Conversion: x_px = round(x_norm * (width-1)), y_px = round(y_norm * (height-1)).\n"
        )

    def build(self, state: CortexState, context: CortexContext, allowed_ops: Set[str]) -> str:
        if state == CortexState.APP_RESOLVE:
            app_rows = context.app_candidates[:80]
            return "".join(
                [
                    "State=APP_RESOLVE\n",
                    self._common_output_rules(),
                    self._dsl_semantics(context),
                    f"Allowed: {', '.join(sorted(allowed_ops))}\n",
                    f"UserTask: {context.user_task}\n",
                    f"DeviceInfo(JSON): {json.dumps(context.device_info, ensure_ascii=False)}\n",
                    f"CurrentActivity(JSON): {json.dumps(context.current_activity, ensure_ascii=False)}\n",
                    f"AppCandidates(JSON): {json.dumps(app_rows, ensure_ascii=False)}\n",
                    "State Goal:\n",
                    "Select the best target app for this task from AppCandidates.\n",
                    "Return exactly one command: SET_APP <package_name> OR FAIL <reason>.\n",
                    "Examples:\n",
                    "SET_APP com.baidu.tieba\n",
                    "SET_APP com.taobao.taobao\n",
                    "FAIL no_matching_app",
                ]
            )

        if state == CortexState.ROUTE_PLAN:
            page_rows = context.page_candidates[:120]
            return "".join(
                [
                    "State=ROUTE_PLAN\n",
                    self._common_output_rules(),
                    self._dsl_semantics(context),
                    f"Allowed: {', '.join(sorted(allowed_ops))}\n",
                    f"UserTask: {context.user_task}\n",
                    f"SelectedPackage: {context.selected_package}\n",
                    f"CurrentActivity(JSON): {json.dumps(context.current_activity, ensure_ascii=False)}\n",
                    f"DeviceInfo(JSON): {json.dumps(context.device_info, ensure_ascii=False)}\n",
                    f"PageCandidates(JSON): {json.dumps(page_rows, ensure_ascii=False)}\n",
                    "State Goal:\n",
                    "Pick the best target_page from PageCandidates for the task.\n",
                    "Return exactly one command: ROUTE <package_name> <target_page> OR FAIL <reason>.\n",
                    "Examples:\n",
                    "ROUTE com.baidu.tieba home\n",
                    "ROUTE com.taobao.taobao home\n",
                    "FAIL target_page_unknown",
                ]
            )

        if state == CortexState.VISION_ACT:
            w = int(context.device_info.get("width") or 0)
            h = int(context.device_info.get("height") or 0)
            activity_sig = f"{context.current_activity.get('package','')}/{context.current_activity.get('activity','')}"
            return "".join(
                [
                    "State=VISION_ACT\n",
                    self._common_output_rules(),
                    self._dsl_semantics(context),
                    f"Allowed: {', '.join(sorted(allowed_ops))}\n",
                    f"UserTask: {context.user_task}\n",
                    f"ScreenSize: width={w}, height={h}\n",
                    f"CurrentActivity(JSON): {json.dumps(context.current_activity, ensure_ascii=False)}\n",
                    f"ActivitySignature: {activity_sig}\n",
                    f"SameActivityStreak: {context.same_activity_streak}\n",
                    f"LastCommand: {context.last_command or '<none>'}\n",
                    f"SameCommandStreak: {context.same_command_streak}\n",
                    f"RecentRouteTrace(JSON): {json.dumps(context.route_trace[-8:], ensure_ascii=False)}\n",
                    "Screenshot: attached in this request.\n",
                    "State Goal:\n",
                    "Choose ONLY the next best single action.\n",
                    "Important: one turn = one command. Do NOT output TAP then DONE in the same response.\n",
                    "Anti-loop Rule: if activity/screen seems unchanged and last command already repeated, do NOT repeat same TAP.\n",
                    "In that case, choose another action (SWIPE/WAIT/INPUT) or output FAIL with reason.\n",
                    "If finished now, output DONE only.\n",
                    "Examples:\n",
                    "TAP 640 420\n",
                    "SWIPE 640 2200 640 900 350\n",
                    "INPUT \"搜索词\"\n",
                    "WAIT 800\n",
                    "DONE\n",
                    "FAIL blocked_by_popup",
                ]
            )

        return f"State={state.value}"


class LLMPlanner:
    def __init__(self, complete: Callable[[str], str], complete_with_image: Optional[Callable[[str, bytes], str]] = None):
        self._complete = complete
        self._complete_with_image = complete_with_image

    def plan(self, state: CortexState, prompt: str, context: CortexContext) -> str:
        return self._complete(prompt)

    def plan_vision(self, state: CortexState, prompt: str, context: CortexContext, screenshot: bytes) -> str:
        if self._complete_with_image and screenshot:
            return self._complete_with_image(prompt, screenshot)
        return self._complete(prompt)


class CortexFSMEngine:
    _ALLOWED_OPS: Dict[CortexState, Set[str]] = {
        CortexState.APP_RESOLVE: {"SET_APP", "FAIL"},
        CortexState.ROUTE_PLAN: {"ROUTE", "FAIL"},
        CortexState.VISION_ACT: {"TAP", "SWIPE", "INPUT", "WAIT", "DONE", "FAIL"},
    }

    def __init__(
        self,
        client,
        planner: Optional[CommandPlanner] = None,
        route_config: Optional[RouteConfig] = None,
        fsm_config: Optional[FSMConfig] = None,
        log_callback: Optional[Callable[[Dict[str, Any]], None]] = None,
    ):
        self.client = client
        self.route_config = route_config or RouteConfig()
        self.fsm_config = fsm_config or FSMConfig()
        self._log_callback = log_callback
        self._prompt_builder = PromptBuilder()
        self._route_helper = RouteThenActCortex(client=self.client, config=self.route_config)
        self._route_loader = self._route_helper._load_map
        self.planner = planner or RuleBasedPlanner(self._route_loader)
        self._heuristic = HeuristicPlanner()

    def run(
        self,
        user_task: str,
        map_path: str,
        start_page: Optional[str] = None,
        extra_context: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        context = CortexContext(task_id=str(uuid.uuid4()), user_task=user_task, map_path=map_path, start_page=start_page)
        for k, v in (extra_context or {}).items():
            if hasattr(context, k):
                setattr(context, k, v)
        state = CortexState.INIT

        for _ in range(self.fsm_config.max_turns):
            if state == CortexState.INIT:
                state = self._run_init_state(context)
                continue
            if state == CortexState.APP_RESOLVE:
                state = self._run_model_state(context, state)
                continue
            if state == CortexState.ROUTE_PLAN:
                state = self._run_model_state(context, state)
                continue
            if state == CortexState.ROUTING:
                state = self._run_routing_state(context)
                continue
            if state == CortexState.VISION_ACT:
                state = self._run_vision_state(context)
                continue
            if state in {CortexState.FINISH, CortexState.FAIL}:
                break
            context.error = f"unknown_state:{state.value}"
            state = CortexState.FAIL

        result = {
            "status": "success" if state == CortexState.FINISH else "failed",
            "task_id": context.task_id,
            "state": state.value,
            "package_name": context.selected_package,
            "target_page": context.target_page,
            "route_trace": context.route_trace,
            "route_result": context.route_result,
            "command_log": context.command_log,
        }
        if context.error:
            result["reason"] = context.error
        if context.output:
            result["output"] = context.output
        return result

    def _run_model_state(self, context: CortexContext, state: CortexState) -> CortexState:
        allowed = self._ALLOWED_OPS.get(state, set())
        prompt = self._prompt_builder.build(state, context, allowed)
        self._log(context, "llm", "prompt", state=state.value, prompt=prompt)
        try:
            raw = self.planner.plan(state, prompt, context)
        except Exception as e:
            context.error = f"planner_call_failed:{state.value}:{e}"
            self._log(context, "fsm", "planner_call_failed", state=state.value, error=str(e))
            return CortexState.FAIL
        self._log(context, "llm", "response", state=state.value, response=(raw or "")[:4000])
        raw = self._normalize_model_output(raw, state, context)
        try:
            commands = parse_instructions(raw, max_commands=self.fsm_config.max_commands_per_turn)
            validate_allowed(commands, allowed)
        except InstructionError as e:
            context.error = f"instruction_invalid:{e}"
            self._log(context, "fsm", "instruction_invalid", state=state.value, error=str(e), raw=raw)
            return CortexState.FAIL

        self._append_commands(context, state, commands)
        first = commands[0]
        if first.op == "FAIL":
            context.error = "planner_fail:" + " ".join(first.args)
            return CortexState.FAIL
        if state == CortexState.APP_RESOLVE and first.op == "SET_APP":
            context.selected_package = first.args[0]
            return CortexState.ROUTE_PLAN
        if state == CortexState.ROUTE_PLAN and first.op == "ROUTE":
            route_map = self._route_loader(context.map_path)
            requested_target = first.args[1]
            resolved_target = self._route_helper._resolve_target_page(route_map, requested_target)
            if resolved_target not in route_map.pages:
                fallback = self._heuristic.plan(context.user_task, route_map)
                self._log(
                    context,
                    "fsm",
                    "route_target_fallback",
                    requested_target=requested_target,
                    resolved_target=resolved_target,
                    fallback_target=fallback.target_page,
                )
                resolved_target = fallback.target_page
            context.selected_package = first.args[0]
            context.target_page = resolved_target
            return CortexState.ROUTING

        context.error = f"unexpected_op:{first.op}@{state.value}"
        return CortexState.FAIL

    def _run_init_state(self, context: CortexContext) -> CortexState:
        self._log(context, "fsm", "state_enter", state=CortexState.INIT.value)

        # Screen size
        try:
            ok, width, height, density = self.client.get_screen_size()
            if ok:
                context.device_info = {"width": int(width), "height": int(height), "density": int(density)}
            else:
                context.device_info = {"width": 0, "height": 0, "density": 0}
        except Exception:
            context.device_info = {"width": 0, "height": 0, "density": 0}

        # Current activity
        try:
            ok, pkg, activity = self.client.get_activity()
            context.current_activity = {
                "ok": bool(ok),
                "package": str(pkg or ""),
                "activity": str(activity or ""),
            }
        except Exception:
            context.current_activity = {"ok": False, "package": "", "activity": ""}

        # App list from Android (fallback when caller does not provide)
        if not context.app_candidates:
            try:
                raw_apps = self.client.list_apps("user")
                context.app_candidates = _normalize_app_candidates(raw_apps)[:200]
            except Exception:
                context.app_candidates = []

        self._log(
            context,
            "fsm",
            "init_ready",
            device_info=context.device_info,
            current_activity=context.current_activity,
            app_candidates=len(context.app_candidates),
            page_candidates=len(context.page_candidates),
        )
        return CortexState.APP_RESOLVE

    def _run_routing_state(self, context: CortexContext) -> CortexState:
        self._log(context, "fsm", "routing_start", package_name=context.selected_package, target_page=context.target_page)
        planner = FixedPlanPlanner(context.selected_package, context.target_page)
        route_engine = RouteThenActCortex(
            client=self.client,
            planner=planner,
            config=self.route_config,
            action_engine=None,
            log_callback=lambda payload: self._log(context, "route", "route_event", payload=payload),
        )
        result = route_engine.run(user_task=context.user_task, map_path=context.map_path, start_page=context.start_page)
        context.route_result = result
        if result.get("status") != "success":
            context.error = result.get("reason", "route_failed")
            self._log(context, "fsm", "routing_fail", reason=context.error)
            return CortexState.FAIL
        context.route_trace = list(result.get("route_trace") or [])
        self._log(context, "fsm", "routing_done", steps=len(context.route_trace))
        return CortexState.VISION_ACT

    def _run_vision_state(self, context: CortexContext) -> CortexState:
        if context.vision_turns >= self.fsm_config.max_vision_turns:
            context.error = "vision_turn_limit"
            return CortexState.FAIL
        context.vision_turns += 1

        # Optional one-time settle on first vision turn only.
        if context.vision_turns == 1 and self.fsm_config.screenshot_settle_sec > 0:
            time.sleep(self.fsm_config.screenshot_settle_sec)

        screenshot = self._screenshot()
        if not screenshot:
            context.error = "vision_screenshot_failed"
            self._log(context, "fsm", "vision_screenshot_failed")
            return CortexState.FAIL
        self._log(context, "fsm", "vision_screenshot_ready", size=len(screenshot))
        self._refresh_activity(context)

        allowed = self._ALLOWED_OPS[CortexState.VISION_ACT]
        prompt = self._prompt_builder.build(CortexState.VISION_ACT, context, allowed)
        self._log(context, "llm", "prompt", state=CortexState.VISION_ACT.value, prompt=prompt)
        try:
            if hasattr(self.planner, "plan_vision"):
                raw = self.planner.plan_vision(CortexState.VISION_ACT, prompt, context, screenshot)
            else:
                raw = self.planner.plan(CortexState.VISION_ACT, prompt, context)
        except Exception as e:
            context.error = f"planner_call_failed:VISION_ACT:{e}"
            self._log(context, "fsm", "planner_call_failed", state=CortexState.VISION_ACT.value, error=str(e))
            return CortexState.FAIL
        self._log(context, "llm", "response", state=CortexState.VISION_ACT.value, response=(raw or "")[:4000])
        raw = self._normalize_model_output(raw, CortexState.VISION_ACT, context)

        try:
            # Vision stage is strictly one-command-per-turn.
            commands = parse_instructions(raw, max_commands=1)
            validate_allowed(commands, allowed)
        except InstructionError as e:
            context.error = f"vision_instruction_invalid:{e}"
            self._log(context, "fsm", "vision_instruction_invalid", error=str(e), raw=raw)
            return CortexState.FAIL

        cmd0 = commands[0]
        current_cmd_sig = cmd0.raw.strip()
        if current_cmd_sig and current_cmd_sig == context.last_command:
            context.same_command_streak += 1
        else:
            context.same_command_streak = 1
        context.last_command = current_cmd_sig

        if (
            cmd0.op == "TAP"
            and context.same_command_streak >= 3
            and context.same_activity_streak >= 3
        ):
            context.error = "vision_action_loop_detected:repeated_same_tap"
            self._log(
                context,
                "fsm",
                "vision_action_loop_detected",
                command=current_cmd_sig,
                same_command_streak=context.same_command_streak,
                same_activity_streak=context.same_activity_streak,
            )
            return CortexState.FAIL

        self._append_commands(context, CortexState.VISION_ACT, commands)
        for cmd in commands:
            if cmd.op == "DONE":
                return CortexState.FINISH
            if cmd.op == "FAIL":
                context.error = "vision_fail:" + " ".join(cmd.args)
                return CortexState.FAIL
            if not self._exec_action_command(context, cmd):
                return CortexState.FAIL
        return CortexState.VISION_ACT

    def _exec_action_command(self, context: CortexContext, cmd: Instruction) -> bool:
        try:
            if cmd.op == "TAP":
                x, y = int(cmd.args[0]), int(cmd.args[1])
                if not self._in_screen_bounds(context, x, y):
                    context.error = f"tap_out_of_bounds:{x},{y}"
                    return False
                if self.fsm_config.tap_bind_clickable:
                    tx, ty, bound = self._resolve_tap_clickable(context, x, y)
                    if bound:
                        self._log(context, "exec", "tap_bind_clickable", src_x=x, src_y=y, tap_x=tx, tap_y=ty, bound=bound)
                    else:
                        self._log(context, "exec", "tap_bind_clickable_miss", src_x=x, src_y=y, tap_x=tx, tap_y=ty)
                else:
                    tx, ty = x, y
                    self._log(context, "exec", "tap_bind_clickable_disabled", src_x=x, src_y=y, tap_x=tx, tap_y=ty)
                jx, jy = self._apply_point_jitter(context, tx, ty, self.fsm_config.tap_jitter_sigma_px)
                if (jx, jy) != (tx, ty):
                    self._log(context, "exec", "tap_jitter_applied", base_x=tx, base_y=ty, tap_x=jx, tap_y=jy, sigma=self.fsm_config.tap_jitter_sigma_px)
                tx, ty = jx, jy
                self._log(context, "exec", "tap_start", x=tx, y=ty)
                self.client.tap(tx, ty)
                self._log(context, "exec", "tap_done", x=tx, y=ty)
                self._wait_for_xml_stable(context, reason="tap")
                return True
            if cmd.op == "SWIPE":
                x1, y1, x2, y2, dur = [int(v) for v in cmd.args]
                if not self._in_screen_bounds(context, x1, y1) or not self._in_screen_bounds(context, x2, y2):
                    context.error = f"swipe_out_of_bounds:{x1},{y1}->{x2},{y2}"
                    return False
                jx1, jy1 = self._apply_point_jitter(context, x1, y1, self.fsm_config.swipe_jitter_sigma_px)
                jx2, jy2 = self._apply_point_jitter(context, x2, y2, self.fsm_config.swipe_jitter_sigma_px)
                jdur = self._apply_duration_jitter(dur, self.fsm_config.swipe_duration_jitter_ratio)
                if (jx1, jy1, jx2, jy2, jdur) != (x1, y1, x2, y2, dur):
                    self._log(
                        context,
                        "exec",
                        "swipe_jitter_applied",
                        base_x1=x1,
                        base_y1=y1,
                        base_x2=x2,
                        base_y2=y2,
                        base_duration=dur,
                        x1=jx1,
                        y1=jy1,
                        x2=jx2,
                        y2=jy2,
                        duration=jdur,
                        sigma=self.fsm_config.swipe_jitter_sigma_px,
                        duration_ratio=self.fsm_config.swipe_duration_jitter_ratio,
                    )
                x1, y1, x2, y2, dur = jx1, jy1, jx2, jy2, jdur
                self._log(context, "exec", "swipe_start", x1=x1, y1=y1, x2=x2, y2=y2, duration=dur)
                self.client.swipe(x1, y1, x2, y2, duration=dur)
                self._log(context, "exec", "swipe_done", x1=x1, y1=y1, x2=x2, y2=y2, duration=dur)
                self._wait_for_xml_stable(context, reason="swipe")
                return True
            if cmd.op == "INPUT":
                self._log(context, "exec", "input_start", text=cmd.args[0])
                self.client.input_text(cmd.args[0])
                if self.fsm_config.action_interval_sec > 0:
                    time.sleep(self.fsm_config.action_interval_sec)
                self._log(context, "exec", "input_done")
                return True
            if cmd.op == "WAIT":
                self._log(context, "exec", "wait_start", ms=int(cmd.args[0]))
                time.sleep(max(0, int(cmd.args[0])) / 1000.0)
                self._log(context, "exec", "wait_done", ms=int(cmd.args[0]))
                return True
            context.error = f"unsupported_action_op:{cmd.op}"
            return False
        except Exception as e:
            context.error = f"action_exec_error:{cmd.op}:{e}"
            self._log(context, "fsm", "action_error", op=cmd.op, error=str(e))
            return False

    def _append_commands(self, context: CortexContext, state: CortexState, commands: List[Instruction]) -> None:
        for cmd in commands:
            context.command_log.append({"state": state.value, "op": cmd.op, "args": cmd.args, "raw": cmd.raw})
            self._log(context, "fsm", "command", state=state.value, op=cmd.op, args=cmd.args)

    def _screenshot(self) -> Optional[bytes]:
        try:
            return self.client.request_screenshot()
        except Exception:
            return None

    def _in_screen_bounds(self, context: CortexContext, x: int, y: int) -> bool:
        w = int(context.device_info.get("width") or 0)
        h = int(context.device_info.get("height") or 0)
        if w <= 0 or h <= 0:
            return True
        return 0 <= x < w and 0 <= y < h

    def _apply_point_jitter(self, context: CortexContext, x: int, y: int, sigma: float) -> tuple[int, int]:
        if sigma <= 0:
            return x, y
        jx = int(round(random.gauss(x, sigma)))
        jy = int(round(random.gauss(y, sigma)))
        w = int(context.device_info.get("width") or 0)
        h = int(context.device_info.get("height") or 0)
        if w > 0:
            jx = max(0, min(w - 1, jx))
        if h > 0:
            jy = max(0, min(h - 1, jy))
        return jx, jy

    def _apply_duration_jitter(self, duration_ms: int, ratio: float) -> int:
        if duration_ms <= 0 or ratio <= 0:
            return max(1, duration_ms)
        sigma = max(1.0, duration_ms * ratio)
        jittered = int(round(random.gauss(duration_ms, sigma)))
        return max(80, jittered)

    def _wait_for_xml_stable(self, context: CortexContext, reason: str) -> None:
        interval = max(0.05, float(self.fsm_config.xml_stable_interval_sec))
        stable_needed = max(2, int(self.fsm_config.xml_stable_samples))
        timeout = max(interval, float(self.fsm_config.xml_stable_timeout_sec))
        deadline = time.time() + timeout

        stable_count = 0
        last_sig = ""
        samples = 0
        self._log(
            context,
            "exec",
            "xml_wait_start",
            reason=reason,
            interval_sec=interval,
            stable_samples=stable_needed,
            timeout_sec=timeout,
        )

        while time.time() < deadline:
            sig = self._dump_actions_signature()
            samples += 1
            if not sig:
                self._log(context, "exec", "xml_wait_skip", reason=reason, samples=samples, why="dump_actions_unavailable")
                return
            if sig and sig == last_sig:
                stable_count += 1
            else:
                stable_count = 1
                last_sig = sig

            if stable_count >= stable_needed:
                self._log(
                    context,
                    "exec",
                    "xml_wait_stable",
                    reason=reason,
                    samples=samples,
                    stable_count=stable_count,
                )
                return
            time.sleep(interval)

        self._log(
            context,
            "exec",
            "xml_wait_timeout",
            reason=reason,
            samples=samples,
            stable_count=stable_count,
        )

    def _dump_actions_signature(self) -> str:
        try:
            raw = self.client.dump_actions() or {}
            nodes = raw.get("nodes") or []
        except Exception:
            return ""

        tokens: List[str] = []
        # Keep order to preserve structural changes, but limit size for speed.
        for n in nodes[:400]:
            b = n.get("bounds") or [0, 0, 0, 0]
            if not isinstance(b, (list, tuple)) or len(b) < 4:
                b = [0, 0, 0, 0]
            text = str(n.get("text") or "")[:24]
            res = str(n.get("resource_id") or "")[:32]
            cls = str(n.get("class") or "")[:24]
            clickable = "1" if n.get("clickable") else "0"
            tokens.append(
                f"{int(b[0])},{int(b[1])},{int(b[2])},{int(b[3])}|{clickable}|{text}|{res}|{cls}"
            )
        return "|".join(tokens)

    def _refresh_activity(self, context: CortexContext) -> None:
        try:
            ok, pkg, activity = self.client.get_activity()
            context.current_activity = {
                "ok": bool(ok),
                "package": str(pkg or ""),
                "activity": str(activity or ""),
            }
            sig = f"{context.current_activity.get('package','')}/{context.current_activity.get('activity','')}"
            if sig == context.last_activity_sig:
                context.same_activity_streak += 1
            else:
                context.same_activity_streak = 1
            context.last_activity_sig = sig
            self._log(context, "fsm", "activity_refreshed", current_activity=context.current_activity)
        except Exception as e:
            self._log(context, "fsm", "activity_refresh_failed", error=str(e))

    def _resolve_tap_clickable(self, context: CortexContext, x: int, y: int) -> tuple[int, int, Optional[List[int]]]:
        """
        Map a TAP point to the center of the smallest clickable bounds that contains it.
        If no clickable container contains the point, keep original coordinates.
        """
        try:
            raw = self.client.dump_actions() or {}
            nodes = raw.get("nodes") or []
        except Exception:
            return x, y, None

        candidates: List[List[int]] = []
        for n in nodes:
            if not bool(n.get("clickable")):
                continue
            b = n.get("bounds")
            if not isinstance(b, (list, tuple)) or len(b) < 4:
                continue
            try:
                l, t, r, btm = int(b[0]), int(b[1]), int(b[2]), int(b[3])
            except Exception:
                continue
            if r <= l or btm <= t:
                continue
            if l <= x <= r and t <= y <= btm:
                candidates.append([l, t, r, btm])

        if not candidates:
            return x, y, None

        width = int(context.device_info.get("width") or 0)
        height = int(context.device_info.get("height") or 0)
        screen_area = max(1, width * height)

        def _area(bb: List[int]) -> int:
            return max(0, bb[2] - bb[0]) * max(0, bb[3] - bb[1])

        def _center(bb: List[int]) -> tuple[int, int]:
            return ((bb[0] + bb[2]) // 2, (bb[1] + bb[3]) // 2)

        # Primary strategy: smallest area + nearest center.
        candidates.sort(
            key=lambda bb: (
                _area(bb),
                abs(_center(bb)[0] - x) + abs(_center(bb)[1] - y),
            )
        )
        pick = candidates[0]
        tx = (pick[0] + pick[2]) // 2
        ty = (pick[1] + pick[3]) // 2

        # Safety gate:
        # If only a very large clickable container matches and rebinding would move
        # the tap too far away, keep the original model coordinate.
        picked_area = _area(pick)
        picked_ratio = picked_area / float(screen_area)
        move_dist = abs(tx - x) + abs(ty - y)
        if picked_ratio >= 0.10 and move_dist >= 120:
            return x, y, None

        return tx, ty, pick

    def _normalize_model_output(self, raw: str, state: CortexState, context: CortexContext) -> str:
        text = (raw or "").strip()
        if not text or not text.startswith("{"):
            return text
        try:
            obj = json.loads(text)
        except Exception:
            return text
        if not isinstance(obj, dict):
            return text
        if state == CortexState.APP_RESOLVE:
            pkg = str(obj.get("package_name") or obj.get("package") or "").strip()
            if pkg:
                return f"SET_APP {pkg}"
        if state == CortexState.ROUTE_PLAN:
            pkg = str(obj.get("package_name") or context.selected_package).strip()
            target = str(obj.get("target_page") or "").strip()
            if pkg and target:
                return f"ROUTE {pkg} {target}"
        if state == CortexState.VISION_ACT:
            action = str(obj.get("action") or "").strip().upper()
            if action == "DONE":
                return "DONE"
        return text

    def _log(self, context: CortexContext, stage: str, event: str, **kwargs: Any) -> None:
        payload = {
            "ts": datetime.now(timezone.utc).isoformat(),
            "task_id": context.task_id,
            "stage": stage,
            "event": event,
            **kwargs,
        }
        if self._log_callback:
            self._log_callback(payload)
        else:
            print(json.dumps(payload, ensure_ascii=False))


def _normalize_app_candidates(raw_apps: Any) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for item in raw_apps or []:
        if isinstance(item, dict):
            pkg = str(item.get("package") or "").strip()
            if not pkg:
                continue
            name = str(item.get("name") or item.get("label") or "").strip()
            out.append({"package": pkg, "name": name})
            continue
        pkg = str(item or "").strip()
        if not pkg:
            continue
        out.append({"package": pkg, "name": pkg.split(".")[-1]})
    return out
