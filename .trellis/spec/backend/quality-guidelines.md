# Backend Quality Guidelines

Backend changes must preserve the daemon's protocol compatibility and ability
to run on Android with minimal dependencies.

## Required Patterns

- Keep `lxb-core` dependency-light. The module is a Java library converted to
  dex; use the local `cortex/json/Json.java` parser/writer for backend JSON.
- Keep protocol constants, app commands, dispatcher cases, and parsers aligned.
  A new command usually touches `CommandIds`, `CommandDispatcher`, a backend
  handler, an app caller, and app parser/tests.
- Bound payload sizes and user-provided limits. `FrameCodec` caps v2 payloads
  at 16 MiB; Trace pull limits are clamped.
- Preserve old persisted fields when adding new fields. Existing code contains
  compatibility paths for `repeat_daily`, missing task-map schema, and legacy
  map/source fields.
- Keep daemon concurrency explicit. `Main` creates one thread per TCP client;
  shared registries and queues in task/schedule managers use synchronization or
  thread-safe collections where needed.

Reference files:
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/protocol/FrameCodec.java`
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexTaskManager.java`
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/taskmap/TaskMap.java`
- `android/LXB-Ignition/lxb-core/src/test/java/com/lxb/server/cortex/taskmap/TaskMapStoreTest.java`

## Forbidden Patterns

- Do not add backend dependencies that will not survive the `jar -> d8 ->
  lxb-core-dex.jar` packaging path.
- Do not put business logic in `CommandDispatcher`; it should route only.
- Do not change command ids or frame layout without updating both Java backend
  and Kotlin app callers.
- Do not break JSON field names used by docs, sample tasks, app parsers, or
  persisted device files.
- Do not make task execution depend on network access except where the design
  already calls LLM/model APIs.

## Testing

Use JVM tests for deterministic backend behavior:

- Protocol framing and CRC validation.
- Schedule time calculation and schedule trigger behavior.
- Task-map storage, portable route codec, adaptation/materialization, and
  route key isolation.
- Notification rule parsing and dump parsing.
- App-side parser behavior for backend JSON payloads.

Useful commands from the Android project root:

```powershell
cd android/LXB-Ignition
./gradlew.bat :lxb-core:test
./gradlew.bat :app:testDebugUnitTest
```

If a change affects app/core contracts, add or update tests on both sides where
possible.

## Scenario: Task Map Tap Targeting Modes

### 1. Scope / Trigger

- Trigger: changing TAP route recording, task-map assembly, portable route
  import/export, or SCRIPT_ACT replay selection.
- Applies to `TaskMap.Step`, `TaskMapAssembler`, `PortableTaskRouteCodec`,
  `TaskMapStore` compatibility helpers, `SemanticVisionStepResolver`,
  `LocatorResolver`, `CortexFsmEngine`, and `CortexTaskManager`.

### 2. Signatures

- `TaskMap.Step` now carries:
  - `xml_locator` as the optional XML/accessibility locator payload.
  - `semantic_locator` as the semantic payload required for every TAP step.
- Legacy input aliases remain parseable for compatibility:
  - `locator` -> `xml_locator`
  - `semantic_descriptor` -> `semantic_locator`
- Portable export/import counters use:
  - `xml_locator_step_count`
  - `semantic_locator_step_count`
- Legacy runtime-adapter fields (`portable_kind`, `adaptation_status`,
  `adaptation_error`, `materialized_*`) may still be read from old persisted
  data, but new serialization paths must not emit them.

### 3. Contracts

- `TaskMapAssembler` always builds a semantic locator for TAP steps. If an XML
  locator is present, it is copied too. If the route has no semantic context, a
  weak fallback semantic locator is synthesized so the step still replays.
- SCRIPT_ACT replay chooses the path from the step payload:
  - xml locator present -> xml locator mode
  - otherwise -> semantic locator mode
- xml locator mode resolves the XML locator directly.
- semantic locator mode routes through the semantic visual resolver directly.
- After a semantic TAP is executed, the next semantic step may run a
  post-tap verifier before the route index advances. The verifier compares the
  current screenshot against the last TAP locator, the current step locator,
  and the last TAP expected result, then chooses previous/current/defer.
- Semantic visual locator and post-tap verifier model outputs use normalized
  0-1000 coordinates. The FSM maps those normalized coordinates to device
  pixels immediately before execution. XML locator bounds remain device-pixel
  coordinates because they come from the accessibility dump.
- Before screenshot-driven semantic locator analysis begins, replay inserts a
  dedicated settle delay so the screenshot is less likely to capture a
  half-rendered page.
- The outer popup-detection stage and the existing no-popup -> `VISION_ACT`
  handoff stay unchanged.
- Portable export writes `semantic_locator` for every TAP and `xml_locator`
  only when it exists.
- Portable import and persisted map loading still accept legacy field names and
  synthesize a fallback semantic locator when older data omitted one.
- `bounds_hint` remains a locator tie-breaker only; it is not a standalone
  targeting strategy.

### 4. Validation & Error Matrix

- TAP with xml locator -> replay in xml locator mode.
- TAP without xml locator but with semantic locator -> replay in semantic
  locator mode, with post-tap verification when a prior TAP checkpoint exists.
- TAP missing semantic locator in old persisted data -> synthesize fallback
  semantic locator during import/load, then replay normally.
- Locator resolution failure -> retry per replay policy, then fall through to
  the existing visual recovery path.
- Post-tap verifier decision `previous` retries the last TAP once and keeps the
  same route index; `current` advances normally; `defer` falls back to the
  existing visual logic when the page is still transitioning.
- Semantic visual locator or post-tap verifier coordinates outside `[0,1000]`
  are invalid model output and must not be executed directly as device pixels.
- Semantic visual `no_match` / `ambiguous` / `blocked` / `error` -> route step
  fails and outer popup recovery decides whether to continue or hand off.
- Semantic visual resolve or semantic adaptation screenshot capture ->
  emit `task_map_semantic_screenshot_settle` with the wait duration before
  grabbing the screenshot.
- Portable import/export still rejects unsupported schemas, versions, and
  malformed bundles.

### 5. Good/Base/Bad Cases

- Good: a locator-backed button tap exports both `xml_locator` and
  `semantic_locator`, then replays from xml first.
- Base: a pure vision tap with no XML locator still replays via the semantic
  locator with a weak default instruction.
- Good: a semantic TAP that did not land is retried from the last TAP
  checkpoint instead of advancing blindly.
- Bad: storing a TAP step with only `portable_kind` and expecting runtime
  materialization to fill in the missing semantic context.
- Bad: marking a TAP step successful just because the click command returned
  ok, even when the post-tap verifier says the page did not transition.

### 6. Tests Required

- `TaskMapAssemblerTest` covers locator-backed TAPs, fallback semantic
  locators, and pure coordinate taps.
- `PortableTaskRouteCodecTest` covers export/import of `xml_locator` and
  `semantic_locator` plus legacy input tolerance.
- `TaskMapTest` covers new-field round trips and legacy field-name
  compatibility.
- `CortexTaskMapReplayTest` covers xml path, semantic path, and retry behavior
  without container/fallback coupling.
- `TaskMapTapReplayVerifierTest` covers the verifier prompt contract and JSON
  decision parsing.
- `CortexTaskMapReplayTest` also covers the dedicated semantic screenshot
  settle delay and its trace event before screenshot capture.
- `SemanticVisionStepResolverTest` covers semantic locator prompt text.
- `:lxb-core:test` must remain green.

### 7. Wrong vs Correct

#### Wrong

Keep `semantic_tap` / `materialized` as the active replay state and only
materialize semantic steps at runtime.

#### Correct

Persist the semantic locator on the step itself, choose xml vs semantic replay
from the step payload, and keep old runtime-adapter fields only as
compatibility input.

## Scenario: Cortex FSM Manual Cancellation Safe Points

### 1. Scope / Trigger

- Trigger: changing frontend manual stop behavior, `CMD_CORTEX_FSM_CANCEL`,
  `CortexTaskManager` cancellation propagation, or long-running
  `CortexFsmEngine` replay / wait / retry helpers.
- Applies to `CortexFacade`, `CommandDispatcher`, `CortexTaskManager`,
  `CortexFsmEngine`, app-side `TraceEventMapper`, and task-runtime UI status.

### 2. Signatures

- Command id:
  - `CMD_CORTEX_FSM_CANCEL`
- Runtime hook:
  - `CortexFsmEngine.CancellationChecker`
- Trace events:
  - `fsm_cancel_requested`
  - `fsm_task_cancelled`
- Task failure reason:
  - `cancelled_by_user`

### 3. Contracts

- Frontend manual stop remains an async best-effort cancel request. The cancel
  command still ACKs immediately with `{"ok":true,"reason":"cancel_requested"}`.
- `CortexTaskManager` owns the shared cancel flag and passes a
  `CancellationChecker` into the running FSM.
- `CortexFsmEngine` must observe cancellation at shared safe points, not only
  at the outer state loop. Required safe-point categories include:
  - SCRIPT_ACT replay loop boundaries
  - shared settle / retry sleeps
  - action `WAIT` and post-action settle waits
  - VISION_ACT first-turn settle and retry loop boundaries
- When cancellation is observed, the FSM sets `ctx.error="cancelled_by_user"`
  and exits through the existing fail/cancel path.
- `fsm_task_cancelled` must be emitted once per cancelled task run, even if
  multiple helpers observe the same cancel flag.
- First-pass cancellation is cooperative. It does not promise forced abortion
  of an already in-flight LLM HTTP call; cancellation takes effect after that
  call returns to a shared safe point.

### 4. Validation & Error Matrix

- Cancel before entering `SCRIPT_ACT` or `VISION_ACT` -> fail immediately with
  `cancelled_by_user` and emit `fsm_task_cancelled` once.
- Cancel during replay sleep / retry / settle wait -> exit at that wait helper
  instead of waiting for the outer FSM loop.
- Cancel during post-action UI settle -> stop before the next screenshot /
  planner turn.
- Cancel while inside an in-flight LLM request -> request may finish or time
  out first; FSM exits on the next safe point afterward.
- Duplicate cancel checks after the first observed cancel -> no duplicate
  `fsm_task_cancelled` events.

### 5. Good/Base/Bad Cases

- Good: user taps stop during SCRIPT_ACT route replay and the task exits during
  the next shared wait/retry boundary.
- Good: user taps stop during VISION_ACT first-turn settle and the planner
  never takes another screenshot-driven turn.
- Base: user taps stop while a model call is already blocked in HTTP; the UI
  shows cancelling, and the task exits as soon as control returns from that
  call.
- Bad: only polling cancellation at the outer FSM loop, leaving long replay or
  settle helpers uninterruptible for seconds.
- Bad: emitting `fsm_task_cancelled` from every helper that notices the flag.

### 6. Tests Required

- `CortexScriptActResultTest` covers cancellation during SCRIPT_ACT replay and
  asserts fail-state plus `fsm_task_cancelled`.
- `CortexFsmCancellationTest` covers cancellation before VISION_ACT first-turn
  settle and asserts no settle trace is emitted.
- `:lxb-core:test` must remain green.
- `:app:testDebugUnitTest` should remain green because app-side cancel trace
  mapping is part of the contract.

### 7. Wrong vs Correct

#### Wrong

Treat frontend stop as a pure UI flag while the backend keeps replaying until
an outer FSM state finishes naturally.

#### Correct

Keep the existing cancel command contract, but make the FSM cooperatively poll
shared safe points so manual stop takes effect promptly without forcing thread
interrupt semantics or transport redesign.

## Scenario: Cortex Task Templates And Workflows

### 1. Scope / Trigger

- Trigger: adding or changing Cortex task-template/workflow authoring,
  persistence, migration, or command APIs.
- Applies to `lxb-core` workflow/template models, `CortexTaskManager`,
  `CortexFacade`, `CommandDispatcher`, app-side `CoreApiParser`, and Task tab
  UI flows.

### 2. Signatures

- Persistence:
  - `task_templates.v1.json` root key `templates`
  - `workflows.v1.json` root key `workflows`
- Command ids:
  - `CMD_CORTEX_TEMPLATE_LIST/GET/SAVE/DELETE/RUN`
  - `CMD_CORTEX_WORKFLOW_LIST/GET/SAVE/DELETE/RUN/CANCEL/STATUS`
- Runtime adapter:
  - template task runs use `source="template"` and `source_id=<template_id>`.
  - route lookup reads the explicit `route_id` or the task run's stored
    `route_id`; it must not derive route keys from schedule/notification
    sources.

### 3. Contracts

- `TaskTemplate` fields use snake_case:
  `template_id`, `name`, `description`, `package_name`, `start_page`,
  `map_path`, `user_playbook`, `record_enabled`, `task_map_mode`, `route_id`,
  `decompose_enabled`, `created_at_ms`, `updated_at_ms`.
- New templates default `decompose_enabled=false`; if `route_id` is omitted,
  save normalizes it to the plain `template_id`.
- Template save uses sparse field-merge semantics for updates: omitted
  persisted fields such as `task_map_mode`, `package_name`,
  `decompose_enabled`, and other template metadata must remain unchanged unless
  the caller explicitly sends replacement values.
- `Workflow` fields use snake_case:
  `workflow_id`, `name`, `description`, `steps`, `failure_policy`,
  `trigger_type`, `trigger_enabled`, `trigger_config`, timestamps.
- `trigger_enabled` controls automatic triggering only. Manual workflow run
  must work regardless of trigger state.
- JSON responses follow `{"ok":true,...}` or `{"ok":false,"err":"..."}`;
  list responses use `{"ok":true,"items":[...]}`.

### 4. Validation & Error Matrix

- Missing `description` on template save -> `description is required`.
- Template partial update without `task_map_mode` -> preserve the existing
  route mode; do not silently fall back to `off`.
- Workflow save with zero steps -> `workflow must have at least one step`.
- Workflow step with missing/unknown template -> validation error naming the
  missing `template_id`.
- Delete referenced template -> reject with the referencing `workflow_id`.
- Delete running workflow -> reject until the workflow run is cancelled.
- Workflow cancel/status without `workflow_run_id` -> command error.

### 5. Good/Base/Bad Cases

- Good: create one template, create one workflow with one step, run workflow,
  route lookup uses the template route id.
- Base: no automatic trigger, `trigger_type="none"`, `trigger_enabled=false`,
  empty `trigger_config`.
- Bad: creating separate persisted trigger rows or making schedule/notification
  lists the primary frontend entry again.

### 6. Tests Required

- Backend store tests assert template default route/decompose values and
  referenced-template delete rejection.
- Backend template-save tests assert partial updates preserve existing
  `task_map_mode` and other omitted template fields.
- Command id uniqueness test must include new template/workflow ids.
- App parser tests assert template/workflow list and workflow run response
  parsing.
- Execution changes should preserve existing `:lxb-core:test` and
  `:app:testDebugUnitTest`.

### 7. Wrong vs Correct

#### Wrong

Persist a schedule as a standalone schedule row and also show it in a separate
Schedules list after workflow migration.

#### Correct

Migrate the legacy schedule into one `TaskTemplate` plus one visible
`Workflow` whose embedded `trigger_config` carries the schedule fields, then
clear the legacy schedule row to avoid duplicate automatic firing.

## Scenario: Workflow And Template Portable Bundles

### 1. Scope / Trigger

- Trigger: adding or changing portable import/export for Cortex task templates,
  workflows, or route compatibility.
- Applies to `WorkflowPortableCodec`, `CortexTaskManager.handlePortable`,
  `CortexFacade.handleCortexPortable`, `CommandDispatcher`, `CommandIds`,
  app-side `CoreApiParser`, and Task tab import/export actions.

### 2. Signatures

- Command id: `CMD_CORTEX_PORTABLE`.
- Request envelope:
  - `{"action":"export_template","template_id":"..."}`
  - `{"action":"export_workflow","workflow_id":"..."}`
  - `{"action":"import","bundle":{...}}` or
    `{"action":"import","bundle_json":"{...}"}`
- Portable schemas:
  - `workflow_bundle`, `version=1`
  - `task_template_bundle`, `version=1`
  - legacy `task_route_asset.v1` and `task_route_portable.v1` remain importable
    as compatibility inputs.

### 3. Contracts

- Export responses include `ok=true`, `schema`, `version`, `bundle`, and
  `bundle_json`.
- Workflow bundle export carries one workflow plus exactly the templates
  referenced by its steps.
- Workflow bundle export does not carry `trigger_type`, `trigger_enabled`, or
  `trigger_config`.
- Template bundle export carries one template and its route asset when a route
  exists.
- Import always creates new local ids for templates and workflows, then rewrites
  workflow step `template_id` references to the new template ids.
- Imported workflows must start with `trigger_type="none"` and
  `trigger_enabled=false`.
- Import responses include `imported_type`, `workflow_id`, `template_id`, and
  `template_ids` so the app can open the imported object directly.

### 4. Validation & Error Matrix

- Missing `action` -> `action is required`.
- Unknown action -> `unsupported portable action: <action>`.
- Export missing id -> `<template_id|workflow_id> is required`.
- Export unknown id -> `<template|workflow> not found: <id>`.
- Unsupported schema or version -> an unsupported schema/version error.
- Workflow bundle whose embedded template ids do not exactly match step
  references -> reject the whole import.
- Unusable route asset inside a bundle -> reject the whole import.

### 5. Good/Base/Bad Cases

- Good: export a workflow with two steps, import it on another device, get one
  new workflow and two new templates with no automatic trigger enabled.
- Base: export/import a template without a route; the template is still created
  and can learn or record a route later.
- Bad: resolving imported workflow steps by local template names or old exported
  ids instead of embedded-template id rewriting.

### 6. Tests Required

- Backend codec tests assert trigger omission, new id generation, disabled
  imported triggers, exact embedded-template validation, and route-only import.
- App parser tests assert portable export requires `bundle_json` and import
  success exposes the imported object id/type.
- Any change to `CMD_CORTEX_PORTABLE` must keep `:lxb-core:test` and
  `:app:testDebugUnitTest` passing.

### 7. Wrong vs Correct

#### Wrong

Reuse `CMD_CORTEX_TASK_MAP export_portable/import_portable` for workflow export
and make the app infer templates from route metadata.

#### Correct

Use `CMD_CORTEX_PORTABLE` for workflow/template bundles, keep route portable
commands as compatibility paths only, and adapt legacy route assets inside the
new importer.

## Scenario: Device-Side LLM Request Types And Routing

### 1. Scope / Trigger

- Trigger: adding or changing device-side LLM/VLM provider request protocols
  or model routing between device-side LLM call sites.
- Applies to `LlmConfig`, `LlmClient`, APK model config state,
  `DeviceConfigSyncer`, saved LLM profiles, model config UI, and model setup
  docs.

### 2. Signatures

- Persisted core config JSON:
  - `api_base_url: string`
  - `api_key: string`
  - `model: string`
  - `request_type: string`
  - `providers: array<object>` where each row has `provider_id`, `name`,
    `api_base_url`, `api_key`, `model`, `request_type`, and `updated_at`
  - `active_provider_id: string`
  - `model_routing: object`
    - `mode: "unified" | "split"`
    - `script_action.unified_provider_id: string`
    - `script_action.semantic_locator_provider_id: string`
    - `script_action.vision_act_provider_id: string`
- Supported `request_type` values:
  - `openai_chat_completions`
  - `gemini_generate_content`
  - `anthropic_messages`
- Legacy configs without `request_type` must be read as
  `openai_chat_completions`.

### 3. Contracts

- `api_base_url` is a base URL. `LlmClient` expands it by request type:
  - OpenAI Chat Completions -> `/chat/completions`
  - Gemini generateContent -> `/models/{model}:generateContent`
  - Anthropic Messages -> `/messages`
- If the user already entered a complete endpoint, do not append the path a
  second time. Complete endpoint detection must ignore query strings.
- `api_key` remains the single secret field:
  - OpenAI-compatible requests use `Authorization: Bearer <api_key>`.
  - Gemini native requests use the REST `key` query parameter.
  - Anthropic native requests use `x-api-key` plus `anthropic-version`.
- Top-level `api_base_url/api_key/model/request_type` fields remain the
  legacy fallback provider and must continue to be written by the APK.
- Saved APK model configs are provider presets. Route configuration stores
  provider ids only; do not copy provider credentials into each route.
- `model_routing.mode="unified"` routes SCRIPT_ACT semantic locator and
  VISION_ACT planner calls to `unified_provider_id` (or `active_provider_id`).
- `model_routing.mode="split"` routes SCRIPT_ACT semantic visual locator and
  semantic adaptation calls to `semantic_locator_provider_id`, and routes
  VISION_ACT planner calls to `vision_act_provider_id`.
- Other LLM call sites such as task decomposition, app resolve, and
  notification LLM conditions keep using the default/unified config unless a
  task explicitly changes their route.
- Image requests preserve current behavior: user prompt plus image only. Do not
  add image+system prompt behavior unless the task explicitly changes that
  contract.
- Text requests may map non-empty `systemPrompt` to the native protocol's
  system-instruction shape.
- LLM call sites that require JSON output should strict-parse first, then
  tolerate model-wrapped JSON through `CortexLlmHelper.extractJsonObjectFromText`.
  Preserve clear `invalid_json` or call-site-specific parse errors when no
  parseable JSON object exists.

### 4. Validation & Error Matrix

- Missing `api_base_url` or `model` -> config load/test failure.
- Missing `request_type` -> normalize to `openai_chat_completions`.
- Unknown `request_type` -> normalize to `openai_chat_completions`.
- Missing `providers/model_routing` -> legacy top-level config loads as a
  unified route.
- Split routing with a missing semantic locator or vision_act provider id ->
  clear config error; do not silently fall back to the other route.
- Route provider id not found in `providers` -> clear config error naming the
  missing provider id.
- Selected provider missing `api_base_url` or `model` -> config load/test
  failure for that provider.
- Native Gemini/Anthropic response without extractable official text -> clear
  LLM parse/config error, not raw JSON passed upstream.
- JSON-only model response wrapped in markdown fences or explanatory text ->
  parse the embedded object when it is otherwise valid.
- JSON-only model response with no parseable JSON object -> clear
  `invalid_json` or call-site-specific parse error; do not treat it as a Trace
  serialization failure.
- API keys, unlock PINs, and full secrets must not be logged or shown in Trace.

### 5. Good/Base/Bad Cases

- Good: a Gemini config with base URL
  `https://generativelanguage.googleapis.com/v1beta`, model
  `gemini-2.0-flash`, and request type `gemini_generate_content` resolves to
  `/models/gemini-2.0-flash:generateContent`.
