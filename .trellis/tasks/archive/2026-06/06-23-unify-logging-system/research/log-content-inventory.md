# Log Content Inventory

## Purpose

Inventory existing messages, states, and trace events that should be migrated into the unified App/Core logs design. "Reuse" here means reusing existing useful content/meaning, not keeping old split surfaces such as Quick Task chat logs or legacy `[CORE]` prefixes.

## Reusable App-Side Content

## Concrete App-Side Logging Plan

### App log entry shape

All App-side producers write through one App log entry point. The entry point is shared by `MainViewModel`, `MapOperationsController`, `TaskRuntimeController`, and `WirelessAdbBootstrapService`; it is not a separate startup log.

Required row shape:

```json
{"ts":"2026-06-23T10:11:12.123+0800","level":"info","logger":"WirelessAdbBootstrapService","message":"Starting core via saved wireless endpoint...","attrs":{"state":"STARTING_CORE","running":"true","mode":"wireless"}}
```

Rules:

* `logger` is the code producer, for example `MainViewModel`, `MapOperationsController`, `TaskRuntimeController`, or `WirelessAdbBootstrapService`.
* `message` is the short human-readable text already useful for support.
* `attrs` holds local facts already available at the emitting point. Do not add run refs/session refs or a rigid context object.
* Redaction runs before display, persistence, and export. API keys, authorization tokens, unlock PINs, and pairing/PIN-like codes must be masked.
* App retention is newest 1000 entries.
* Existing notification, broadcast, status, and toast/user-message behavior may continue. The requirement is that the same event also goes into the App log.

### App log entry point

Add one app-side logger/store, tentatively named `AppLogStore` or `AppLogger`.

Expected behavior:

* `write(context, level, logger, message, attrs)` appends one sanitized row to the in-process App log list and the bounded App log file.
* `read(context)` loads the same sanitized recent rows for Logs page startup.
* `entries` / `StateFlow` gives `MainViewModel` one source to show in the App logs page.
* Pruning to 1000 entries happens in the store, not at every caller.
* `MainViewModel.appendLog(...)` becomes a compatibility wrapper that writes into this same store and then keeps `_logLines` updated only if old code still needs the flow.

This means there is one App log source. It is acceptable if a caller also updates notification/status UI after writing the log.

### Current startup signals that already exist

These are useful today, but they are not all real logs yet:

* `MainViewModel.startServerWithNative` writes `Native start requested on port ...` through `appendLog`.
* `MainViewModel.startServerWithRootDirect` writes `Root-direct start requested on port ...` through `appendLog`.
* `MainViewModel.sendWirelessBootstrapAction` writes action-start failure through `appendLog`.
* `MainViewModel.registerWirelessBootstrapReceiver` writes receiver registration failure through `appendLog`.
* `WirelessAdbBootstrapService.setState(...)` broadcasts latest startup state/message only; it does not persist history.
* `WirelessAdbBootstrapService.relaunchCoreViaSavedEndpoint()` builds endpoint candidates and returns connect/launch failures in a detail string.
* `WirelessAdbBootstrapService.launchCoreWithVerification()` and `launchCoreWithRootVerification()` already know deploy result, starter deploy result, app labels deploy result, starter launch result, port readiness, remote process snapshot, and remote `/data/local/tmp/lxb-core.log` tail, but most of that only survives inside return details.
* Foreground notification updates are user status, not the support log. They should remain unchanged.

Current `setState` states/messages to reuse as App log rows:

* `GUIDE_SETTINGS`: guide user to Developer Options / Wireless debugging.
* `WAIT_INPUT`: waiting for pairing code, invalid input, endpoint not detected, endpoint detected, NSD discovery unavailable.
* `STARTING_CORE`: starting core via saved wireless endpoint.
* `STARTING_CORE_ROOT`: starting core via root.
* `WAIT_WIRELESS_DEBUGGING`: native start failed, retrying/waiting for Wireless debugging.
* `STOPPING`: stopping core.
* `STOP_PAIRING_REQUIRED`: user must pair before stop.
* `PAIRING`: reachability check and TLS provider prepared.
* `CONNECTING`: paired and connecting for stop flow.
* `PAIRED`: pairing succeeded.
* `RUNNING`: native/root/retry/watchdog recovery succeeded.
* `FAILED`: root, pairing, wireless bootstrap, or retry failure.
* `RECONNECTING`: watchdog detected unreachable core and is relaunching.
* `IDLE`: bootstrap stopped.

