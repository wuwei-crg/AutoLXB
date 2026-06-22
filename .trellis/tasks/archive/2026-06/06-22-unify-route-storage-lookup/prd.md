# brainstorm: unify route storage and lookup

## Goal

Fix the route-key mismatch where template execution stores route data under one
name but the FSM reads a different key. Route storage, status display,
import/export, and runtime replay should all use the template id directly as the
route key.

## What I already know

* The current bug is confirmed by inspection.
* `TaskTemplate.normalizeForSave()` stores template routes as
  `template:<template_id>`.
* `WorkflowPortableCodec` imports template routes under `template.routeId`.
* `CortexTaskManager.executeWorkflow()` submits template steps with
  `source="template"` and `sourceId=template.routeId`.
* `CortexTaskManager.resolveRouteId()` recognizes `source="template"` and
  returns the provided route id.
* `CortexFsmEngine.resolveRouteId()` only handles `schedule` and
  `notify_trigger`; it falls back to the per-run `taskId` for template runs.
* Result: task-map status/editor paths can point at `template:<id>`, while FSM
  route lookup can look for the transient task id and miss the saved route.

## Assumptions

* Canonical route identity is the template id itself.
* There are no active schedule/notification route owners anymore.
* Quick/manual one-off tasks are not route-owned and should not invent a route
  id.
* Existing `template:<template_id>` storage is legacy and should be migrated or
  removed, not kept as a live fallback.

## Requirements

* Runtime route lookup and route status/detail APIs must use the template id
  directly for template-owned routes.
* There must be one backend route-key path, not two competing manager/FSM
  readers.
* Old duplicated route-reading helpers must be removed, not left as parallel
  fallback paths.
* Template editor, route editor, workflow execution, and FSM lookup must all
  read/write the same template id.
* Route save, route delete, route detail, portable import/export, and task run
  snapshots must continue to use the same key field.
* Runtime task instances should carry the resolved template id instead of
  recomputing it independently in every read path.
* Schedule/notification route compatibility must be removed from the active
  route path.
* The route-key logic must be centralized or otherwise kept in sync so
  `CortexTaskManager` and `CortexFsmEngine` cannot drift again.
* Trace events such as `task_route_key`, `task_route_lookup`,
  `task_route_lookup_hit`, and task run snapshots should report the canonical
  route id.

## Backend Behavior

### Existing Route Read Paths

* Template storage:
  * `TaskTemplate.routeId` is legacy storage, not the canonical identity.
  * Imported template bundles save route maps under the template's id.
* Manager/API read path:
  * `CortexTaskManager.resolveRouteId()` still recognizes multiple sources, but
    that is broader than the current model.
  * `CortexTaskManager.resolveTaskKeyHash()` and
    `resolvePersistedTaskKeyHash()` wrap direct `route_id` plus manager-side
    source/sourceId/taskId resolution.
  * Task status/list and task-map APIs use the manager-side resolver.
* FSM runtime read path:
  * `CortexFsmEngine.resolveRouteId()` only recognizes old source-based cases.
  * For template runs it can fall back to the transient `taskId`, causing route
    lookup to miss the saved template route.

### Unified Route Read Plan

* Treat `template_id` as the first-class canonical route key for template-owned
  routes.
* Route editor should ask for and load the route by template id.
* Workflow execution should pass the step's template id into the FSM as the
  route key.
* FSM should load the task map using that explicit template id. It should not
  infer route ownership from source or trigger state.
* Direct route reads may remain only as a low-level "load this exact key"
  operation, not as a source-based guessing layer.
* Delete the old local `resolveRouteId()` methods from both
  `CortexTaskManager` and `CortexFsmEngine`.
* Delete or collapse manager-only wrapper methods if they only forward to the
  source-based guessing layer after migration.
* Existing template route data under `template:<template_id>` should be migrated
  to plain `template_id` or renamed in place.
* Task status/list snapshots should read the stored template id when available.
* Invalid or empty inputs should preserve current best-effort behavior rather
  than failing task submission.
* Unit tests should cover route id parity between route editor/status and FSM
  lookup.

## Frontend/User-Facing Interface

* No new UI surface is expected for the MVP.
* Existing route editor/status UI should become more accurate because backend
  `route_id` and `has_task_map` values align with runtime lookup.
* Existing Chinese-first copy and route editor flows should remain unchanged.

## Acceptance Criteria

* [x] Running a template with `task_map_mode=manual` or `ai` looks up the
      route by `template_id` directly.
* [x] Running a workflow step that references a template uses the template id
      for lookup and trace output.
* [x] No active route path constructs or reads `schedule:*` or `notify:*`
      route keys.
* [x] Quick/manual one-off tasks do not create or read template routes.
* [x] `TaskInstance` / persisted task run rows store and expose the resolved
      template id when the run is template-owned.
* [x] Existing portable template/workflow route import/export tests continue to
      pass.
* [x] New or updated tests catch route-id parity between saved route status and
      FSM lookup.
* [x] No local `resolveRouteId()` helper remains in `CortexTaskManager` or
      `CortexFsmEngine`.
* [x] No manager-only route-key wrapper remains unless it adds behavior beyond
      direct exact-id reads.

## Definition of Done

* Tests added or updated for the route-id mismatch.
* Relevant backend tests pass.
* App-side parser/model behavior remains compatible.
* No unrelated route/map UI changes.
* Specs updated only if implementation reveals a durable convention worth
  preserving.

## Technical Approach

Recommended approach: stop deriving route keys from source. Make the template id
the saved-route key. The route editor passes template id, workflow execution
passes template id, and the FSM loads by the exact id it was given. Remove the
old source-based route-id helpers after migrating any `template:<id>` data to
plain `template_id`.

## Decision Log

* User confirmed the mismatch is a bug and asked to create a task to unify route
  storage and lookup -> create this Trellis task and seed PRD with the canonical
  route-id requirements.
* User asked to clarify existing read paths and explicitly delete old read paths
  cleanly -> record the existing manager/FSM split and make deletion of old
  local resolvers part of acceptance criteria.
* User clarified schedule and notification tasks no longer own routes because
  workflows now organize everything -> route identity is template-only and the
  active path should use template id directly.

## Out of Scope

* Redesigning task maps, cloud map lanes, or route editor UX.
* Changing portable bundle schema unless required for compatibility.
* Preserving schedule/notification route ownership in the active path.
* Migrating historical task run rows that only recorded transient task ids.
* Changing the route-generation locator semantics.

## Technical Notes

* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/workflow/TaskTemplate.java`
  stores default template route ids.
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/workflow/WorkflowPortableCodec.java`
  imports and exports template/workflow routes.
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexTaskManager.java`
  has manager-side route-id resolution and workflow execution.
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFsmEngine.java`
  has FSM-side route lookup and currently missing template source handling.
* `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/taskmap/TaskRouteKey.java`
  is the likely shared home for route identity helpers.

## Implementation Notes

* `TaskTemplate.routeId` now normalizes to plain `template_id` on save.
* Workflow/template route import/export reads and writes routes by
  `template_id`.
* `CortexTaskManager` stores the resolved route id on template-owned task runs
  and no longer derives route keys from source/source_id.
* `CortexFsmEngine` uses the explicit template source id as the route id only
  for template runs; non-template runs have no route id.
* `TaskMapStore.migrateRouteKey()` performs one-time artifact migration from
  legacy keys such as `template:<id>` to `<id>`.
* Template route editor requests route detail by direct `route_id=<template_id>`.

## Verification

* `./gradlew.bat :lxb-core:test`
* `./gradlew.bat :app:testDebugUnitTest`
