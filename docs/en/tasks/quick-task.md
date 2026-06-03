# Quick Tasks

A quick task runs once immediately. It does not require a rule or a schedule, so it is useful for trial runs and device configuration tests.

## Entry

Entry: `Tasks -> Direct task -> Quick task`.

The quick task page has one main input and one run button:

| Frontend field / button | Meaning | Required | Example |
| --- | --- | --- | --- |
| Describe what you want to do | Enter the task to run right now. | Yes | Open WeChat and send hello to File Transfer. |
| Run | Submit the current task description to Core. | - | - |

## When to use quick tasks

Use quick tasks when:

- You want to try a new task description.
- You are not sure whether the model can understand the task.
- You just changed model or control settings and want to test them.
- You want to trial a task description before creating a task template.

## Examples

```text
Open WeChat and send hello to File Transfer.
Open Bilibili and publish a test post.
Open an app, enter the check-in page, and complete check-in.
```

## Writing good task descriptions

Prefer a clear order: "open where -> find what -> do what".

```text
Open WeChat, enter File Transfer, and send hello.
```

If the task involves a specific contact, group, product, or page, include that detail:

```text
Open WeChat, enter the group named Project Discussion, and reply to the person who just sent a message: received.
```

## What to check after running

After submitting the task, check:

1. Whether the home or task page shows that a task is running.
2. Whether the phone UI opens and operates as expected.
3. Whether the Logs page records execution details and the final result.

If a quick task works reliably, create a task template with the same description, target app, and user playbook. For long-term automation, add that template to a workflow and configure a schedule or notification trigger there.

!!! warning "Do not start too complex"
    Quick tasks are best for one clear path. If a task has many conditions, switches between multiple apps, or waits for a long time, split it into smaller tasks first.