### Required startup logs to add

Add these App log writes through the same App logger/store:

| Placement | Level | Logger | Message | attrs |
| --- | --- | --- | --- | --- |
| `onStartCommand` | info | `WirelessAdbBootstrapService` | `Wireless bootstrap action received` | `action`, `startId`, `running`, `state` |
| `startBootstrap` / `ensureForegroundRunning` | info/warn | `WirelessAdbBootstrapService` | `Foreground bootstrap service started` or `Foreground bootstrap service start failed` | `action`, `sdk`, `serviceType` |
| `setState` | info/warn/error | `WirelessAdbBootstrapService` | existing `message` | `state`, `previousState`, `running` |
| `startDiscovery` | info/warn | `WirelessAdbBootstrapService` | `Wireless ADB discovery started/failed/unavailable` | `serviceType`, `errorCode` |
| endpoint resolved | info | `WirelessAdbBootstrapService` | `Wireless ADB endpoint detected` | `kind=pairing/connect`, `host`, `port` |
| `submitPairing` | info/warn/error | `WirelessAdbBootstrapService` | pairing input/check/pair result | `pairCodeLength`, `pairingHost`, `pairingPort`, `connectHost`, `connectPort` |
| `relaunchCoreViaSavedEndpoint` before loop | info/warn | `WirelessAdbBootstrapService` | `Core relaunch endpoint candidates prepared` | `candidateCount`, `savedEndpoint`, `discoveredConnect`, `discoveredPairing` |
| each endpoint attempt | info/warn | `WirelessAdbBootstrapService` | `Wireless ADB endpoint connect result` | `host`, `port`, `direct`, `auto`, `connected` |
| launch verification start | info | `WirelessAdbBootstrapService` | `Core launch verification started` | `mode=wireless/root`, `port` |
| starter ABI resolution | info/error | `WirelessAdbBootstrapService` | `Starter asset resolved` or `Starter asset unsupported` | `abi`, `assetName`, `supportedAbis` |
| core jar deploy | info/error | `WirelessAdbBootstrapService` | `Core jar deploy result` | `mode`, `remotePath`, `ok`, `detail` |
| starter deploy | info/error | `WirelessAdbBootstrapService` | `Starter binary deploy result` | `mode`, `abi`, `assetName`, `remotePath`, `ok`, `detail` |
| app labels deploy | info/warn | `WirelessAdbBootstrapService` | `App labels snapshot deploy result` | `mode`, `remotePath`, `ok`, `detail` |
| each launch attempt | info/warn/error | `WirelessAdbBootstrapService` | `Starter launch attempt result` | `mode`, `attempt`, `port`, `starterOk`, `detail` |
| port readiness checks | info/warn | `WirelessAdbBootstrapService` | `Core port readiness result` | `mode`, `attempt`, `port`, `ready`, `waitMs` |
| port not ready | warn/error | `WirelessAdbBootstrapService` | `Core port not ready after starter launch` | `mode`, `attempt`, `port` |
| failure diagnostics | warn | `WirelessAdbBootstrapService` | `Remote core log tail captured` | `mode`, `attempt`, `path`, `tail` capped |
| failure diagnostics | warn | `WirelessAdbBootstrapService` | `Remote process snapshot captured` | `mode`, `attempt`, `processSnapshot` capped |
| stop core flow | info/warn | `WirelessAdbBootstrapService` | `Core stop result` | `mode`, `endpoint`, `localReachableBefore`, `closed`, `detail` |
| watchdog | warn/info/error | `WirelessAdbBootstrapService` | `Core watchdog relaunch started/succeeded/failed` | `port`, `detail` |

`setState` implementation constraint:

* Keep `currentState = state`, `currentMessage = message`, `Intent(ACTION_STATUS)`, extras, and `sendBroadcast(broadcast)` behavior unchanged.
* Add one App log write inside or immediately next to `setState`.
* Do not remove `updateNotification()` calls or change notification timing.
* To avoid noise, the logger may skip only consecutive duplicate `(state, message)` App log rows. Skipping duplicate log rows must not skip broadcasts or notifications.

