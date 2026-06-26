# semantic locator post-verification

## Goal

Add a post-click verification loop for semantic locator replay so the engine can detect when a click did not actually take effect, keep the replay on the same route step, and retry with the prior locator/context instead of blindly advancing to the next step.

## What I already know

* The current task-map replay path resolves a TAP step, performs the tap, waits for UI stability, and then immediately marks the step as `ok` if the tap command itself succeeded.
* `SemanticVisionStepResolver` already receives `step`, `historyText`, `locatorFailureReason`, and screenshot bytes, so the prompt already has enough room to compare current vs prior context.
* `CortexExecutionHistory` already carries recent turn history and is injected into the semantic visual resolver prompt, but it is only used as conversational history, not as a post-click success gate.
* The route replay flow emits `fsm_script_act_task_map_step_start` / `fsm_script_act_task_map_step_end`, but there is no explicit “click verified” result today.
* `replayTaskMapSegmentScript` advances `index += 1` immediately after a step returns `ok`; if a step returns `false`, it stays on the same index, optionally tries popup recovery, and only then falls back.
* `executeTaskMapRoutingStep` currently treats a TAP as successful when the tap command returns success, without checking whether the page transition actually happened.
* The user’s proposed failure pattern is: step N click is executed, step N+1 begins, and the page still shows the old target while the next target is absent. In that case the engine should conclude step N did not land, retry step N, and not advance the route index.
* MVP scope is semantic locator TAP only; XML locator replay should not change in this task.
* The verifier must use the last TAP locator, not merely the immediately previous route step, because the previous step may be a non-TAP action.
* The verifier input should include current page context, the last TAP locator, the current step locator, and the previous step expected result.
* The verifier output should be JSON so the engine can parse the decision and command without a second custom syntax layer.
* The verifier JSON contract is one object containing `observing`, `judging_prev`, `judge_prev_result`, `thinking`, `decision`, `command`, and `reason`.
* `command` coordinates are normalized integers in a 1000x1000 logical plane, matching the semantic visual resolver and VISION_ACT coordinate contract.
* The verifier runs at the boundary between steps: on the next step's screenshot it decides whether to retry the last TAP, execute the current step, or defer.
* The verifier prompt must include explicit reasoning guidance. It must not ask the model to jump directly to `decision`.

## Assumptions (temporary)

* This task is backend-only unless later evidence shows a user-facing trace or settings change is needed.
* MVP should keep the current task-map replay contract stable: a failed verification must prevent step advancement, not invent a new route state model.
* The mechanism should be local to route replay and should not change unrelated VISION_ACT behavior.

## Open Questions

* None.

## Backend Behavior (evolving)

* Replay currently resolves a TAP step through XML locator if present, otherwise semantic visual resolution.
* After a TAP, the engine should capture a new observation and verify whether the intended transition happened before the route index advances.
* If verification fails, the engine should retry the last TAP step with the same route index and the same step context.
* The verification prompt/contract needs current page context, last TAP locator, current step locator, and previous step expected result so the model can tell “old page still present” from “new page reached”.
* The initial verifier is model-first; deterministic prechecks can wait unless the model path proves noisy.
* The verifier prompt must guide the model to write this analysis into JSON fields before emitting the final decision:
  1. Inspect whether the previous TAP appears to have completed, using the previous expected result and whether the previous TAP locator is still visible.
  2. Inspect whether the current step locator is visible and actionable on the current page.
  3. Decide whether the safest next action is to retry the previous TAP, execute the current step, or defer because the page is still loading or neither locator is reliable.
  4. Emit one JSON object that contains the analysis fields and the final decision/command.
* The loop already has a natural “do not advance index on false” branch, so the new logic should plug into the existing `ok/false` boundary instead of inventing a separate replay controller.
* Failure handling should remain bounded and deterministic enough to avoid infinite loops.

## Frontend/User-Facing Interface (evolving)

* No direct UI change is expected for MVP.
* If a trace/result is exposed later, it should make verification failure explicit rather than looking like a normal step success.

## Decision Log

* Initial task definition → add post-click verification for semantic locator replay → scope is step-local retry instead of blind advancement.
* Scope preference → only cover semantic locator TAP in MVP → keep XML locator replay unchanged for now.
* Verification strategy → model-first → use contextual model judgment before rule-based fallback.
* History source → use the last TAP locator rather than the immediately previous route step.
* Output format → JSON → keep parser implementation aligned with existing JSON-heavy prompt contracts.
* Result shape → JSON contains `observing`, `judging_prev`, `judge_prev_result`, `thinking`, `decision`, `command`, and `reason`; `decision` is `previous`, `current`, or `defer`; `command` is a `TAP x y` string for previous/current and empty for defer.
* Retry budget → one verification retry, then fall back to the existing failure path.
* Verification timing → boundary between steps, using the next step's observation to choose between last TAP retry and current step execution.
* Prompt contract → include explicit reasoning guidance in the JSON output requirement, so the model evaluates previous TAP completion and current locator visibility before choosing.

## Requirements (evolving)

* Add a post-click verification stage after semantic locator tap execution.
* Preserve the current route step when verification fails.
* Reuse prior step context in the verification prompt or decision logic.
* Emit traceable failure/success signals for verification.
* Limit MVP to semantic locator TAP replay.
* Leave XML locator replay unchanged in MVP.
* Feed the verifier with current page context, last TAP locator, current step locator, and previous step expected result.
* Parse verifier output as JSON with explicit reasoning fields, decision field, and command field.
* Preserve an explicit loading/defer branch if the page is still transitioning and neither locator is visible.
* Treat verifier command coordinates as normalized 0-1000 coordinates, then map them to device pixels immediately before execution.
* Retry the previous tap once before falling back to the existing failure path.
* Run the verifier before committing to the current semantic TAP step.
* Add explicit prompt guidance that forces the model to analyze previous TAP completion, current locator visibility, and retry-vs-current-vs-defer inside the same JSON output.

## Acceptance Criteria (evolving)

* [ ] A semantic locator tap that does not change the page no longer advances to the next route step.
* [ ] The same step is retried when verification says the tap did not take effect.
* [ ] The verifier can compare current and previous step context.
* [ ] Existing successful semantic locator replay still completes normally.
* [ ] XML locator replay behavior remains unchanged in MVP.
* [ ] The verifier receives the last TAP locator even when the previous route step is not TAP.
* [ ] Verifier output is machine-parseable JSON.
* [ ] The verifier supports `previous`, `current`, and `defer` decisions.
* [ ] A failed verification retries once, then follows the existing failure path.
* [ ] Verifier prompt includes explicit reasoning guidance inside the JSON-only output contract.

## Definition of Done (team quality bar)

* Tests added/updated (unit/integration where appropriate)
* Lint / typecheck / CI green
* Docs/notes updated if behavior changes
* Rollout/rollback considered if risky

## Out of Scope (explicit)

* New user-facing settings for verification sensitivity.
* Reworking unrelated route planning or VISION_ACT behavior.
* XML locator replay verification in MVP.
* Changing portable route serialization unless verification needs a new persisted field.

## Technical Notes

* Likely touch points: `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFsmEngine.java`
* Likely touch points: `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/SemanticVisionStepResolver.java`
* Likely touch points: `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/StepVisualResolveRequest.java`
* Existing prompt/history plumbing already exists in `CortexExecutionHistory` and `renderExecutionHistoryForResolver`.
* Route replay currently treats tap execution success as step success; verification will need to sit after the tap and before index advancement.
