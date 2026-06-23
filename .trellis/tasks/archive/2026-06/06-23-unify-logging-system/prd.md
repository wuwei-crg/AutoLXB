# brainstorm: unify startup and runtime logging

## Goal

Unify startup logs, app-side operational logs, FSM/runtime trace, and current Quick Task session/status messages into one real logging system so startup failures and runtime issues are diagnosable from the Logs page, and users can export the same unified log stream for support.

## What I already know

* Startup logs and core runtime traces are currently separated.
* Startup failures can leave no useful evidence for support, e.g. a user reports that the application list cannot be loaded.
* Some logging/progress is currently visible inside the Quick Task dialog through `chatMessages`; this is a wrong separate session-log surface because Quick Task is also FSM-based.
* The desired user-facing outcome is: all relevant logs are shown in the Logs page and can be exported.
* `MainViewModel` already has app-side `logLines`, but code search found no UI consumer; current Logs page shows only core trace.
* Quick Task displays `chatMessages`, including status messages derived from selected trace push events. This duplicates/splits FSM logging and should be replaced by core log entries visible in the Core logs page.
* Wireless startup has detailed service states and can tail `/data/local/tmp/lxb-core.log` on failures, but that history is not persisted or exported from the Logs page.
* The installed-app list path currently logs app-side `[APPS]` results and core console messages, not structured trace entries.
* The Logs page itself must show the unified log stream. Unification must not happen only at export time.
* `WirelessAdbBootstrapService.setState(...)` already has useful startup state/message changes, but it must keep its existing broadcast/notification behavior. The logging change is an added App log write, not a replacement for status delivery.
* Startup diagnosis already has useful raw facts in the service: endpoint candidates, ADB connect result, deploy result, starter start result, app-label snapshot deploy result, port readiness, process snapshot, and remote core log tail. These need to become App log rows instead of only being folded into return strings.

## Assumptions (temporary)

* Startup logging should begin early enough to capture initialization and app-list loading failures.
* Runtime trace output should remain available for existing debugging workflows by flowing through the unified logging system.
* Quick Task must not own a separate session log after this feature.
* The user support workflow benefits more from persistent recent logs than from current-session-only logs.
* The unified Logs page should reflect live activity as it happens while also loading persisted recent logs.

## Open Questions

* None.

## Backend Behavior (evolving)

