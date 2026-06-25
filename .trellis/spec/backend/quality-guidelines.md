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

- Trigger: changing route recording, task-map assembly, portable route
  import/export, semantic tap adaptation, or locator replay for TAP steps.
- Applies to `RuntimeLocatorBuilder`, `TaskMapLocalTapBuilder`,
  `TaskMapAssembler`, `PortableTaskRouteCodec`, `SemanticStepMaterializer`,
  `LocatorResolver`, and `CortexFsmEngine` task-map replay.

### 2. Signatures

- `TaskMap.Step.portableKind` allowed TAP target kinds:
  - `local_locator` for XML/accessibility locator-backed taps.
  - `semantic_tap` for semantic target descriptions when no unique XML locator
    is available.
  - `materialized` for imported semantic steps adapted on the local device.
- XML locator fields include `resource_id`, `text`, `content_desc`, `class`,
  `parent_rid`, `locator_index`, `locator_count`, and `bounds_hint`.
- Legacy fields `container_probe`, `tap_point`, and `fallback_point` may still
  be parsed for compatibility, but must not be new targeting strategies.

### 3. Contracts

- Route construction must first attempt an XML locator. If the uniqueness gate
  rejects it, the TAP must become semantic-context backed instead of
  coordinate-backed.
- `TaskMapAssembler` keeps a locator-less TAP only when it has semantic context
  from vision rows, page semantics, expected result, history, or a semantic
  descriptor.
- Runtime replay attempts XML locator resolution first. Missing or failing XML
  locators fall back to semantic visual targeting for the current step.
- `bounds_hint` is a locator tie-breaker only; by itself it is not a usable XML
  locator.
- Portable export emits either `local_locator` or `semantic_tap`; it must not
  export `container_probe`, `tap_point`, or `source_local_kind` as target
  strategy fields.

### 4. Validation & Error Matrix

- TAP with unique XML locator -> save/replay as locator-backed.
- TAP with no XML locator and no semantic context -> drop from assembled map or
  reject portable export as `unsupported_step:no_tap_target`.
- TAP with no XML locator but semantic context -> save/export as
  `semantic_tap`.
- Locator replay failure -> retry per replay policy, then semantic visual
  fallback.
- Semantic visual `no_match` / `ambiguous` / `blocked` / `error` -> route step
  fails and the task map segment falls back to normal visual execution.

### 5. Good/Base/Bad Cases

- Good: duplicate buttons produce `locator_index` disambiguation and no
  `fallback_point`; locator replay uses the index before bounds hint.
- Base: a pure icon tap with vision instruction but no XML locator becomes
  `semantic_tap` and carries a semantic descriptor.
- Bad: persisting a local tap point, container probe, or fallback coordinate as
  a replay strategy for a TAP step.

### 6. Tests Required

- `LocatorSemanticsTest` asserts locator construction and disambiguation, and
  that new locators do not emit fallback coordinates.
- `TaskMapAssemblerTest` asserts locator-less semantic TAPs become
  `semantic_tap` and pure coordinate taps without semantics are skipped.
- `PortableTaskRouteCodecTest` asserts exports contain `local_locator` or
  `semantic_tap` only, while legacy `source_local_kind` imports remain
  tolerated.
- Replay changes must preserve `:lxb-core:test`.

### 7. Wrong vs Correct

#### Wrong

When locator uniqueness fails, write `container_probe`, `tap_point`, or
`fallback_point` and replay that coordinate-backed target later.

#### Correct

When locator uniqueness fails, preserve semantic context as `semantic_tap`; at
replay time, use semantic visual targeting if the XML locator is missing or
fails.

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
- `Workflow` fields use snake_case:
  `workflow_id`, `name`, `description`, `steps`, `failure_policy`,
  `trigger_type`, `trigger_enabled`, `trigger_config`, timestamps.
- `trigger_enabled` controls automatic triggering only. Manual workflow run
  must work regardless of trigger state.
- JSON responses follow `{"ok":true,...}` or `{"ok":false,"err":"..."}`;
  list responses use `{"ok":true,"items":[...]}`.

### 4. Validation & Error Matrix

- Missing `description` on template save -> `description is required`.
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
- API keys, unlock PINs, and full secrets must not be logged or shown in Trace.

### 5. Good/Base/Bad Cases

- Good: a Gemini config with base URL
  `https://generativelanguage.googleapis.com/v1beta`, model
  `gemini-2.0-flash`, and request type `gemini_generate_content` resolves to
  `/models/gemini-2.0-flash:generateContent`.
- Good: split routing can point semantic locator to a small/fast visual model
  and VISION_ACT to a stronger planner model while both provider configs live
  only once in `providers`.
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
