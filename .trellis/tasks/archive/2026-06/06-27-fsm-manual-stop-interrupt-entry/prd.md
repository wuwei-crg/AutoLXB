# brainstorm: fsm manual stop interrupt entry

## Goal

When the frontend manually stops a running task, the device-side Cortex FSM
should stop promptly and predictably. The current issue is that SCRIPT_ACT does
not appear to have an interrupt entry, so the cancel request can be delayed
until the FSM returns to an outer loop boundary.

## What I already know

* The Android frontend already exposes a manual stop action in the Tasks page.
* `MainViewModel.cancelCurrentTaskOnDevice()` sends
  `CMD_CORTEX_FSM_CANCEL` to `lxb-core`.
* `CortexFacade.handleCortexFsmCancel(...)` calls
  `taskManager.requestCancel()` and emits `fsm_cancel_requested`.
* `CortexTaskManager` already keeps a global `cancelRequested` flag and passes
  a `CancellationChecker` into `CortexFsmEngine.run(...)`.
* `CortexFsmEngine` only checks cancellation at outer FSM loop boundaries
  before entering major states, not inside long-running SCRIPT_ACT internals.
* `runScriptActState(...)` and its helpers contain multiple retry loops,
  settle waits, and recovery flows that can run for seconds without polling
  cancellation.
* The frontend Trace/UI path already understands both `fsm_cancel_requested`
  and `fsm_task_cancelled`.

## Assumptions (temporary)

* The bug is not in the frontend stop button or transport command path.
* The main behavior gap is cancellation granularity inside backend FSM
  execution, especially SCRIPT_ACT replay / settle / recovery helpers.
* A more uniform fix may be preferable to a SCRIPT_ACT-only patch if it can be
  introduced without excessive churn.

## Open Questions

* Should cancellation be added only to SCRIPT_ACT hotspots first, or promoted
  to a unified "safe interrupt point" mechanism across the whole FSM?
* Should cancellation during replay/report as `FAIL` with reason
  `cancelled_by_user`, or should specific states surface a dedicated cancelled
  transition/result before outer loop exit?
* Do we want interruptibility only between device actions, or also during long
  settle/retry sleeps by replacing `sleepQuiet(...)` with a cancellation-aware
  wait helper?

## Backend Behavior (evolving)

* Frontend manual stop should remain a best-effort async cancel request.
* Backend should preserve the existing `CMD_CORTEX_FSM_CANCEL` contract and
  continue returning `{"ok":true,"reason":"cancel_requested"}` immediately.
* The running FSM should observe cancellation inside long-running execution
  paths instead of only at outer state-loop boundaries.
* Trace behavior should remain compatible with existing app-side
  `TraceEventMapper` handling for `fsm_cancel_requested` and
  `fsm_task_cancelled`.
* Current outer-loop polling exists in `CortexFsmEngine.run(...)` before major
  states and sub-task loop iterations, but inner helpers do not share a common
  cancellation utility.
* The biggest latency hotspots currently appear in:
  - `runScriptActState(...) -> replayTaskMapSegmentScript(...)`
  - `executeTaskMapRoutingStep(...)`
  - semantic locator / verifier retry loops
  - `waitForUiStableByDumpActions(...)`
  - `execActionCommand(...): WAIT/SWIPE/TAP/BACK/INPUT` follow-up waits
  - `runVisionActState(...)` retry loop (same architectural issue, even if the
    current bug report is focused on SCRIPT_ACT)

## Frontend/User-Facing Interface (evolving)

* Existing Tasks page stop button already routes to the cancel command.
* Existing runtime indicator already shows a "CANCELLING" phase after request
  submission and stops on `fsm_task_cancelled`.
* No confirmed UI gap yet; current evidence points to backend interrupt
  latency rather than missing frontend affordance.
* Current UI already models cancellation as "stop at next safe point", so a
  backend improvement can stay within the existing user-facing copy unless we
  want stronger guarantees.

## Decision Log

* "Where is the current cancel chain?" -> Frontend stop button -> ViewModel
  cancel command -> `CMD_CORTEX_FSM_CANCEL` -> `CortexFacade` ->
  `CortexTaskManager.cancelRequested` -> outer-loop `CancellationChecker`
  polling -> Focus current work on FSM/backend interrupt granularity.
* "Is there already test/document contract for cancel events?" -> app trace
  mapping exists, but repo search found no focused JVM/unit tests for manual FSM
  cancellation and no trace doc section covering `fsm_cancel_requested` /
  `fsm_task_cancelled` yet -> implementation should likely add tests and maybe
  trace docs.
* "Which direction should we take?" -> User selected Approach B: unified FSM
  cancellation helpers instead of a SCRIPT_ACT-only patch.

## Requirements (evolving)

* Investigate the current manual-stop chain end to end.
* Identify exact backend code locations where cancellation is currently polled
  and where it is missing.
* Propose concrete implementation approaches before coding.
* Prefer a design that improves reuse and avoids scattering one-off
  `if (cancelled)` checks everywhere when feasible.

## Acceptance Criteria (evolving)

* [ ] Current frontend -> backend cancel path is documented with concrete file
      locations.
* [ ] Missing interrupt points inside FSM/SCRIPT_ACT are identified.
* [ ] At least 2 feasible implementation approaches are compared.
* [ ] A chosen direction is agreed before implementation starts.

## Definition of Done (team quality bar)

