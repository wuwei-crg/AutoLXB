# Current Logging State

## Scope

Local codebase research for unifying startup/app-side logs with core runtime trace in the Android app Logs page and export flow.

## Existing Channels

### App-side transient log lines

* `MainViewModel` owns `_logLines` / `logLines` at `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainViewModel.kt:230`.
* `appendLog` keeps only the latest 500 strings in memory at `MainViewModel.kt:2366`.
* Search found no UI consumer of `logLines`; the current Logs page does not display it.
* Producers include startup/config/map/core/app-list/template/workflow/export paths:
  * Startup map sync and core start requests write `[MAP]` / `[CORE]`.
  * `refreshInstalledAppSnapshotOnDevice` writes `[APPS]` for installed-app list refresh failures/success.
  * LLM, template, workflow, map sync, and portable export flows also append app-side log lines.

### Quick Task conversation messages

* Quick Task page renders `TaskSessionCard` at `MainActivity.kt:1494`.
* It displays `chatMessages`, not `logLines`.
* `appendSystemMessage` adds user-facing status bubbles to Quick Task chat at `MainViewModel.kt:2389`.
* `TaskRuntimeController` maps selected trace push events into system messages at `core/TaskRuntimeController.kt:170`.
* This means Quick Task currently doubles as a task-progress/status surface. Some entries are trace-derived, but this is not the same data source as the Logs page.

### Core runtime trace

* `CortexFacade` owns a runtime `TraceLogger(2000)` ring buffer at `lxb-core/src/main/java/com/lxb/server/cortex/CortexFacade.java:51`.
* `TraceLogger` is an in-memory JSONL ring buffer with pull pagination and optional per-task push at `TraceLogger.java:80`.
* `CortexFacade.handleTracePull` exposes the ring through `CMD_CORTEX_TRACE_PULL` at `CortexFacade.java:187`.
* `LogsTab` renders only `traceLines` from core trace at `MainActivity.kt:4624`.
* `exportAllTraceToDevice` exports only pulled core trace entries at `MainViewModel.kt:1290`; `writeTraceExportFile` writes `lxb-trace-*.jsonl` at `MainViewModel.kt:2514`.

### Startup service / remote core log

* `WirelessAdbBootstrapService` starts core via native starter/root and passes `--log /data/local/tmp/lxb-core.log` at `WirelessAdbBootstrapService.kt:96` and `:1071`.
* Startup verification reads `tail -n 40 /data/local/tmp/lxb-core.log` only when local core port is not ready, then embeds the tail in a compressed failure detail string at `WirelessAdbBootstrapService.kt:894` and `:1214`.
* State transitions are broadcast through `ACTION_STATUS` by `setState` at `WirelessAdbBootstrapService.kt:689`, and `MainViewModel` only stores the latest `WirelessBootstrapStatus`.
* Therefore detailed startup history is neither persisted nor shown/exported from the Logs page.

### Installed app list diagnostics

* App-side installed-app refresh sends `CMD_LIST_APPS` at `MainViewModel.kt:1345` and logs the result through `[APPS]`.
* Core `ExecutionEngine.handleListApps` prints console diagnostics, not trace, at `lxb-core/src/main/java/com/lxb/server/execution/ExecutionEngine.java:529`.
* `UiAutomationWrapper.listApps` uses APK-provided app-label snapshot first, falls back to package-only output, and prints errors with `System.err` at `lxb-core/src/main/java/com/lxb/server/system/UiAutomationWrapper.java:1103`.
* If app-list failures happen before/around startup, the most useful evidence is split between app-side transient logs, service status messages, remote starter/core log, and core console output.

## Constraints From Specs

* Backend logging guidelines define two daemon channels: console logs for startup/socket/protocol diagnostics and structured Cortex Trace for app-visible task behavior.
* The guidelines explicitly say app-side `appendLog("[MAP] ...")` and system messages are for user-visible status, while backend Trace is for daemon execution facts.
* Frontend state uses `AndroidViewModel` plus `StateFlow`; composables should not do file/socket/JSON work.
* Parser behavior and trace mapping should stay in `CoreApiParser` / `TraceEventMapper`, not in Compose.

## Feasible Approaches

### Approach A: UI-only merge of existing in-memory channels

How it works:
* Convert app-side `logLines` into display rows beside `traceLines` in `LogsTab`.
* Export current app-side log lines plus pulled trace entries.

Pros:
* Smallest implementation.
* Immediately surfaces many currently hidden app-side messages.

Cons:
* Startup history is lost if the app process dies.
* Remote `/data/local/tmp/lxb-core.log` is still not exported.
* Does not capture service state history unless added separately.

### Approach B: Persistent app-owned support log

How it works:
* Introduce an app-side `SupportLogStore` or equivalent file-backed JSONL store.
* Route `appendLog`, wireless bootstrap status transitions, app-list refresh results, and export outcomes into structured app log entries with source/phase/level/timestamp.
* Keep core Trace as the runtime trace source, but display/export it through the same Logs page as a separate source.
* Export a support bundle or combined JSONL containing app startup/support logs plus core runtime trace; include remote `/data/local/tmp/lxb-core.log` tail when reachable and safe.

Pros:
* Captures startup failures even before core trace is available.
* Keeps app-side startup ownership in the APK, where the startup process already lives.
* Improves support workflow without forcing all daemon console logs into structured Cortex Trace.

Cons:
* Larger implementation.
* Requires retention policy and privacy filtering decisions.
* Needs careful UI grouping/filtering so Logs page stays readable.

User-feedback caveat:
* This is not enough as the final architecture if it keeps app logs, Quick Task session messages, and core trace as three separate systems. It is only useful as a building block for a real unified event pipeline.

### Approach C: Core-first trace unification

How it works:
* Push more daemon and app-list events into `TraceLogger.event`, add command(s) to pull startup/core logs from the daemon, and keep Logs page primarily Trace-shaped.

Pros:
* Reuses existing trace parser/UI/export semantics.
* Good for core runtime events after the daemon is reachable.

Cons:
* Does not solve failures before core starts/listens.
* Wireless bootstrap and APK-side deployment failures remain outside core.
* Can blur the spec's current distinction between console diagnostics and structured task Trace.

## Recommendation

Use a unified event-pipeline design, not a multi-source viewer that only merges at display/export time. The corrected target is one log domain model with one durable app-visible store and one Logs-page feed. Existing producers should become adapters into that pipeline:

* App/startup/support logs become unified events.
* Quick Task/FSM progress becomes unified events, not a separate chat/session stream.
* Core runtime trace records are adapted into the same unified event model.
* Optional remote startup/core log tail is converted into bounded unified rows when reachable.

This directly addresses startup failures, app-list diagnostics, Quick Task/FSM progress, Logs-page display, and export through one model instead of preserving fragmented log surfaces.

## User Clarification

* Quick Task is FSM-based; treating it as a separate chat/session log surface is a design mistake.
* The Quick Task page should not maintain a separate session log. It may show compact current FSM state, but detailed progress belongs in the unified Logs stream.