* Capture startup, app operational, Quick Task/FSM progress, and core trace records under one shared event model.
* Preserve enough metadata to distinguish producers and keep useful structured details without freezing a rigid set of context fields.
* Support export of logs needed for support diagnosis.
* Revised direction: create one standard log event format and one app-facing read projection, while allowing App source logs and Core source logs to keep separate physical retention.
* Existing producers (`appendLog`, `WirelessAdbBootstrapService.setState`, `TraceLogger` pull/push, `TraceEventMapper` quick-task messages) should emit or be adapted into the same standard log event shape, not kept as separate user-facing log formats.
* Existing log/progress content must be reused according to `research/log-content-inventory.md`; implementation should not invent log rows without mapping the current producers first.
* Unified log records should include at least timestamp, level, logger/component, message, and optional structured `attrs`.
* `WirelessAdbBootstrapService` is part of the App source. Its state transitions should use the same App logging entry/store/format as `MainViewModel` logs, and should be recorded durably instead of only broadcasting the latest `WirelessBootstrapStatus`.
* App-list diagnostics should be captured when app label snapshot creation/deploy, `CMD_LIST_APPS`, or core list-app fallback fails.
* Export should serialize the unified log stream shown by Logs. If reachable and safe, it may include bounded remote core log tail as unified entries.
* MVP retention scope is persistent recent support logs, not current-session-only and not fully configurable history.
* Persistent support logs should have a bounded default window so startup evidence survives app restarts without unbounded storage growth.
* MVP export shape is one unified JSONL support file. Each exported row must carry logger/component plus optional structured `attrs` so app/startup support logs and core trace records remain distinguishable and searchable.
* MVP persistent support-log retention uses a fixed entry count window, not time-based or size-based retention.
* App-side persistent support-log retention keeps the newest 1000 entries by default.
* The 1000-entry retention limit should be centralized as a single constant so it is easy to adjust later.
* All existing app-side `appendLog(...)` calls should feed the persistent support log for MVP, not only startup/app-list or error-only events.
* App-side persistent log entries should use the emitting code component / emitting point as `logger`, not legacy business prefixes. Examples: `MainViewModel`, `WirelessAdbBootstrapService`, `TaskRuntimeController`, `ExecutionEngine.apps`.
* Core app-list diagnostics should be promoted into structured runtime trace events, not left only in `System.out/err`.
* App-list trace coverage should include start/result/error or equivalent events, with enough fields to distinguish label snapshot success, package-only fallback, empty result, and exceptions.
* Logs page and export should both consume the same unified app-facing log projection; they must not each implement their own merge logic.
* Quick Task/FSM progress events should be unified log entries. Quick Task UI may show current runtime state, but it must not maintain a separate chat/session log stream.
* The old Quick Task entry/jump page should be removed. The Tasks page should directly contain only the Quick Task input box and Run action for this flow.
* Unified events should not use a large mutually exclusive `domain` taxonomy such as `startup/runtime/fsm/apps/llm/workflow`; those boundaries overlap and would fragment the model again.
* Use a conventional logging shape: one event has one emitting logger/component plus optional local structured attrs. Logger names should point to the code component / emitting point rather than a separate business taxonomy.
* FSM, LLM, app-list, and runtime relationships must be expressed by the emitting logger/component itself, not by nested or competing categories.
* Do not require a fixed context schema such as `startupAttemptId`, `taskRunId`, `fsmRunId`, `commandId`, and `appListRefreshId` on every event.
* Startup versus runtime should not become hard event classes that compete with FSM/core/app categories. Startup/bootstrap entries are app-source logs with their own logger names/messages/attrs.
* App source logs and Core source logs may remain physically separate, but both sides must write or expose the same event envelope: `ts`, `level`, `logger`, `message`, optional `attrs`.
* The integration point should be a shared log repository/projection in the app layer, not export-time-only stitching. Logs page display and export should read the same projection.
* The shared projection should expose a coarse UI section boundary, at minimum `app` and `core`. This is a physical/source boundary because app/frontend and core/backend read from different sources, not a logical/business taxonomy.
* Core runtime trace is not persisted for MVP. It should remain bounded by the existing in-memory `TraceLogger` ring buffer, but records exposed to the app must be adapted to the standard event envelope.
* App logs, including `WirelessAdbBootstrapService` startup/bootstrap logs, remain the durable source for startup/support diagnostics. Core runtime history may be lost across core daemon restart in MVP.
* Current core trace rows lack a `logger` field. Implementation must populate `logger` from the emitting code component, preferably without adding manual context plumbing to every trace call site.
* Add one App-side logging entry point/store used by all App producers. `MainViewModel.appendLog(...)`, `MapOperationsController`, `TaskRuntimeController`, and `WirelessAdbBootstrapService` must write through that same App log entry point.
* `MainViewModel.appendLog(...)` may remain as a compatibility wrapper, but the real App log source becomes the shared App logger/store with newest-1000 retention and redaction before display/export.
* `WirelessAdbBootstrapService.setState(...)` must add an App log row for the state transition while preserving `currentState/currentMessage`, `ACTION_STATUS` extras, `sendBroadcast`, and existing notification update call sites.
* Notification/status updates are allowed to remain as separate side effects. The requirement is that each important App event also writes into the unified App log.
* App startup logs to add include service action received, foreground bootstrap service start, discovery start/failure, endpoint detected, pairing check/result, endpoint candidate/connect result, core launch verification start, starter ABI resolution, core jar deploy, starter deploy, app labels deploy, starter launch attempt, port readiness, remote core log tail/process snapshot on failure, stop result, and watchdog recovery result.
* App-list support path must show App-side startup/app-label deploy/installed-app refresh logs plus Core-side `list_apps_*` trace events.

## Frontend/User-Facing Interface (evolving)

