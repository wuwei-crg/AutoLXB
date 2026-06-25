# Task Route Trace

Task route trace helps determine whether a route hit, which step replayed, why it failed, and whether execution fell back to visual execution.

## `task_map_root_lookup`

Core is checking whether this task has a usable route.

```json
{
  "task_id": "task-20260506-001",
  "state": "TASK_MAP_ROOT_LOOKUP",
  "route_id": "daily-checkin",
  "task_map_mode": "manual",
  "ts": "2026-05-06T09:00:01.700+0800",
  "event": "task_map_root_lookup"
}
```

| Field | Meaning |
| --- | --- |
| `state` | Route lookup phase. |
| `route_id` | Internal identifier used to find the route. |
| `task_map_mode` | Current route mode. `off` usually means route execution is disabled. |

## `task_map_root_lookup_miss`

No usable route was found, so the task continues through the normal flow.

```json
{
  "task_id": "task-20260506-001",
  "route_id": "daily-checkin",
  "reason": "map_missing",
  "ts": "2026-05-06T09:00:01.720+0800",
  "event": "task_map_root_lookup_miss"
}
```

| Field | Meaning |
| --- | --- |
| `reason` | Why lookup missed. `map_missing` means no saved route; `map_unusable` means the route cannot be used. |

## `task_map_root_lookup_hit`

A usable route was found.

```json
{
  "task_id": "task-20260506-001",
  "route_id": "daily-checkin",
  "segment_count": 1,
  "step_count": 4,
  "ts": "2026-05-06T09:00:01.730+0800",
  "event": "task_map_root_lookup_hit"
}
```

| Field | Meaning |
| --- | --- |
| `segment_count` | Number of route segments. |
| `step_count` | Number of steps in the route. |

## `fsm_routing_task_map_begin`

Route replay begins for a segment.

```json
{
  "task_id": "task-20260506-001",
  "package": "com.example.app",
  "segment_id": "segment-0",
  "steps": 4,
  "ts": "2026-05-06T09:00:05.100+0800",
  "event": "fsm_routing_task_map_begin"
}
```

| Field | Meaning |
| --- | --- |
| `package` | Target app for this route segment. |
| `segment_id` | Current route segment. Usually only needed for debugging. |
| `steps` | Number of steps in the segment. |

## `fsm_routing_task_map_step_start`

A route step is about to execute.

```json
{
  "task_id": "task-20260506-001",
  "package": "com.example.app",
  "segment_id": "segment-0",
  "step_id": "step-2",
  "source_action_id": "action-2",
  "index": 2,
  "op": "TAP",
  "ts": "2026-05-06T09:00:06.300+0800",
  "event": "fsm_routing_task_map_step_start"
}
```

| Field | Meaning |
| --- | --- |
| `index` | Step index, starting from 0. |
| `op` | Operation type, such as `TAP`, `SWIPE`, `INPUT`, `BACK`, or `WAIT`. |
| `step_id` / `source_action_id` | Internal debugging identifiers. Keep them when reporting issues. |

## `fsm_routing_task_map_step_end`

A route step finished. This is the most important event when diagnosing route failures.

```json
{
  "index": 2,
  "step_id": "step-2",
  "source_action_id": "action-2",
  "op": "TAP",
  "picked_stage": "locator:text",
  "picked_bounds": [120, 1800, 960, 1920],
  "picked_point": [540, 1860],
  "result": "ok",
  "reason": "",
  "ts": "2026-05-06T09:00:06.520+0800",
  "event": "fsm_routing_task_map_step_end"
}
```

| Field | Meaning |
| --- | --- |
| `result` | Whether the step succeeded. Common values: `ok`, `tap_fail`, `swipe_fail`, `resolve_fail`, `unsupported`. |
| `reason` | Failure reason. Empty usually means success. |
| `picked_stage` | How the target was found, such as XML locator stages or semantic visual fallback. |
| `picked_bounds` | Bounds of the matched control. |
| `picked_point` | Actual tap coordinate. |

