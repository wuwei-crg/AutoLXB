# brainstorm: script-act xml locator / semantic locator split

## Goal

Simplify `SCRIPT_ACT` TAP replay into two explicit locator paths:

* `xml locator` path: xml locator resolution, then popup detection, then the existing `VISION_ACT` handoff if there is no popup
* `semantic locator` path: semantic locator resolution, then popup detection, then the existing `VISION_ACT` handoff if there is no popup

The task is now to change the step data model and its attached adaptation so that every TAP step carries a semantic locator, an xml locator is optional, and the old runtime adaptor design (`semantic_tap`, `pending`, `adapted`, `failed`) is removed.

## What I already know

* The issue lives in `android/LXB-Ignition/lxb-core`, inside the `SCRIPT_ACT` task-map replay path.
* A TAP step already has two relevant payload areas in code:
* `locator` is the current structured locator payload and maps to `xml locator`
* `semanticDescriptor` is the current semantic payload already injected into model prompts and maps to `semantic locator`
* `SemanticVisionStepResolver` already passes the semantic payload directly into the model prompt.
* Current replay has separate outer recovery stages:
* popup detection/recovery after a step failure
* route-level handoff to `VISION_ACT` when replay still does not succeed
* The user wants those outer stages preserved as-is.
* Current runtime adaptor behavior exists only for imported portable semantic steps:
* `portable_kind=semantic_tap`
* `adaptation_status=pending`
* `ensureSemanticTapMaterialized(...)`
* `SemanticStepMaterializer`
* writeback of a materialized step into task-map storage
* The user wants that runtime adaptor design removed from this task.
* The user wants terminology unified:
* use only `TAP`
* use only `xml locator`
* use only `semantic locator`
* do not use `semantic tap`, `ordinary locator`, or `normal locator`

## Assumptions (temporary)

* This is a backend/runtime and schema cleanup task.
* No UI redesign is required.
* The current `semanticDescriptor` field will be renamed in code to match the `semantic locator` terminology.
* The existing per-step success sleep and task-map segment behavior stay unchanged unless a locator-path change requires a test update.

## Open Questions

* None blocking right now. The remaining work is implementation detail, not product direction.

## Backend Behavior (evolving)

* Every TAP step must carry a semantic locator.
* A TAP step may also carry an xml locator.
* Replay mode is determined by the step payload:
* if xml locator exists, the step runs in xml locator mode
* otherwise the step runs in semantic locator mode
* xml locator mode flow:
* try xml locator resolution
* if the step still fails, run popup detection using the current logic
* if no popup is found, keep the existing handoff to `VISION_ACT`
* semantic locator mode flow:
* try semantic locator resolution
* if the step still fails, run popup detection using the current logic
* if no popup is found, keep the existing handoff to `VISION_ACT`
* The simplified model removes runtime adaptor states from replay:
* no `semantic_tap`
* no `pending`
* no `adapted`
* no `failed`
* The simplified model removes runtime materialization from `SCRIPT_ACT`:
* no screenshot-driven conversion from semantic target into local locator during replay
* no writeback of a newly materialized step into task-map storage during replay
* Export/import and route assembly should always preserve or build the semantic locator on the step itself.
* The current popup detection and `VISION_ACT` fallback behavior stays in place and should not be reworked beyond the step path split.

## Frontend/User-Facing Interface (evolving)

* Backend-only change.
* Internal traces and logs should reflect the unified `xml locator` / `semantic locator` terminology.

## Decision Log

* Terminology decision → use only `TAP`, `xml locator`, and `semantic locator` → later code and docs must stop using legacy locator labels.
* Architecture decision → remove runtime semantic adaptor behavior from `SCRIPT_ACT` → replay should consume step payloads directly.
* Flow decision → there are two replay paths, selected by whether the step has an xml locator → popup detection and the existing `VISION_ACT` handoff remain the outer recovery flow.
* Scope decision → this task is now about step fields and attached adaptation, not about changing the outer no-popup-to-`VISION_ACT` behavior.

## Requirements (evolving)

* Every TAP step must have a semantic locator payload.
* Some TAP steps also have an xml locator payload.
* The step payload must be enough for replay to choose the correct path without entering a semantic adaptor state.
* Imported/exported route data must not depend on `semantic_tap` or adaptation status fields.
* Replay code must branch cleanly into xml locator mode or semantic locator mode.
* Popup detection remains the failure-recovery stage after locator resolution.
* If popup detection does not find a popup, the current handoff to `VISION_ACT` remains unchanged.
* Code, traces, and specs must use the unified locator terminology.

## Acceptance Criteria (evolving)

* [ ] A TAP step can be replayed in xml locator mode without any semantic adaptor runtime state.
* [ ] A TAP step can be replayed in semantic locator mode without any semantic adaptor runtime state.
* [ ] Replay still reaches popup detection and then the existing `VISION_ACT` handoff when locator resolution fails.
* [ ] The code path no longer relies on `semantic_tap`, `pending`, `adapted`, or `failed`.
* [ ] The codebase and task docs use `TAP`, `xml locator`, and `semantic locator` consistently.
* [ ] Tests cover at least xml-locator mode, semantic-locator mode, and the no-popup fallback path.

## Definition of Done (team quality bar)

* Tests added/updated (unit/integration where appropriate)
* Lint / typecheck / CI green
* Docs/notes updated if behavior changes
* Rollout/rollback considered if risky

## Out of Scope (explicit)

* Changing the outer no-popup-to-`VISION_ACT` logic
* Broad redesign of unrelated `VISION_ACT` planning behavior
* Product UI changes
* General task-map authoring/import UX beyond the step field changes needed here

## Technical Notes

* Relevant files inspected:
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFsmEngine.java`
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/SemanticVisionStepResolver.java`
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/SemanticStepMaterializer.java`
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/LocatorResolver.java`
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/taskmap/TaskMap.java`
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/taskmap/TaskMapAssembler.java`
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/taskmap/PortableTaskRouteCodec.java`
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/taskmap/TaskMapStore.java`
* Current field mapping to planned terminology:
* `TaskMap.Step.locator` -> `xml locator`
* `TaskMap.Step.semanticDescriptor` -> `semantic locator` before code rename
* Current adaptor-only state that will be removed from replay:
* `portable_kind=semantic_tap`
* `portable_kind=materialized`
* `adaptation_status=none/pending/adapted/failed`
* Current timing facts that should remain untouched unless tests prove otherwise:
* segment start sleep: `1500ms`
* per-step success sleep: `400ms`
* locator retry sleep: `300ms`
* Relevant spec entry:
* `.trellis/spec/backend/index.md`