### Startup/app-list diagnosis path

For the specific user complaint "app list cannot be loaded", the final logs should show:

1. App page: user requested native/root start.
2. App page: bootstrap action received and startup state transitions.
3. App page: endpoint candidates and connect results.
4. App page: core jar/starter deploy result.
5. App page: app labels snapshot deploy result to `/data/local/tmp/lxb-app-labels.tsv`.
6. App page: starter launch and local port readiness result.
7. App page: failure tail/process snapshot if the daemon did not become reachable.
8. App page: installed app snapshot refresh start/result/failure from `MainViewModel.refreshInstalledAppSnapshotOnDevice`.
9. Core page: `list_apps_*` trace events for list-app command, external labels result, fallback, empty result, and error.

This gives support enough evidence to tell whether the failure is startup, ADB endpoint, app labels deploy, daemon readiness, or core app-list execution.

### `MainViewModel.appendLog(...)`

Current state:

* `MainViewModel` owns `_logLines` / `logLines`, an in-memory list capped at 500 rows.
* Code search found 44 `appendLog(...)` call sites across `MainViewModel`, `MapOperationsController`, and `TaskRuntimeController`.
* Current Logs page does not consume `logLines`.

Reusable content:

* Keep the existing message text. It already covers meaningful user/support operations:
  * core start requests and core config/touch mode results
  * wireless bootstrap action failures and receiver registration failures
  * FSM submission/cancel/task-list/task-map operation results
  * trace export status
  * installed-app snapshot refresh result
  * template and workflow CRUD/run results
  * LLM config sync and LLM connectivity test result
  * app update check result
  * map sync/pull/status results from `MapOperationsController`
  * trace push listener and runtime indicator failures from `TaskRuntimeController`

Required adaptation:

* Convert each call into a standard app log event:
  * `ts`: current time
  * `level`: infer from message or caller (`error/warn/info`), with failures/errors as at least `warn` or `error`
  * `logger`: emitting code component / function area, not legacy prefix
  * `message`: existing human-readable text with legacy `[PREFIX]` removed or preserved only as detail if useful
  * `attrs`: local fields naturally available at the call site, such as port, path, result status, count, selected mode
* Persist app-side events with newest-1000 retention.

Do not keep as the standard design:

* Existing prefixes such as `[CORE]`, `[APPS]`, `[LLM]`, `[FSM]`, `[MAP]`, `[TRACE]`, `[WORKFLOW]`, `[TEMPLATE]`. They are useful migration clues but are not the standard `logger` value.

### `appendSystemMessage(...)` / Quick Task chat

Current state:

* `appendSystemMessage(...)` writes localized chat/session bubbles.
* Quick Task currently displays `chatMessages`.
* `TaskRuntimeController` maps selected core trace push events into `appendSystemMessage(...)`.

Reusable content:

* User-facing message strings can inform Core-page row summaries, but the chat/session stream itself should not be reused as a log source.

Required adaptation:

* Quick Task progress should come from core trace rows in the Core logs page.
* Quick Task UI should not maintain or display a separate chat/session log.

### `WirelessAdbBootstrapService.setState(...)`

Current state:

* `WirelessAdbBootstrapService` is an Android service in the App module, so it belongs to the App log source.
* Startup/bootstrap flow uses `setState(state, message)` and broadcasts only the latest state to the app.
* Important states/messages exist for guide/waiting, native/root start, pairing, connecting, reconnecting, stopping, running, failed, and retry cases.
* On port-not-ready failures, the service already captures remote `/data/local/tmp/lxb-core.log` tail and process list into failure detail.

Reusable content:

* Every `setState(state, message)` transition is directly useful as an App log row.
* Failure details in `RelaunchResult.detail` are useful support evidence, especially starter deploy/start failure, port readiness failure, `ps`, and remote core log tail.

Required adaptation:

