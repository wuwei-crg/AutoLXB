# Portable Bundle Import and Export

Portable bundles move task templates, workflows, and template routes between devices. They preserve task descriptions, target apps, user playbooks, step order, and saved routes as much as possible, so the target device can import and adapt them locally.

## When to export

Export is useful when:

- You have tuned a task template on one device and want to use it on another.
- You want to back up a composed workflow.
- You want to share a template or workflow and let another user test it locally after import.

Export is risky when:

- The template has not been proven to work.
- The task strongly depends on local account state, time, location, or temporary UI state.
- The task performs high-risk actions such as payment, ordering, or sending messages without being tested on the target device.

## Export

Export entries are in edit pages:

- Task Template: open a saved template detail page and tap **Export Template**.
- Workflow: open a saved workflow detail page and tap **Export Workflow**.

If the object has not been saved yet, export shows a prompt asking you to save first.

After export, the frontend immediately shows a result popup with the saved path. The preferred paths are:

```text
Downloads/LXB/Tasks/lxb-template-portable-<timestamp>.json
Downloads/LXB/Tasks/lxb-workflow-portable-<timestamp>.json
```

If the system Downloads directory is unavailable, the app falls back to its own external files directory. The popup shows the final path.

## What the file includes

A template bundle includes:

- Template name and description.
- Target app package.
- User playbook.
- The legacy TASK_DECOMPOSE stage switch.
- Route mode.
- Saved template route.
- Route behavior such as **finish after replay**.

A workflow bundle includes:

- Workflow name and description.
- Step order.
- Template reference for each step.
- Embedded copies of the templates needed by the workflow.
- Routes from those embedded templates.

## What is excluded

To avoid carrying local runtime state to another device, portable bundles exclude:

- Original local object identity.
- Historical run records.
- Historical run times.
- Workflow trigger configuration.
- Trigger enabled state.

After import, workflows start with trigger type empty and trigger disabled. Configure schedule or notification triggering again from the workflow edit page.

## Import

The import entry is on the Tasks page. The frontend button is **Import Portable Bundle**. Select a JSON file and AutoLXB detects template bundles, workflow bundles, and compatible legacy files.

Import success or failure is shown immediately:

- Template import opens the imported template edit page.
- Workflow import opens the imported workflow edit page.
- Failure shows the error reason.

Import generates new local IDs. Duplicate names are allowed; objects are distinguished by ID. Import is transactional: if any template, workflow, or route part fails, the whole import rolls back.

## Legacy compatibility

The importer recognizes older task JSON and route JSON files and migrates them into the current template/workflow model:

- Old schedule-style files become one template plus one workflow.
- Old notification-style files become one template plus one workflow.
- Route-only files become template routes.

After migration, open the edit page and review target app, task description, route, and trigger type.

## Cross-device route adaptation

Some route steps can be reused directly, such as buttons with clear text or descriptions. Other steps may need semantic adaptation on the new device when an XML locator cannot be reused.

On the first run after import, AutoLXB tries to use semantic descriptions and the current screen to find the target UI element again, then saves the adapted result locally.

!!! warning "Always test after import"
    Different devices may have different screen sizes, font scales, app versions, and login states. After importing, run a low-risk manual trigger before enabling scheduled or notification-based execution.