* Logs page should become the single primary location for viewing logs.
* Logs page should display logs in coarse `App` and `Core` pages rather than one mixed default feed.
* `App` logs should cover startup/bootstrap, app-side operational, app-list, config/map/workflow/export, and other Android-app-produced events.
* `Core` logs should cover backend/core trace records, including FSM/Quick Task runtime progress and core app-list diagnostics.
* Logs page should support exporting both categories together as one unified JSONL support file.
* Quick Task should no longer be a separate navigated page. The Tasks page should directly show the task input and Run button.
* Quick Task UI should not show a chat/session log or a recent-log panel.
* MVP Logs page should not add extra controls for narrowing results. `App` and `Core` source pages plus the per-row `logger` are the intended distinction mechanism.

## Decision Log

* Initial task creation -> create planning task and seed PRD -> requirements will be refined through backend-first then frontend/user-facing brainstorming.
* Codebase exploration -> current state recorded in `research/current-logging-state.md` -> recommended MVP is a persistent app-owned support log plus existing core trace export/display.
* Backend retention scope? -> choose persistent recent support logs -> MVP must persist a bounded recent log window for startup/app-side diagnostics.
* Backend export shape? -> choose one unified JSONL support file -> user support workflow should require one exported file while preserving source metadata per row.
* Backend retention policy? -> choose fixed recent entry count -> support-log store should prune by count with a centralized default limit.
* Which app-side events persist? -> choose all existing `appendLog(...)` messages -> persistent support log should capture all current app-side operational log lines with source classification and API key / PIN redaction.
* Should app-list/core console diagnostics be structured trace? -> yes -> add structured TraceLogger events around `CMD_LIST_APPS` / list-app fallback behavior so runtime trace explains empty/failing app lists.
* Frontend display scope? -> Logs page must show both app/startup support logs and core trace -> export is a secondary action over the same unified log model, not the merge point.
* Quick Task session surface? -> user rejected separate Quick Task session logging as useless/wrong because Quick Task is FSM-based -> remove separate Quick Task chat/session log as a logging surface and derive any status from unified FSM/log state.
* Quick Task placement? -> remove the Quick Task entry/jump page -> Tasks page directly contains only the quick task input box and Run action for quick task submission.
* Unified log classification? -> user rejected a fine-grained domain taxonomy because FSM, LLM, runtime, and app-list boundaries overlap -> use one emitter/logger field plus optional structured attrs instead of nested or competing categories.
* Unified log context shape? -> user rejected a rigid fixed context field list as too boilerplate -> keep only logger/component plus attrs, with no refs/tags layer.
* Logger naming? -> use the code component / emitting point as `logger` -> avoid a separate business taxonomy and make logs point directly to the producer code.
* App/Core source ownership? -> keep App source and Core source logs physically separate but format-identical -> unify through schema and shared app-facing projection rather than forcing one cross-process persistent store.
* Core retention? -> do not persist core trace for MVP -> keep existing bounded in-memory trace ring, adapted to the standard event envelope for Logs/export.
* Logs page display? -> use coarse category pages with `App` and `Core` shown separately -> preserve one common export over both categories.
* Logs page controls? -> do not add narrowing controls for MVP -> App/Core are source pages, and logger is the source detail.
* App-side retention count? -> 1000 entries -> app persistent log store keeps the newest 1000 entries and prunes older entries.
* App-side unified logger/store? -> add one App log entry point shared by all App producers -> `MainViewModel.appendLog(...)` becomes a wrapper, and `WirelessAdbBootstrapService` writes into the same App log source.
* Startup `setState(...)` handling? -> keep broadcast/notification behavior unchanged and add an App log write -> support logging must not change the startup status contract.
* Startup log content? -> add rows for action received, discovery, endpoint attempts, deploys, app labels, starter attempts, port readiness, failure diagnostics, stop, and watchdog -> startup diagnosis should be reconstructable from App logs alone; implementation follows `research/log-content-inventory.md#concrete-app-side-logging-plan`.
* Redaction rule? -> mask API keys and unlock PINs -> task text, LLM prompts, and LLM responses are not automatically removed by the unified logging layer unless they contain those secrets.
* Existing log content inventory? -> existing messages/states/events exist in app `appendLog`, App service `WirelessAdbBootstrapService.setState`, Core `TraceLogger`, and Core app-list console diagnostics -> record mapping in `research/log-content-inventory.md` and require implementation to follow it.

