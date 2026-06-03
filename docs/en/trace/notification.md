# Notification Trigger Trace

Core trace records notification-trigger related events with the outer event name `notify_trigger`. It means the notification module produced a record. To understand whether it started, skipped, matched, or failed to submit a workflow, inspect the other fields in the same record.

Example:

```json
{
  "ts": "2026-05-06T09:00:00.000+0800",
  "event": "notify_trigger",
  "package": "com.tencent.mm",
  "post_time": 1778040000000,
  "title": "WuWei",
  "text": "Are you there?",
  "rule_id": "rule-1",
  "rule_name": "Reply to WeChat message",
  "task_id": ""
}
```

In exported core trace, `event` is usually `notify_trigger`. Use fields such as `package`, `rule_name`, `raw`, `error`, and `final_task` to understand what happened.

## Common fields

| Field | Meaning |
| --- | --- |
| `package` | Which app produced the notification. |
| `post_time` | Notification post time. |
| `title` | Notification title, cropped to a limited length. |
| `text` | Notification body, cropped to a limited length. |
| `rule_id` | Internal rule identifier. Keep it when reporting issues. |
| `rule_name` | Rule name. |
| `raw` | Raw LLM condition or task rewrite output, may be empty. |
| `error` | Failure or skip reason. Empty usually means no error. |
| `final_task` | Final task description submitted to the execution system. Common after a successful match. |

## Module started record

The notification listener module has started.

```json
{
  "event": "notify_trigger",
  "error": "poll_ms=3000",
  "task_id": "",
  "ts": "2026-05-06T09:00:00.000+0800"
}
```

`poll_ms=3000` indicates the notification polling interval. Seeing this means the notification module itself is running.

## Baseline ready record

The notification module has built its baseline. After the baseline is ready, later notifications are treated as candidate new notifications.

```json
{
  "event": "notify_trigger",
  "error": "last_post_time=1778040000000",
  "task_id": "",
  "ts": "2026-05-06T09:00:03.000+0800"
}
```

## Workflow submitted record

A rule matched and a workflow was submitted.

```json
{
  "event": "notify_trigger",
  "package": "com.tencent.mm",
  "post_time": 1778040001234,
  "title": "WuWei",
  "text": "Are you there?",
  "rule_id": "rule-1",
  "rule_name": "Reply to WeChat message",
  "task_id": "",
  "final_task": "Open WeChat, find WuWei, and reply to the latest message once",
  "ts": "2026-05-06T09:01:10.000+0800"
}
```

| Field | Meaning |
| --- | --- |
| `final_task` | Final task description submitted to the execution system. |
| `task_id` | Notification module records are outside the later task run, so this is usually empty. The submitted workflow will have its own task-flow trace later. |

If you see `final_task` but no later task-flow trace, check whether Core is running, whether another task is occupying the queue, and whether the workflow exists with its trigger enabled.

## LLM condition did not match

Package/title/body matching passed, but the LLM condition said no.

```json
{
  "event": "notify_trigger",
  "package": "com.tencent.mm",
  "title": "WuWei",
  "text": "Received, thanks",
  "rule_id": "rule-1",
  "rule_name": "Reply to WeChat message",
  "raw": "no",
  "task_id": "",
  "ts": "2026-05-06T09:02:00.000+0800"
}
```

If you expected it to trigger, the LLM condition may be too strict.

## Cooldown skip record

The rule matched, but it is still within the cooldown interval, so this notification was skipped.

```json
{
  "event": "notify_trigger",
  "package": "com.tencent.mm",
  "title": "WuWei",
  "text": "Another message",
  "rule_id": "rule-1",
  "rule_name": "Reply to WeChat message",
  "error": "skip",
  "task_id": "",
  "ts": "2026-05-06T09:02:20.000+0800"
}
```

| Field | Meaning |
| --- | --- |
| `error` | Often `skip`, meaning this notification was skipped by rule policy. |

## Workflow submit failed record

The rule matched, but submitting the workflow failed.

```json
{
  "event": "notify_trigger",
  "package": "com.tencent.mm",
  "title": "WuWei",
  "text": "Are you there?",
  "rule_id": "rule-1",
  "rule_name": "Reply to WeChat message",
  "error": "java.lang.IllegalStateException: task queue full",
  "task_id": "",
  "ts": "2026-05-06T09:03:00.000+0800"
}
```

For this kind of event, also check whether another task was running and whether Core was healthy at that time.
