# Task Flow Trace

Task flow trace helps you understand which phase a task has reached. If a task does nothing, opens the wrong app, or fails suddenly, start here.

## `task_route_key`

This means the route identity for a task run has been determined. It usually appears shortly after the task enters the state machine.

```json
{
  "task_id": "task-20260506-001",
  "source": "workflow",
  "source_id": "daily-checkin",
  "package_name": "com.example.app",
  "user_task": "Open the app and complete check-in",
  "user_playbook": "Tap the check-in button on the home page",
  "task_map_mode": "manual",
  "route_id": "template:daily-checkin",
  "ts": "2026-05-06T09:00:01.120+0800",
  "event": "task_route_key"
}
```

| Field | Meaning |
| --- | --- |
| `task_id` | Identifier of this task run. Use it to connect events from the same run. |
| `source` | Run source, such as quick task, template trigger-now, manual workflow, scheduled workflow, or notification workflow. |
| `source_id` | Internal source object identifier. Users usually do not need to interpret it. |
| `package_name` | The specified or resolved target app package. |
| `user_task` | The task description entered by the user. |
| `user_playbook` | Extra execution guidance entered by the user. |
| `task_map_mode` | Whether task routes are enabled and which route mode is used. |
| `route_id` | Internal route identifier used to look up the route. |
| `ts` | Event time. |
| `event` | Always `task_route_key`. |

## `fsm_state_enter`

This means the state machine entered a phase. These events tell you where the task is stuck.

```json
{
  "task_id": "task-20260506-001",
  "state": "INIT",
  "user_task": "Open the app and complete check-in",
  "ts": "2026-05-06T09:00:01.180+0800",
  "event": "fsm_state_enter"
}
```

| Field | Meaning |
| --- | --- |
| `task_id` | Current task run. |
| `state` | Current phase, such as `INIT`, `APP_RESOLVE`, `ROUTING`, or `VISION_ACT`. |
| `user_task` | Task text handled in this phase. Some phases may omit it. |
| `event` | Always `fsm_state_enter`. |

Common states:

| State | Meaning |
| --- | --- |
| `INIT` | Initialize device state, screen information, and input capability. |
| `TASK_DECOMPOSE` | Try to split the task into smaller steps. |
| `APP_RESOLVE` | Decide which app to open. |
| `ROUTE_PLAN` | Prepare page routing. |
| `PREPARE_DEVICE` | Launch or switch to the target app. |
| `ROUTING` | Execute route replay or page navigation. |
| `VISION_ACT` | Enter vision-model observation and action. |

## `fsm_init_ready`

Initialization has finished. This helps confirm whether device info and current foreground app were read correctly.

```json
{
  "task_id": "task-20260506-001",
  "device_info": {"width": 1080, "height": 2400, "density": 440},
  "current_activity": {"ok": true, "package": "com.android.launcher", "activity": "Launcher"},
  "app_candidates": 120,
  "page_candidates": 0,
  "text_input_support": {"adb_keyboard_installed": true},
  "ts": "2026-05-06T09:00:01.450+0800",
  "event": "fsm_init_ready"
}
```

| Field | Meaning |
| --- | --- |
| `device_info` | Screen size and density. Useful for positioning issues. |
| `current_activity` | Current foreground app and Activity. |
| `app_candidates` | Number of app candidates Core found. `0` may affect app resolution. |
| `page_candidates` | Page candidate count, mainly for debugging. |
| `text_input_support` | Input capability, such as whether ADB Keyboard is detected. |

## `fsm_sub_task_begin` / `fsm_sub_task_end`

A sub-task started or ended. One user task may be decomposed into several sub-tasks.

```json
{
  "task_id": "task-20260506-001",
  "index": 0,
  "sub_task_id": "default",
  "mode": "single",
  "app_hint": "",
  "app_hint_used": false,
  "ts": "2026-05-06T09:00:02.100+0800",
  "event": "fsm_sub_task_begin"
}
```

```json
{
  "task_id": "task-20260506-001",
  "index": 0,
  "sub_task_id": "default",
  "mode": "single",
  "status": "success",
  "ts": "2026-05-06T09:00:18.400+0800",
  "event": "fsm_sub_task_end"
}
```

| Field | Meaning |
| --- | --- |
| `index` | Which sub-task, starting from 0. |
| `sub_task_id` | Internal sub-task identifier. Usually only needed when reporting issues. |
| `mode` | Sub-task mode, such as single-step execution. |
| `status` | Result, commonly `success` or `failed`. |

## `fsm_app_resolve_done`

The target app has been selected.

```json
{
  "task_id": "task-20260506-001",
  "package": "com.example.app",
  "app_name": "Example App",
  "source": "llm",
  "ts": "2026-05-06T09:00:03.000+0800",
  "event": "fsm_app_resolve_done"
}
```

| Field | Meaning |
| --- | --- |
| `package` | Final target app package. |
| `app_name` | App name. |
| `source` | How the app was selected, such as model judgment or fallback logic. |

If this app is wrong, manually select the target app in task configuration or make the task description more explicit.
