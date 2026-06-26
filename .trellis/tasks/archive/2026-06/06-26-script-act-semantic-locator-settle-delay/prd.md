# brainstorm: script-act semantic locator settle delay

## Goal

Reduce wasted semantic-locator model analysis in `SCRIPT_ACT` by ensuring the page has enough time to settle before screenshot-driven semantic targeting starts.

## What I already know

* The issue lives in `android/LXB-Ignition/lxb-core`, inside the `SCRIPT_ACT` task-map replay path.
* In `replayTaskMapSegmentScript(...)`, a successful step currently does `sleepQuiet(400L)` before moving to the next step.
* The next step may enter screenshot-driven semantic analysis through either:
* `ensureSemanticTapMaterialized(...)`
* `resolveTaskMapTapPointByVisual(...)`
* Both of those paths capture a screenshot immediately before sending it to the model.
* TAP execution is not completely unguarded today:
* `execTapAtPoint(...)` already calls `waitForUiStableByDumpActions(ctx, "TASK_MAP_TAP")`
* `execActionCommand(...)` already calls the same settle logic for TAP / SWIPE / INPUT / BACK
* There is already a more conservative settle strategy in the same file:
* `UI_SETTLE_PRE_WAIT_MS = 1000L`
* `UI_SETTLE_SAMPLE_MS = 500L`
* `UI_SETTLE_REQUIRED_HITS = 2`
* `UI_SETTLE_TIMEOUT_MS = 2500L`
* The reported pain point is that semantic locator still begins too early after the current `400ms` gap, so the screenshot can be taken before the target page is visually ready.
* A related archived task (`06-25-script-act-semantic-locator-settle-strategy`) explicitly noted `per-step success sleep: 400ms` as unchanged at that time.

## Assumptions (temporary)

* This is a backend runtime timing fix in `android/LXB-Ignition/lxb-core`.
* No product UI change is required unless we later decide this delay should become configurable.
* The bug is primarily about screenshot timing before semantic-model calls, not about ordinary non-visual route steps.

## Open Questions

* None blocking. The fix scope is now chosen.

## Backend Behavior (evolving)

* `SCRIPT_ACT` should avoid sending screenshots to semantic locator analysis while the destination page is still rendering.
* Add a dedicated pre-screenshot settle delay for semantic locator entry points only.
* The settle delay should be `2000ms`.
* The fix should preserve existing task-map replay semantics, popup recovery, and `VISION_ACT` fallback behavior.
* The fix should keep traceability clear enough that we can confirm when extra settle time was applied.

## Frontend/User-Facing Interface (evolving)

* Backend-only change for now.
* No user-facing settings are planned unless targeted delay proves insufficient and we later need runtime tuning.

## Decision Log

* Initial diagnosis → the risky transition is from a successful replay step into screenshot-driven semantic targeting → likely fix scope should be centered on semantic analysis entry points, not blindly on every replay step.
* Scope decision → apply a dedicated `2s` settle delay only before semantic screenshot analysis → keep the existing `400ms` replay-loop gap unchanged.

## Requirements (evolving)

* Add a dedicated `2000ms` pre-analysis settle time before semantic locator screenshots are captured.
* Limit the extra delay to semantic screenshot entry points instead of slowing every successful `SCRIPT_ACT` step.
* Preserve current replay, recovery, and fallback contracts unless explicitly decided otherwise.

## Acceptance Criteria (evolving)

* [ ] When the next `SCRIPT_ACT` step requires semantic screenshot analysis, the runtime waits an extra `2000ms` before taking the screenshot.
* [ ] The fix does not break existing xml-locator replay, popup recovery, or `VISION_ACT` fallback behavior.
* [ ] Trace or logs make it possible to verify when the extra semantic-locator settle path was used.
* [ ] Tests cover the chosen delay strategy at the right seam.

## Definition of Done (team quality bar)

* Tests added/updated (unit/integration where appropriate)
* Lint / typecheck / CI green
* Docs/notes updated if behavior changes
* Rollout/rollback considered if risky

## Out of Scope (explicit)

* Reworking the full `SCRIPT_ACT` / `VISION_ACT` architecture
* Broad UI-settle algorithm redesign for all execution phases
* Frontend settings or UI work unless the chosen fix requires exposure

## Technical Notes

* Relevant files inspected:
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFsmEngine.java`
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/SemanticVisionStepResolver.java`
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/SemanticStepMaterializer.java`
* Relevant code points:
* `replayTaskMapSegmentScript(...)` success gap: `sleepQuiet(400L)`
* `ensureSemanticTapMaterialized(...)` captures screenshot before model adaptation
* `resolveTaskMapTapPointByVisual(...)` captures screenshot before semantic visual resolve
* Existing generic settle helper:
* `waitForUiStableByDumpActions(...)`
* Chosen scope:
* extra delay only for semantic screenshot entry points
* fixed delay: `2000ms`
* Prior related task:
* `.trellis/tasks/archive/2026-06/06-25-script-act-semantic-locator-settle-strategy/prd.md`
* Relevant spec entry:
* `.trellis/spec/backend/index.md`
