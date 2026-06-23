# Backend Logging Guidelines

The daemon uses two logging channels:

- Console logs with `System.out.println` / `System.err.println` and a stable
  tag prefix, for low-level daemon diagnostics.
- Structured Cortex Trace events through `TraceLogger.event`, for task progress
  and app UI inspection.

There is no backend logging framework dependency.

## Console Logs

Use console logs for startup, socket lifecycle, hidden API/UI automation
fallbacks, protocol errors, and command routing failures. Prefix messages with
the local tag used by the owning class, such as `[LXB]`, `[LXB][Dispatcher]`,
`[LXB][Execution]`, or `[LXB][Perception]`.

Reference files:
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/Main.java`
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/system/UiAutomationWrapper.java`
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/dispatcher/CommandDispatcher.java`

## Structured Trace

Use `TraceLogger.event(eventName, fields)` for Cortex-visible task behavior.
Event names are lowercase snake_case. Fields are `Map<String, Object>` built
with `LinkedHashMap` so serialized JSON is stable and readable.

Trace events automatically add:

- `ts`
- `event`
- caller-provided fields

The Android UI maps selected event names in `TraceEventMapper`, so renaming an
event can be a UI contract change.

Reference files:
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/TraceLogger.java`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/core/TraceEventMapper.kt`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/core/CoreApiParser.kt`

## What To Log

- Startup phase and readiness.
- Accepted TCP clients and client close events.
- Protocol/CRC errors without terminating the daemon.
- Cortex state transitions, app resolve results, route replay results, LLM
  prompt/response milestones, execution actions, and failure reasons.
- Persistence or map changes that future debugging depends on, such as
  `map_set` and task-map replay/adaptation status.

## What Not To Log

- API keys, model provider credentials, unlock PINs, and full user secrets.
- Large screenshots or full binary payloads.
- Excessively large LLM raw responses. If logging raw model text, cap or
  summarize it, following existing Trace UI truncation patterns.

Use app-side `appendLog("[MAP] ...")` and system messages for user-visible
status. Use backend Trace for daemon execution facts.

## Scenario: Unified App/Core Log Envelope

### 1. Scope / Trigger
- Trigger: changing app support logs, core trace rows, or log export/display.

### 2. Signatures
- App rows use `seq`, `ts`, `level`, `logger`, `message`, `attrs`, and `source="app"`.
- Core trace rows use `event` plus the auto-injected `ts`, `logger`, `level`, and `message`.

### 3. Contracts
- App logs are persisted in a bounded recent window of 1000 entries.
- Core trace remains in-memory for MVP.
- Logs-page export writes one JSONL file containing both sources.

### 4. Validation & Error Matrix
- Missing App logger or level -> infer from caller or message.
- Sensitive API keys, tokens, and PIN/pairing values -> redact before persistence/export.
- Newer App log writes must not be overwritten by slower older file rewrites.

### 5. Good/Base/Bad Cases
- Good: startup/bootstrap rows appear in App logs immediately and export with the same envelope.
- Base: older trace rows without a logger are inferred when parsed for display/export.
- Bad: export-only merging or a separate Quick Task session log surface.

### 6. Tests Required
- `AppLogStoreTest` covers JSONL envelope and redaction.
- `TraceLoggerTest` covers auto-added logger/level/message.
- Parser tests cover redaction after core trace pull.

### 7. Wrong vs Correct
#### Wrong
Keep one format for app display and a different format for export.

#### Correct
Use the same unified envelope for both display and export, with App/Core only as physical sources.