* Add durable app log writes at `setState(...)` or the state transition boundary through the same App log entry/store used by `MainViewModel`.
* Use `logger = "WirelessAdbBootstrapService"`.
* Suggested attrs: `state`, `running`, and any locally available `detail` fields. Avoid adding a separate startup category/page.

## Reusable Core-Side Content

### `TraceLogger`

Current state:

* `CortexFacade` owns `TraceLogger(2000)`.
* `TraceLogger.event(event, fields)` writes JSONL records into an in-memory ring and supports pull pagination.
* Existing row shape is roughly:

```json
{"ts":"...","event":"fsm_state_enter","task_id":"...","state":"VISION_ACT", "...":"..."}
```

* There are about 205 `trace.event(...)` call sites and a large existing event vocabulary covering:
  * FSM lifecycle and state transitions
  * app resolve / app enter
  * LLM prompts and responses
  * vision screenshot / parse / retry / settle
  * execution actions: tap, swipe, input, wait, back
  * task route / task map lookup, replay, recovery, save
  * unlock flow
  * notification workflow triggers
  * schedule/task/template/workflow API errors
  * locator resolution

Reusable content:

* Core trace event names and fields are the strongest existing reusable log content.
* `CoreApiParser.parseTraceEntry(...)` already parses raw JSONL into `TraceEntry` with `summary`, `detail`, `isError`, `meta`, and `fields`.
* Logs page can reuse much of the existing trace rendering and pagination behavior for the Core page.

Required adaptation:

* Expose or adapt each trace row to the standard event envelope:
  * `ts`: existing `ts`
  * `level`: infer from event name / error fields
  * `logger`: emitting code component / emitting point
  * `message`: current parser summary or a direct event-derived message
  * `attrs`: current trace fields, including original `event`
* Core trace remains in memory only for MVP; do not add durable core JSONL persistence.

Important gap:

* Current core trace rows do not include `logger`. They include `event` but not emitting component.
* Implementation must decide how to populate logger without adding context plumbing:
  * preferred option to evaluate: infer caller class inside `TraceLogger.event(...)` from the Java stack trace, so existing call sites do not need to pass logger manually;
  * alternative: add overloads/wrappers and migrate call sites, which is more explicit but touches many files.

### Core App-List Diagnostics

Current state:

* `ExecutionEngine.handleListApps(...)` prints `LIST_APPS filter=...` and `UiAutomation not available` to console.
* `UiAutomationWrapper.listApps(...)` uses APK-provided labels snapshot first, then package-only fallback.
* Current app-list diagnostics are `System.out/err`, not `TraceLogger` events.

Reusable content:

* Existing messages and decision points are useful:
  * `LIST_APPS filter`
  * `UiAutomation not available`
  * external label snapshot unavailable
  * package-only fallback used
  * fallback empty returning `[]`
  * `listAppsWithExternalLabels` total/labeled/path
  * external labels load failure
  * package-only list failure

Required adaptation:

* Add structured core trace events around `CMD_LIST_APPS` / `UiAutomationWrapper.listApps` behavior.
* Suggested events to add:
  * `list_apps_start`
  * `list_apps_external_labels_result`
  * `list_apps_package_fallback`
  * `list_apps_empty`
  * `list_apps_error`
* These events belong to Core logs and should use the same standard envelope when displayed/exported.

## Existing Content That Should Not Become Logs

* Quick Task chat/session messages as a standalone log source.
* Extra UI narrowing controls/chips for MVP.
* Legacy business prefixes as standard logger names.
* Export-only merging logic.

## Implementation Risks To Resolve Before Coding

* Logger generation:
  * App call sites can usually know the component directly.
  * Core trace currently lacks logger; caller inference or explicit logger overload is required.
* Severity:
  * App strings are free text; severity must be inferred conservatively or specified by helper methods.
  * Core severity can be inferred from event names and error fields.
* Redaction:
  * API keys and unlock PINs must be masked before persistence/export.
  * Existing unlock trace events may contain PIN-related details and must be audited.
* Export shape:
  * App and Core are separate physical sources, but export must write one JSONL file using the same envelope.
* UI model:
  * App page reads persistent app log events.
  * Core page reads current core trace ring adapted to the envelope.
  * Both pages and export must use the same app-facing projection/adapters, not duplicate conversion rules.