* Tests added/updated (unit/integration where appropriate)
* Lint / typecheck / CI green
* Docs/notes updated if behavior changes
* Rollout/rollback considered if risky

## Out of Scope (explicit)

* Changing the transport command id or redesigning the entire app-side Tasks UI
  before confirming backend needs.
* Broad workflow/template cancellation redesign unless it is required by the
  FSM fix.

## Technical Notes

* Frontend stop action:
  `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainActivity.kt`
  `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainViewModel.kt`
* Frontend runtime indicator / trace mapping:
  `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/core/TaskRuntimeController.kt`
  `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/core/TraceEventMapper.kt`
* Backend command entry:
  `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFacade.java`
  `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/dispatcher/CommandDispatcher.java`
* Backend task manager + FSM:
  `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexTaskManager.java`
  `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFsmEngine.java`
* Relevant Trace / docs references:
  `docs/trace/task-flow.md`
  `android/LXB-Ignition/README.md`
* Current high-confidence code points:
  - Frontend stop button:
    `MainActivity.kt` -> `TaskRuntimeStatusCard(... onStop = { viewModel.cancelCurrentTaskOnDevice() })`
  - Frontend cancel command:
    `MainViewModel.kt` -> `cancelCurrentTaskOnDevice()`
  - Backend cancel entry:
    `CortexFacade.java` -> `handleCortexFsmCancel(...)`
  - Task-manager cancellation flag:
    `CortexTaskManager.java` -> `cancelRequested`, `requestCancel()`, worker
    `CancellationChecker`
  - Existing outer-loop FSM polling:
    `CortexFsmEngine.java` around `run(...)` outer state loops
  - SCRIPT_ACT entry:
    `CortexFsmEngine.java` -> `runScriptActState(...)`
  - Main SCRIPT_ACT replay loop:
    `CortexFsmEngine.java` -> `replayTaskMapSegmentScript(...)`
  - Step execution:
    `CortexFsmEngine.java` -> `executeTaskMapRoutingStep(...)`
  - Long waits without cancel awareness:
    `sleepQuiet(...)`, `waitForUiStableByDumpActions(...)`,
    `execActionCommand(...)` WAIT branch

## Feasible Approaches

### Approach A: Patch SCRIPT_ACT only

Add cancellation checks inside `runScriptActState(...)`,
`replayTaskMapSegmentScript(...)`, and the major SCRIPT_ACT retry/wait helpers.

Pros:
* Smallest change set
* Directly addresses the reported bug

Cons:
* Leaves the same architectural problem in VISION_ACT and shared wait helpers
* Easy to miss a loop or reintroduce the bug later

### Approach B: Unified FSM cancellation helpers (Recommended)

Keep the existing `CancellationChecker`, but store it in FSM runtime context or
pass it through a small shared helper layer:
* `isCancellationRequested(ctx)` / `throwIfCancelled(ctx)`
* `sleepCancelable(ctx, ms)`
* optional `waitForUiStable...` / retry loops call the shared helper

Then apply it first to shared helpers plus SCRIPT_ACT, and optionally to
VISION_ACT retry loops in the same pass.

Pros:
* Solves the current bug and gives the FSM a consistent interrupt model
* Reuses the same logic in SCRIPT_ACT, VISION_ACT, and settle/wait code
* Easier to test and reason about "safe points"

Cons:
* Slightly broader refactor than a local patch
* Needs care to preserve current trace/result contracts

### Selected Direction

Adopt Approach B.

Current preferred implementation shape:

* Keep `CMD_CORTEX_FSM_CANCEL` and `CortexTaskManager.cancelRequested` as the
  existing external contract.
* Extend `CortexFsmEngine.Context` to carry cancellation access, or introduce a
  single engine-level helper path that all long-running helpers can call.
* Add shared helpers such as:
  - `isCancellationRequested(ctx)`
  - `markCancelledAndTrace(ctx)`
  - `sleepCancelable(ctx, ms)`
  - possibly `waitForUiStableCancelable(...)`
* Use those helpers in shared wait/retry points first, instead of adding
  unrelated ad-hoc checks everywhere.
* Preserve current task result contract:
  - FSM emits `fsm_task_cancelled`
  - task manager still maps `reason contains cancelled_by_user` to
    `TaskState.CANCELLED`
* Do not rely on thread interrupt as the primary mechanism in this phase,
  because the current code swallows `InterruptedException` broadly.

### Scope Notes

* Immediate, high-confidence scope:
  - outer/shared wait helpers
  - SCRIPT_ACT replay loops
  - action WAIT / settle sleeps
* Medium-confidence same-pattern scope:
  - VISION_ACT retry and settle helpers
* Non-goal for the first pass unless explicitly expanded:
  - forcibly aborting an in-flight `HttpURLConnection` LLM request mid-call
* Rationale:
  - shared sleep/wait/retry points are enough to remove the current "stuck in
    SCRIPT_ACT until outer loop returns" behavior
  - active LLM/network abort would need a larger transport-level redesign or
    interrupt-aware connection handling

### Approach C: Thread interrupt driven cancellation

When cancel is requested, also interrupt the worker thread and rely on sleep /
blocking points to wake early.

Pros:
* Can reduce sleep latency quickly

Cons:
* Current code swallows `InterruptedException` in many places
* More fragile global threading semantics
* Still needs explicit policy for non-sleep loops, so it is not sufficient
  alone
