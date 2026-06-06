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
  - template task runs use `source="template"` and `source_id=<route_id>`.
  - `resolveRouteId("template", sourceId, taskId)` must return `sourceId`.

### 3. Contracts

- `TaskTemplate` fields use snake_case:
  `template_id`, `name`, `description`, `package_name`, `start_page`,
  `map_path`, `user_playbook`, `record_enabled`, `task_map_mode`, `route_id`,
  `decompose_enabled`, `created_at_ms`, `updated_at_ms`.
- New templates default `decompose_enabled=false`; if `route_id` is omitted,
  save normalizes it to `template:<template_id>`.
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

## Scenario: Device-Side LLM Request Types

### 1. Scope / Trigger

- Trigger: adding or changing device-side LLM/VLM provider request protocols.
- Applies to `LlmConfig`, `LlmClient`, APK model config state,
  `DeviceConfigSyncer`, saved LLM profiles, model config UI, and model setup
  docs.

### 2. Signatures

- Persisted core config JSON:
  - `api_base_url: string`
  - `api_key: string`
  - `model: string`
  - `request_type: string`
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
- Image requests preserve current behavior: user prompt plus image only. Do not
  add image+system prompt behavior unless the task explicitly changes that
  contract.
- Text requests may map non-empty `systemPrompt` to the native protocol's
  system-instruction shape.

### 4. Validation & Error Matrix

- Missing `api_base_url` or `model` -> config load/test failure.
- Missing `request_type` -> normalize to `openai_chat_completions`.
- Unknown `request_type` -> normalize to `openai_chat_completions`.
- Native Gemini/Anthropic response without extractable official text -> clear
  LLM parse/config error, not raw JSON passed upstream.
- API keys, unlock PINs, and full secrets must not be logged or shown in Trace.

### 5. Good/Base/Bad Cases

- Good: a Gemini config with base URL
  `https://generativelanguage.googleapis.com/v1beta`, model
  `gemini-2.0-flash`, and request type `gemini_generate_content` resolves to
  `/models/gemini-2.0-flash:generateContent`.
- Base: an old OpenAI-compatible config with no `request_type` continues using
  `/chat/completions`.
- Bad: creating one large vendor adapter per provider when the desired
  extension point is request protocol shape.

### 6. Tests Required

- JVM tests for config backward compatibility and request type normalization.
- JVM tests for endpoint expansion, including already-complete endpoints and
  query strings.
- JVM tests for request-type-specific auth/header behavior.
- JVM tests for text and image payload shape.
- JVM tests for response text extraction per request type.
- App JVM test for `DeviceConfigSyncer` writing `request_type` into the config
  JSON sent to core.

### 7. Wrong vs Correct

#### Wrong

Infer Gemini or Anthropic behavior only from URL substrings, or add separate
provider classes such as `GeminiProvider` and `AnthropicProvider` while leaving
the actual request protocol implicit.

#### Correct

Persist an explicit `request_type`, normalize unknown or missing values to
OpenAI-compatible, and keep provider-specific URL examples as UI/documentation
helpers instead of implementation branches.