## Requirements (evolving)

* One unified logging system with shared event format, shared app-facing read projection, Logs-page display, and export.
* App source and Core source may keep separate write paths and physical stores/ring buffers, but they must expose the same standard event shape.
* Existing messages/states/events to migrate include app `appendLog(...)`, App service `WirelessAdbBootstrapService.setState(...)`, Core `TraceLogger.event(...)`, and Core app-list diagnostics currently printed to console.
* Core/backend runtime trace is not persisted for MVP; existing bounded trace retention remains acceptable.
* Unified display means the Logs page itself shows startup, app operational, Quick Task/FSM, app-list, and core runtime events using the same event format, but grouped into `App` and `Core` pages.
* Export support for the same unified log stream shown in Logs.
* Better diagnostics for startup/app-list loading failures.
* Persist recent app/startup support logs with a bounded retention window.
* Retention is fixed-count based for MVP, with app-side persistent logs keeping the newest 1000 entries.
* All current app-side operational log lines emitted through `appendLog(...)` are included in the persistent support log.
* Core app-list diagnostics are represented as structured runtime trace events.
* Quick Task/FSM progress is represented as unified log events, not a separate chat/session log.
* Quick Task submission is available directly on the Tasks page with only an input box and Run button; no separate Quick Task navigation page.
* Export one unified JSONL support file containing unified log rows.
* Preserve useful existing core trace pull, pagination, and detail behavior by adapting it into the unified logging model, not by keeping a separate runtime log UX.
* Do not expose API keys or unlock PINs in displayed or exported logs; mask them before persistence/export.
* Unified log classification is based on `logger`/component plus optional structured attrs, not a fine-grained mutually exclusive domain enum or rigid fixed context field set.
* Logger names are based on code components / emitting points rather than legacy business prefixes such as `[CORE]`, `[APPS]`, or `[LLM]`.
* Existing legacy prefixes may still be parsed into human-friendly messages or attrs during migration, but they should not define the standard `logger` value.
* Integration is not allowed to happen only in export code; Logs page and export must consume the same combined projection.
* Logs page display uses coarse `App` and `Core` categories; export remains one combined JSONL support file.
* No additional narrowing controls are required for MVP. Startup/bootstrap remains part of `App`; FSM/runtime trace remains part of `Core`; `logger` provides producer-level distinction.
* App-side startup/service logging uses the same App log entry point as other App logs. There is no separate startup log file or startup-specific UI surface.
* `setState(...)` logging must not change user notification/status behavior; it only adds a support-log write.
* Current startup state messages are reusable App log content: `GUIDE_SETTINGS`, `WAIT_INPUT`, `STARTING_CORE`, `STARTING_CORE_ROOT`, `WAIT_WIRELESS_DEBUGGING`, `STOPPING`, `STOP_PAIRING_REQUIRED`, `PAIRING`, `CONNECTING`, `PAIRED`, `RUNNING`, `FAILED`, `RECONNECTING`, and `IDLE`.
* Additional startup support rows must cover internal launch steps that are not represented by `setState(...)`, especially deploy/start/port/app-label/failure-diagnostic steps.

## Acceptance Criteria (evolving)

