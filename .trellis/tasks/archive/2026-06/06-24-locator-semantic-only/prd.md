# Remove Container Probe And Tap Point Routing

## Goal

Task route replay should use only two tap targeting modes: XML-backed locators and semantic taps. The old `container_probe` and `tap_point` coordinate-backed paths should stop being generated as executable alternatives and should not be used as lookup targets.

## What I Already Know

* Current route construction builds an XML locator first, then falls back to `container_probe` plus `tap_point` when the locator is not accepted.
* Portable export currently converts coordinate-backed taps into `semantic_tap`.
* Runtime tap replay currently uses XML locator resolution, then screenshot visual resolution when locator resolution fails.
* Existing persisted routes and sample assets may still contain `container_probe`, `tap_point`, and `fallback_point`, so readers should tolerate those fields while new behavior stops depending on them.

## Requirements

* Keep XML locator construction for unique, usable UI hierarchy targets.
* When a unique XML locator cannot be constructed, create a semantic tap instead of `container_probe` or `tap_point`.
* During replay, try XML locator first when it exists and is usable.
* If XML locator is missing or cannot resolve, fall back to semantic location.
* Remove `container_probe` and `tap_point` from new construction and lookup behavior.
* Keep backward-compatible parsing of old persisted fields where cheap, but do not treat them as executable targeting strategies.

## Backend Behavior

* `TaskMapLocalTapBuilder` should return either an accepted locator payload or a semantic fallback marker/source point used only to build semantic metadata.
* `RuntimeLocatorBuilder.buildContainerProbe` should no longer be part of route construction.
* `TaskMapAssembler` should keep TAP steps when they have either a locator or semantic context; it should not depend on `container_probe`/`tap_point`.
* Portable route export should emit `local_locator` for locator-backed taps and `semantic_tap` for semantic-backed taps.
* Imported `semantic_tap` steps remain pending until first replay materializes them using the screenshot model.
* Locator resolver internals remain structurally the same: self attributes, optional text, parent rid, index, then bounds hint.
* Replay fallback should be explicit: locator resolution failure leads to semantic visual resolution.

## Frontend/User-Facing Interface

* No new UI controls are required.
* Trace/details may still display legacy fields if old route data contains them, but new route data should emphasize locator and semantic tap fields.
* Existing route import/export user flows should continue to work.

## Acceptance Criteria

* [x] New TAP route construction never uses `container_probe` as the fallback targeting strategy.
* [x] New TAP route construction never uses `tap_point` as the fallback targeting strategy.
* [x] Non-unique XML locator taps are represented as semantic taps.
* [x] Replay tries XML locator first and falls back to semantic location when locator is missing or fails.
* [x] Portable export/import tests cover locator-backed and semantic-backed taps.
* [x] Existing task-map JVM tests pass.

## Out Of Scope

* Removing backward-compatible JSON fields from all persisted model classes.
* Changing the internal XML locator matching stages.
* Adding new frontend controls.
* Changing non-TAP operations such as SWIPE, INPUT, BACK, and WAIT.

## Technical Notes

* Main files expected to change:
  * `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/taskmap/TaskMapLocalTapBuilder.java`
  * `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/taskmap/TaskMapAssembler.java`
  * `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/taskmap/PortableTaskRouteCodec.java`
  * `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/SemanticStepMaterializer.java`
  * `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFsmEngine.java`
* Relevant specs read:
  * `.trellis/spec/backend/index.md`
  * `.trellis/spec/backend/directory-structure.md`
  * `.trellis/spec/backend/error-handling.md`
  * `.trellis/spec/backend/logging-guidelines.md`
  * `.trellis/spec/backend/quality-guidelines.md`
  * `.trellis/spec/backend/database-guidelines.md`
* Verification:
  * `./gradlew.bat :lxb-core:test` passed from `android/LXB-Ignition`.

## Decision Log

* User requirement: only locator and semantic_tap remain; remove container_probe and tap_point construction/lookup. Decision: implement as a two-mode tap targeting contract with XML locator first and semantic fallback second.
* Compatibility decision: retain old field parsing to avoid breaking existing saved route files, but stop generating or executing those fields as target strategies.