For imported tasks with semantic tap steps, this event may also include:

| Field | Meaning |
| --- | --- |
| `adaptation_status` | Local adaptation status, such as adapted or failed. |
| `portable_kind` | Whether the step is still portable semantic form or has been materialized for this device. |

These fields usually appear during the first route replay after importing a task from another device.

## `llm_prompt_semantic_adaptation`

A semantic tap step has not been adapted to this device yet, so AutoLXB is about to ask the model to locate the target from the screenshot.

```json
{
  "task_id": "task-20260506-001",
  "route_id": "daily-checkin",
  "segment_id": "segment-0",
  "step_id": "step-2",
  "prompt": "You are adapting a portable Android route step...",
  "ts": "2026-05-06T09:00:06.360+0800",
  "event": "llm_prompt_semantic_adaptation"
}
```

| Field | Meaning |
| --- | --- |
| `route_id` | Internal route identifier. |
| `segment_id` | Current route segment. |
| `step_id` | Step that needs local adaptation. |
| `prompt` | Prompt sent to the model. Useful for complex semantic-location issues. |

## `llm_response_semantic_adaptation`

The semantic adaptation model returned a result.

```json
{
  "task_id": "task-20260506-001",
  "route_id": "daily-checkin",
  "segment_id": "segment-0",
  "step_id": "step-2",
  "response": "{\"result\":\"point\",\"x\":540,\"y\":1860}",
  "ts": "2026-05-06T09:00:07.020+0800",
  "event": "llm_response_semantic_adaptation"
}
```

| Field | Meaning |
| --- | --- |
| `response` | Raw model response. On success it usually returns a target point; on failure it may report no match or ambiguity. |

## `task_map_semantic_adaptation_materialized`

The semantic tap step was adapted successfully and saved as a local route step.

```json
{
  "task_id": "task-20260506-001",
  "route_id": "daily-checkin",
  "segment_id": "segment-0",
  "step_id": "step-2",
  "portable_kind": "materialized",
  "adaptation_status": "adapted",
  "ts": "2026-05-06T09:00:07.180+0800",
  "event": "task_map_semantic_adaptation_materialized"
}
```

After this event, the same device can reuse any XML locator produced during adaptation. If no unique XML locator was produced, replay still falls back to semantic visual targeting for that step.

## `task_map_semantic_adaptation_failed`

Semantic adaptation failed.

```json
{
  "task_id": "task-20260506-001",
  "route_id": "daily-checkin",
  "segment_id": "segment-0",
  "step_id": "step-2",
  "reason": "semantic_adaptation_no_match:target not found",
  "ts": "2026-05-06T09:00:07.200+0800",
  "event": "task_map_semantic_adaptation_failed"
}
```

| Field | Meaning |
| --- | --- |
| `reason` | Failure reason, such as screenshot missing, target not found, or ambiguous target. |

If this fails, the task usually falls back to visual execution. Check whether the imported task description is clear, the page is the same, and no popup or ad is blocking the UI.

## `fsm_routing_task_map_fallback`

Route replay failed and the task fell back to visual execution.

```json
{
  "task_id": "task-20260506-001",
  "package": "com.example.app",
  "segment_id": "segment-0",
  "failed_index": 2,
  "reason": "task_map_locator_missing_fallback",
  "ts": "2026-05-06T09:00:07.000+0800",
  "event": "fsm_routing_task_map_fallback"
}
```

| Field | Meaning |
| --- | --- |
| `failed_index` | Which step failed. Combine it with step start/end events. |
| `reason` | Fallback reason, such as control not found, semantic target not matched, or popup blocking the page. |

## `fsm_routing_task_map_done`

Route replay succeeded.

```json
{
  "task_id": "task-20260506-001",
  "package": "com.example.app",
  "segment_id": "segment-0",
  "steps": 4,
  "ts": "2026-05-06T09:00:08.500+0800",
  "event": "fsm_routing_task_map_done"
}
```

If it is followed by `fsm_routing_task_map_finish_after_replay`, the task ended directly after successful route replay.