* [ ] A startup failure during app-list loading leaves inspectable log evidence.
* [ ] Runtime trace entries are visible from the Logs page.
* [ ] App/startup support log entries are visible from the Logs page without waiting for export.
* [ ] Startup and runtime logs can be exported from the Logs page as one unified JSONL support file.
* [ ] Logs page separates `App` and `Core` logs into coarse pages.
* [ ] `App` and `Core` logs are exported together by the shared export action.
* [ ] App source records and Core source records use the same JSON event envelope.
* [ ] Exported rows include logger/component plus attrs so startup/app/task/core records can be inspected after export.
* [ ] Quick Task no longer displays or maintains a separate session/chat log stream.
* [ ] Tasks page contains the Quick Task input and Run button directly, without navigating to a separate Quick Task page.
* [ ] Quick Task/FSM progress entries appear in the unified Logs page.
* [ ] App-side startup/support log entries survive app process restart within the retained recent window.
* [ ] Persistent support-log storage prunes old app-side entries by a fixed maximum count.
* [ ] App-side persistent support-log storage keeps at most the newest 1000 entries.
* [ ] Every existing `appendLog(...)` producer writes a persistent support-log entry without requiring per-call bespoke file I/O.
* [ ] Startup/bootstrap `setState(...)` transitions write persistent App log entries.
* [ ] Installed-app list refresh failures produce a support-log entry visible/exportable from Logs.
* [ ] Core app-list fallback/empty/error cases produce structured trace entries visible/exportable from Logs.
* [ ] Core trace rows include a standard `logger` when adapted for display/export.
* [ ] Exported log output is bounded and masks API keys and unlock PINs.
* [ ] A log emitted by LLM during an FSM run can be found by its component logger without duplicating or nesting the event.
* [ ] Logs page and export share the same app-facing log projection instead of duplicating merge logic.
* [ ] `WirelessAdbBootstrapService.setState(...)` still broadcasts `ACTION_STATUS` with the same extras after the logging change.
* [ ] Existing notification update behavior around startup/state changes is not removed or reordered for logging.
* [ ] Every `setState(...)` transition creates an App log row, except optional consecutive duplicate suppression for identical state/message rows.
* [ ] Core launch via wireless/root writes App log rows for deploy result, starter result, app-label snapshot deploy result, launch attempt, and port readiness.
* [ ] When the core port is not ready after launch, the App logs include bounded remote core log tail and process snapshot rows when those commands are reachable.
* [ ] App-list failure diagnosis can be followed from App logs through startup/app-label deploy/refresh result and from Core logs through `list_apps_*` trace rows.

## Definition of Done (team quality bar)

* Tests added/updated where behavior is changed.
* Lint / typecheck / CI green.
* Docs/notes updated if behavior changes.
* Rollout/rollback considered if risky.

## Out of Scope (explicit)

* Replacing the entire `TraceLogger` implementation is not assumed for MVP.
* External cloud log upload is out of scope unless explicitly added later.
* Durable core/backend trace JSONL persistence is out of scope for MVP.

## Research References

* [`research/current-logging-state.md`](research/current-logging-state.md) - local codebase research and feasible approaches.
* [`research/log-content-inventory.md`](research/log-content-inventory.md) - current reusable log/progress content and required adaptations.

## Research Notes

### Feasible approaches

**Approach A: UI-only merge of in-memory channels**

* Smallest change: display/export existing `logLines` plus trace.
* Risk: loses startup history on app process death and misses remote core log.

**Approach B: Persistent app-owned support log** (recommended)

 * File-backed app log records startup/service/app-list/config events; Logs page also pulls core trace.
 * Better for startup failures before core is reachable.
 * Requires retention and privacy decisions.

**Approach C: Core-first trace unification**

* Push more daemon/app-list facts into core Trace and keep Logs page trace-shaped.
* Does not solve failures before core starts/listens.

## Technical Notes

* Task directory: `.trellis/tasks/06-23-unify-logging-system`.
* Relevant app files:
  * `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainViewModel.kt`
  * `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainActivity.kt`
  * `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/core/TaskRuntimeController.kt`
  * `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/service/WirelessAdbBootstrapService.kt`
* Relevant core files:
  * `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/TraceLogger.java`
  * `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFacade.java`
  * `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/execution/ExecutionEngine.java`
  * `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/system/UiAutomationWrapper.java`
* Relevant specs:
  * `.trellis/spec/backend/logging-guidelines.md`
  * `.trellis/spec/backend/error-handling.md`
  * `.trellis/spec/frontend/state-management.md`
  * `.trellis/spec/frontend/component-guidelines.md`
  * `.trellis/spec/frontend/quality-guidelines.md`
* Concrete App-side logging plan:
  * `.trellis/tasks/06-23-unify-logging-system/research/log-content-inventory.md#concrete-app-side-logging-plan`
