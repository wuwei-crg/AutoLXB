# Trace Overview

Trace is AutoLXB's runtime log system. When a task does not start, a route does not hit, a notification rule does not trigger, the model taps the wrong place, or input fails, check Trace first.

Trace is not something you need to memorize. Its purpose is to help you answer: **which phase did the problem happen in?**

## How to view Trace

Open the app's **Logs** page to see recent core trace events. Each trace appears as a card, and tapping a card opens detailed fields.

The Logs page usually lets you:

- View the latest trace events.
- Open a single trace event's details.
- Load older trace events.
- Export locally cached trace.

## What does Trace look like?

Trace is JSON lines. Each record has an `event` field for the event type and a `ts` field for the timestamp.

The category pages use real AutoLXB trace event names and field structures. Example times, task names, package names, and coordinates are demonstration values; your exported trace file is the source of truth.

Example:

```json
{
  "task_id": "2f1c...",
  "state": "VISION_ACT",
  "size": 384221,
  "attached": true,
  "ts": "2026-05-06T16:20:31.318+0800",
  "event": "vision_screenshot_ready"
}
```

Important fields:

| Field | Meaning |
| --- | --- |
| `event` | What kind of event this record is. |
| `ts` | When the event happened. |
| `task_id` | Which task run this event belongs to. Useful when reporting issues. |
| Other fields | Different events have different fields, such as state, package, failure reason, or coordinates. |

## How to use Trace when something fails

Check in this order:

1. Is there a task-start related event?
2. Did AutoLXB enter the target app?
3. Did it hit or replay the task route?
4. Did it enter visual execution?
5. Did it execute tap, input, swipe, or back actions?
6. What is the final failure reason?

## What to include when reporting issues

When reporting a problem, include:

- Device model and system version.
- Startup method: Root or ADB.
- Run entry: quick task, task template, or workflow. For workflows, include manual, scheduled, or notification trigger when relevant.
- Task description and key configuration screenshots.
- A screenshot of the phone UI when it failed.
- **The exported Trace file**.

!!! tip "Why Trace matters"
    A screenshot only shows where the task stopped. Trace shows why it stopped. Many issues can only be diagnosed from Trace: configuration issues, route failures, notification matching problems, model errors, or device permission problems.

## Categories

The following pages explain common trace categories:

- **Task Flow Trace**: which state the task entered.
- **Task Route Trace**: whether route replay hit, which step failed, and why it fell back.
- **Visual Execution Trace**: what the model saw, what it output, and whether parsing failed.
- **Notification Trigger Trace**: whether notifications were read, rules matched, and workflows were submitted.
- **Action Execution Trace**: whether taps, swipes, input, waits, and back actions actually ran.