- Good: split routing can point semantic locator to a small/fast visual model
  and VISION_ACT to a stronger planner model while both provider configs live
  only once in `providers`.
- Good: a semantic locator response wrapped in markdown fences around
  `{"result":"point","x":123,"y":456}` is parsed as the embedded object.
- Base: an old OpenAI-compatible config with no `request_type` continues using
  `/chat/completions`.
- Base: a config with no `providers/model_routing` continues using top-level
  provider fields for all call sites.
- Bad: creating one large vendor adapter per provider when the desired
  extension point is request protocol shape.
- Bad: duplicating `api_key`/`model` under
  `model_routing.script_action.semantic_locator` or silently using VISION_ACT's
  provider when split semantic locator routing is incomplete.

### 6. Tests Required

- JVM tests for config backward compatibility and request type normalization.
- JVM tests for provider-route resolution, including unified routing, split
  semantic locator vs vision_act routing, legacy fallback, missing provider id,
  and missing split-route provider.
- JVM tests for endpoint expansion, including already-complete endpoints and
  query strings.
- JVM tests for request-type-specific auth/header behavior.
- JVM tests for text and image payload shape.
- JVM tests for response text extraction per request type.
- JVM tests for JSON-only model consumers accepting strict JSON and
  fenced/wrapped JSON, while still rejecting non-JSON command text as
  `invalid_json` or an equivalent call-site parse error.
- App JVM test for `DeviceConfigSyncer` writing `request_type`,
  `providers`, `active_provider_id`, and `model_routing` into the config JSON
  sent to core.

### 7. Wrong vs Correct

#### Wrong

Infer Gemini or Anthropic behavior only from URL substrings, or add separate
provider classes such as `GeminiProvider` and `AnthropicProvider` while leaving
the actual request protocol implicit. For routing, copy provider credentials
under each route block.

#### Correct

Persist an explicit `request_type`, normalize unknown or missing values to
OpenAI-compatible, and keep provider-specific URL examples as UI/documentation
helpers instead of implementation branches. For routing, store provider
credentials once in `providers` and store only provider ids under
`model_routing`.
