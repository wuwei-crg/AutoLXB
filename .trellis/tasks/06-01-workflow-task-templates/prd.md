# Workflow Task Templates

## Goal

Introduce a first-class workflow layer above the existing task execution engine. A
task template defines what to do; a workflow defines how one or more templates
are executed and when they are triggered. This lets users split a complex task
into reusable smaller tasks without configuring schedules or notification
triggers one by one.

## What I Already Know

- Existing task execution should stay largely intact. The new architecture wraps
  it instead of replacing it.
- Current model-decomposed sub-tasks are useful, but they should not be the only
  way to express multi-step work.
- Users need a dedicated frontend area to author reusable task templates.
- The frontend should have two primary management areas: templates and
  workflows. Schedule and notification behavior is configured on workflows, not
  managed through separate schedule/notification task lists.
- Workflow execution is the universal runtime model. A single template run is a
  one-step workflow internally.
- The current task run record remains route-learning infrastructure; workflow
  run history should not become a separate persisted history system.

## Requirements

### Task Templates

- Add a first-class `TaskTemplate` entity.
- A task template defines the reusable execution unit:
  - `template_id`
  - `name`
  - `description` (current `user_task` equivalent)
  - `package_name` (optional)
  - `start_page`
  - `map_path`
  - `user_playbook`
  - `record_enabled`
  - `task_map_mode` (`off|manual|ai`)
  - `route_id`
  - `decompose_enabled`
  - `created_at_ms`
  - `updated_at_ms`
- A template owns exactly one primary route.
- Template edits are global. Any workflow step referencing that template uses the
  latest template configuration.
- New templates default `decompose_enabled=false` so the old `TASK_DECOMPOSE`
  stage is skipped unless explicitly enabled.
- Deleting a template referenced by any workflow is forbidden in the first
  version.

### Workflows

- Add a first-class `Workflow` entity.
- A workflow contains one or more ordered steps, each referencing a
  `TaskTemplate`.
- A workflow owns its optional trigger configuration. There is no separate
  persisted trigger entity in the first version.
- Workflow records include `trigger_type`, `trigger_enabled`, and
  `trigger_config`.
- Workflow itself has no enabled/disabled state; it is a static orchestration
  definition.
- `trigger_enabled` controls only automatic triggering.
- A workflow defaults to no automatic trigger.
- A workflow can have zero or one automatic trigger condition in the first
  version:
  - none
  - `schedule`
  - `notification`
- Users may switch a workflow trigger condition among none, schedule, and
  notification, but only one trigger condition may exist at a time.
- Switching from schedule to notification, or notification to schedule, replaces
  the previous trigger config.
- Workflow steps cannot reference other workflows. Nested workflows are out of
  scope.
- Workflow steps cannot override template configuration in the first version.
- Removing a workflow step only removes that workflow's reference to the
  template. It does not delete the template.
- A workflow has a failure policy:
  - `stop_on_failure` (default)
  - `continue_on_failure`
- Workflow status model:
  - `pending`
  - `running`
  - `success`
  - `failed`
  - `partial_failed`
  - `cancelled`
- Step status model:
  - `pending`
  - `running`
  - `success`
  - `failed`
  - `skipped`
  - `cancelled`
- Deleting a running workflow is forbidden. The user must cancel it first.
- Deleting a workflow does not delete templates or task routes.

### Trigger Semantics

- Workflow and trigger are unified at the product and persistence level.
- Trigger configuration is optional on a workflow.
- Every workflow can be run manually regardless of trigger condition.
- The trigger condition only controls automatic execution.
- Immediate/manual execution directly starts the workflow.
- Automatic execution requires `trigger_enabled=true`.
- A scheduled workflow stores schedule config inside the workflow.
- A notification-triggered workflow stores notification matching config inside
  the workflow.
- Manual execution is an action on a template or workflow, not a separate
  trigger type/list in the first version.
- Ordinary "run this template now" does not create a visible workflow entry.
  Internally it is wrapped as a one-step workflow run.
- Creating a scheduled or notification-triggered action from a template creates a
  visible one-step workflow with the chosen trigger condition.

### Execution Semantics

- All execution goes through a `WorkflowRun`.
- A single template run is internally a one-step `WorkflowRun`.
- A workflow step starts an independent existing task run.
- Workflow execution creates a parent run and child task runs:
  - `workflow_run_id`
  - step 1 -> `task_id`
  - step 2 -> `task_id`
  - etc.
- Existing task execution remains responsible for app-level execution and app
  cleanup per step.
- Device-level preparation and cleanup move to the workflow parent:
  - wake/unlock/device readiness at workflow start
  - final auto-lock at workflow end
- Child task runs skip device-level start/end work, but keep app resolve, app
  enter, script/vision execution, and app cleanup.
- Cancelling always targets the workflow. Cancelling a one-template run is
  cancelling its one-step workflow.
- Workflow cancellation cancels the current child task, skips remaining steps,
  and performs workflow-level cleanup.
- Workflow active state is kept in memory only. There is no persisted
  `workflow_runs.json` in the first version.
- Workflow lifecycle and step lifecycle should be trace events:
  - `workflow_run_started`
  - `workflow_step_started`
  - `workflow_step_finished`
  - `workflow_run_finished`
  - `workflow_run_cancelled`
- Existing task run records remain the source for task route learning and route
  persistence.

### Persistence

- Add `task_templates.json`.
- Add `workflows.json`.
- Do not add a standalone `triggers.json`.
- Legacy schedule and notification storage should be migrated into templates and
  workflows.
- Migration creates:
  - one `TaskTemplate` for the old task/action body
  - one visible `Workflow` for the old schedule or notification trigger
- Legacy ids are preserved as metadata such as `legacy_id`, but new ids should
  use the new template/workflow id spaces.
- Routes from legacy schedule/notification tasks migrate to `TaskTemplate`, not
  to `Workflow`.
- Legacy notification rule migration splits the existing rule:
  - notification matching fields move to workflow `trigger_config`
  - existing `action` task fields move to the generated `TaskTemplate`
- Notification trigger config should preserve existing rule fields:
  - `priority`
  - `package_mode`
  - `package_list`
  - `text_mode`
  - `title_pattern`
  - `body_pattern`
  - `llm_condition_enabled`
  - `llm_condition`
  - `llm_yes_token`
  - `llm_no_token`
  - `llm_timeout_ms`
  - `task_rewrite_enabled`
  - `task_rewrite_instruction`
  - `task_rewrite_timeout_ms`
  - `task_rewrite_fail_policy`
  - `cooldown_ms`
  - `active_time_start`
  - `active_time_end`
  - `stop_after_matched`

### Backend API

- Add a clear new command surface for task templates and workflows instead of
  overloading existing schedule, notification, or task-map commands.
- All new template/workflow command payloads and responses use JSON.
- Standard success response shape is `{"ok":true,...}`.
- Standard failure response shape is `{"ok":false,"err":"..."}`.
- List responses use `{"ok":true,"items":[...]}`.
- Template commands should cover list/get/save/delete.
- Workflow commands should cover list/get/save/delete.
- Runtime commands should cover run/cancel/status for workflows.
- Existing schedule and notification commands may remain temporarily for legacy
  compatibility and migration, but new frontend flows should call the new
  workflow/template commands.

### Frontend UX

- Keep the existing Quick task / Direct task entry as an ad-hoc temporary run.
- Quick task does not create a saved template or visible workflow.
- Internally, Quick task may still execute through a temporary one-step workflow
  run for runtime consistency.
- Task tab home shows these primary entries in the first version:
  - Quick task
  - Templates
  - Workflows
  - Recent Runs
- Add a dedicated frontend area for authoring task templates.
- Add a dedicated frontend area for authoring workflows.
- Template list sorts by `updated_at_ms` descending in the first version.
- Remove separate scheduled-task and notification-triggered-task list concepts.
- Scheduled and notification behavior is configured from a workflow as an
  optional trigger condition.
- Migrated legacy scheduled and notification-triggered tasks appear only in the
  workflow list, not in old dedicated lists.
- Workflow list sorts by `updated_at_ms` descending in the first version.
- Route/task-map editing belongs to the template detail page.
- Workflow steps may link to a template detail page, but route edits are not
  performed inline on workflow steps.
- If a user starts from a template and adds a schedule/notification, the frontend
  creates a visible one-step workflow.
- The internal default one-step workflow used for immediate template execution is
  not displayed in the frontend workflow list.

## Acceptance Criteria

- [ ] Users can create, edit, and list task templates.
- [ ] A task template can own one primary route and can be used by multiple
      workflows.
- [ ] New templates default `decompose_enabled=false`, and task execution skips
      `TASK_DECOMPOSE` when disabled.
- [ ] Users can create workflows that default to no automatic trigger.
- [ ] Users can add one schedule or notification trigger condition to a
      workflow.
- [ ] Users can switch a workflow trigger condition between none, schedule, and
      notification while preserving workflow steps.
- [ ] Removing a workflow step does not delete the referenced task template.
- [ ] Users can manually run any workflow, including workflows with no automatic
      trigger condition.
- [ ] Users can pause automatic triggering without deleting the saved trigger
      condition.
- [ ] Workflow steps execute existing task templates in order.
- [ ] `stop_on_failure` stops after the first failed step and marks later steps
      skipped.
- [ ] `continue_on_failure` continues after failed steps and ends as
      `partial_failed` when any step failed.
- [ ] Cancelling a workflow cancels the current child task and prevents later
      steps from starting.
- [ ] Workflow-level device unlock/lock happens once per workflow run, while
      app-level cleanup still happens per step.
- [ ] Existing schedule and notification persisted data migrate into templates
      and workflows without dropping legacy ids.
- [ ] Workflow lifecycle emits trace events sufficient to inspect execution
      without a persisted workflow run history.
- [ ] New template/workflow command handlers use JSON payloads and JSON
      responses with consistent `ok` / `err` handling.
- [ ] Frontend exposes Templates and Workflows as the primary lists and does not
      keep separate scheduled-task or notification-triggered-task lists.
- [ ] Quick task remains available as a temporary, unsaved run path.
- [ ] Task tab home exposes Quick task, Templates, Workflows, and Recent Runs,
      and no longer exposes Schedules or Notification Triggers as separate
      entries.
- [ ] Legacy schedule/notification items are visible as workflows after
      migration.
- [ ] Route editing is available from template detail and not duplicated as a
      workflow-step editor.
- [ ] Users can import a portable bundle from the Task tab home.
- [ ] Portable import detects workflow bundle, template bundle, legacy
      schedule/notification portable files, and legacy route assets without
      asking the user to choose the type first.
- [ ] Portable workflow/template imports generate new local ids and never
      overwrite, merge, rename, or reject objects because of name conflicts.
- [ ] Portable workflow import is atomic and fails without writing partial data
      when references or embedded assets are invalid.
- [ ] Imported workflows start with no trigger and automatic triggering disabled.
- [ ] Users can export a workflow from the workflow edit page.
- [ ] Users can export a template from the template edit page.
- [ ] Import/export visible copy uses Chinese-first wording and no longer uses
      the old "Portable Task" concept for new workflow/template flows.

## Definition of Done

- Tests added or updated for template persistence, workflow execution, migration,
  failure policy, cancellation, and `decompose_enabled`.
- App/core parser tests updated for new API response shapes.
- Backend JVM tests pass.
- App JVM tests pass.
- Trace events verified for workflow start, step start/end, cancellation, and
  workflow finish.
- Legacy data migration has regression coverage.
- Trellis specs updated if implementation establishes durable new conventions.

## Out of Scope

- Parameter passing between tasks.
- Task output schemas.
- AI-generated workflow/template decomposition.
- Nested workflows.
- Multiple automatic trigger conditions per workflow.
- Workflow step overrides of template configuration.
- Persisted workflow run history.
- Advanced branching, loops, retries, or conditional execution.
- Multiple routes per template.
- Deleting referenced templates.
- Template/workflow archive states.
- Workflow list trigger-type filters.

## Technical Approach

- Keep the existing task engine as the child execution unit.
- Add template and workflow persistence as the new authoring layer.
- Move optional trigger configuration into workflow records instead of creating a
  separate trigger entity or separate schedule/notification task lists.
- Add explicit command handlers for template CRUD, workflow CRUD, workflow run,
  workflow cancel, and workflow active-status query.
- Keep the new command contract JSON-only for both request and response bodies.
- Add a workflow runner that:
  - prepares the device once
  - executes each workflow step as an existing task run
  - applies failure policy
  - propagates cancellation
  - performs final device cleanup once
  - emits trace events for history/debugging
- Add a template-to-task adapter so a `TaskTemplate` can be converted into the
  current task execution request.
- Add a migration path from legacy schedule/notification data into
  `task_templates.json` and `workflows.json`.
- Develop in stages, but expose the feature as one complete migration/cutover to
  users.

## Implementation Plan

1. Add backend template/workflow data models, stores, and legacy migration from
   schedules/notification rules.
2. Add workflow runner with parent device preparation/cleanup, step execution,
   failure policy, cancellation propagation, and trace events.
3. Add JSON command API for template/workflow CRUD plus workflow run/cancel/status.
4. Add frontend Templates and Workflows screens and parser/ViewModel support.
5. Replace old Schedules and Notification Triggers entries with Workflow-based
   flows, while keeping Quick task and Recent Runs.

## Decision Log

- Use a top-level workflow feature instead of changing existing sub-task
  semantics directly.
- Treat existing task configuration, minus trigger timing, as reusable
  `TaskTemplate`.
- Use workflows for both single-template and multi-template execution.
- Make optional trigger configuration part of workflow, not a separate persisted
  entity.
- Replace separate schedule/notification task lists with a single workflow list
  and trigger-condition editing.
- Keep route/task-map editing on the template detail surface because routes are
  template-owned assets.
- Add a new explicit backend command surface for templates and workflows instead
  of reusing legacy schedule/notification commands for new UI flows.
- Default template decomposition off to prevent unwanted model splitting.
- Keep route ownership on template.
- Keep workflow run history out of persistence; use active memory state and
  trace.
- Existing portable import/export is route-centric and legacy task-centric. The
  new template/workflow architecture needs a separate import/export decision
  because route assets now belong to templates and workflows can reference
  multiple templates.
- First-version portable workflow export uses workflow-with-embedded-templates
  as the primary object model.
- Portable workflow bundle uses exported object ids for internal references
  inside the file; import regenerates local ids and rewrites references.
- Portable workflow bundle uses both `schema` and `version`: `schema` identifies
  the portable object type, and `version` identifies the schema revision for
  parser adaptation.

## Import And Export Discussion

### Current Backend Facts

- Existing export uses `CMD_CORTEX_TASK_MAP` with `action=export_portable`.
- Existing portable route schema is `task_route_asset.v1`.
- Existing portable route export contains task route behavior and segments, not
  schedule metadata.
- Existing app-side export wraps the route bundle with old task config metadata
  for legacy `schedule` and `notify_trigger` import paths.
- Existing import only supports legacy `schedule` and `notify_trigger` task
  types from the app-side portable wrapper.
- New `TaskTemplate` owns the primary route, so route-only export is no longer
  the best top-level user-facing export object.
- New `Workflow` can reference multiple templates, so workflow export must
  decide whether it also carries referenced templates.

### Backend Question Queue

- Decide the first-version portable object model:
  - template-only export
  - workflow export with embedded templates: selected for the first version
  - route-only legacy compatibility
  - combined package format

### Confirmed Import/Export Decisions

- The first-version primary portable format exports one workflow plus all
  templates it references.
- Embedded templates include their primary routes so imported workflows are not
  broken by missing local template references.
- Route-only export remains a legacy compatibility concept, not the new primary
  user-facing export object.
- Import creates local ids for every imported workflow and template. It does not
  reuse portable object ids as local ids.
- Imported ids are regenerated in the local id/hash space so repeated imports do
  not overwrite existing user data.
- Portable workflow/template import does not preserve original object ids as
  source metadata. The portable file expresses reusable content, not provenance.
- Imported local objects do not store `imported_from_*` or original-id metadata
  in the first version.
- Inside the portable file, workflow steps reference embedded templates by the
  exported template id. The importer builds an in-memory exported-id to new-local-id
  mapping, then rewrites workflow step references before saving.
- No extra portable-local reference field such as `ref`, array index reference,
  or template-name reference is added in the first version.
- The first-version portable workflow schema is `workflow_bundle` with
  `version=1` rather than encoding the full version only in the schema string.
- Import parsing should be implemented as a dedicated portable workflow parser
  module with version-specific adapters instead of a one-off inline parser.
- Future workflow format changes such as loop logic, conditional branches, retry
  policy, or richer step graph semantics should be handled by adding new
  version adapters.
- Portable workflow export does not include workflow trigger configuration.
- Imported portable workflows always start with `trigger_type=none` and
  `trigger_enabled=false`.
- Portable import/export transfers reusable workflow orchestration content, not
  automatic execution conditions.
- Legacy portable `schedule` and `notify_trigger` files remain importable.
- Legacy portable import is handled by a legacy adapter that converts the old
  task/route content into one imported template plus one imported workflow with
  no trigger.
- The portable import module dispatches by `schema`/`version` for new formats,
  and by legacy wrapper/schema/type detection for old formats.
- Each supported portable version or legacy format should have its own adapter
  module that outputs the same internal import result shape before persistence.
- Portable workflow import is atomic. If the workflow, any embedded template,
  or any embedded route asset fails validation, the importer writes nothing.
- Partial portable import is not supported in the first version.
- Template-only portable export/import is supported as a secondary format.
- Template-only portable export uses `schema="task_template_bundle"` and
  `version=1`.
- Template-only import creates a local template and its route asset, but does
  not automatically create a workflow.
- Workflow bundle remains the primary user-facing portable format for workflow
  export.
- Add a unified portable command surface for new import/export behavior, for
  example `CMD_CORTEX_PORTABLE` with JSON actions such as `export_workflow`,
  `export_template`, and `import`.
- New portable import/export should not be added to the legacy
  `CMD_CORTEX_TASK_MAP export_portable/import_portable` command path.
- Legacy task-map portable commands remain only for route compatibility.
- The new importer accepts pure legacy `task_route_asset.v1` route bundles.
- A pure route bundle imports into one local template with that route asset and
  does not create a workflow.
- For pure route imports, template name/description are derived from portable
  `task_info` when available.
- Templates without a route are exportable.
- A template bundle may have an empty or missing route payload.
- A workflow bundle may embed templates that do not have route assets.
- Importing a template without a route still creates the template; it simply has
  no reusable route until the user records or learns one locally.
- Workflow bundle imports require every workflow step template reference to
  resolve to an embedded template in the same bundle.
- If any workflow step references a missing embedded template, the whole import
  fails atomically.
- Workflow bundle import must not bind steps to local templates by matching ids
  or names.
- Workflow bundle imports reject embedded templates that are not referenced by
  any workflow step.
- Importing unreferenced extra templates from a workflow bundle is out of scope;
  future multi-asset packages should use a separate schema if needed.
- Import allows duplicate workflow/template names.
- Import never overwrites, renames, merges, or rejects objects because of name
  conflicts.
- Newly generated local ids are the only identity boundary for imported
  workflows and templates.

## Product And Frontend Constraints

This section records user-visible constraints and interaction decisions. It is
not a dump of backend DTOs. Internal ids, persisted file paths, protocol field
names, and timestamp storage formats must stay below the UI/state translation
layer unless explicitly called out.

### Language And Localization

- Chinese is the primary language for the first-version user experience.
- All user-visible labels, buttons, empty states, helper text, and error
  messages must go through the app i18n layer.
- Backend field names, enum values, or command payload keys must never be shown
  directly to the user.
- New template/workflow surfaces must not ship with mixed protocol English and
  partial Chinese copy.

### User Input Red Lines

- Users must not edit internal ids such as `template_id`, `workflow_id`,
  `step_id`, or route ids.
- Users must not manage file-system paths such as `map_path`.
- Users must not enter epoch/timestamp values directly for schedule/workflow
  trigger time.
- Users must not manually type app package names for normal template/workflow
  authoring flows.
- Backend DTOs must not be used directly as Compose form state for template or
  workflow editing.

### Task Tab Information Architecture

- The Task tab primary entry points remain:
  - Quick task
  - Templates
  - Workflows
  - Recent Runs
- Do not preserve separate first-class Schedules or Notification Triggers list
  entry points in the new flow.
- Migrated legacy schedule/notification items appear in the Workflows area.
- This is an existing product migration, not a greenfield redesign. New
  template/workflow surfaces should inherit existing page responsibilities and
  familiar interaction patterns where possible instead of introducing extra
  hierarchy by default.

### Template Authoring

- Users edit business fields, not storage/runtime fields.
- Template create/edit should focus on user-facing task content:
  - name
  - description
  - target app (optional)
  - user playbook / instructions (optional)
  - execution-related toggles that users can understand
- Route ownership stays on template.
- The current route-entry interaction should be preserved instead of being
  redesigned during this migration unless a later decision explicitly changes
  it.
- Route is treated as a user-facing asset/ability, not a file path that users
  place into folders manually.
- First-version template edit page stays intentionally minimal:
  - name
  - description
  - target app
  - user playbook / instructions
  - decompose enabled
- Do not expose route ids, map paths, or similar storage/runtime internals in
  the template edit form.
- Template target app must use the existing installed-app snapshot picker
  interaction instead of a raw package-name text field.
- Template target app selection reads the local installed-app snapshot and lets
  the user search by app label or package name.
- Template target app selection should reuse the existing `PackageSelectField`
  and `PackagePickerDialog` pattern where practical.
- Template target app is the new owner of the old "schedule open app" and
  "notification action open app" semantics.
- Do not duplicate old schedule/notification action app fields in workflow
  trigger forms.
- Template list items open directly into the working edit page.
- Do not add a separate read-only template detail layer in the first version.
- The template page combines editable task fields with the existing route entry
  pattern.

### Workflow Authoring

- Workflow edit screens should show the workflow's own current structure, not
  dump the entire template inventory into the main editing surface.
- Workflow step editing is based on explicit add/remove/reorder interactions.
- The workflow main editor should display only already-added steps plus actions
  to manage them.
- Step rows must not expose internal ids.
- Tapping a workflow step row navigates to the referenced template edit page.
- First-version step reordering uses explicit up/down controls instead of drag
  and drop.

### Workflow Step Picker

- Adding a workflow step uses the interaction:
  `Add step -> full-screen template picker -> return to workflow editor`.
- Do not present all templates inline in the workflow editor as a long raw list
  of selectable items.
- The picker is a full-screen page in the first version, not a bottom sheet or
  small modal.
- After selection, the user returns to the workflow editor and sees the chosen
  steps in sequence.

### Workflow List Density

- The workflow list is for scanning, not for reading full details.
- First-version workflow list items should stay compact and avoid large cards.
- Workflow list items should show:
  - name
  - trigger enabled state
- A very short trigger summary may be shown only if it does not noticeably
  increase row height, for example:
  - no trigger
  - daily 09:30
  - once 06-02 09:30
- Do not show long descriptions or expanded step summaries in the workflow list.

### Template List Density

- The template list should also stay compact.
- First-version template list items should show:
  - name
  - target app summary when available
  - lightweight route state such as route available / no route
- Do not show internal route ids, file paths, or oversized descriptive cards in
  the template list.

### Template List Actions

- Compact template list items should prioritize navigation over inline actions.
- First-version template list items should support:
  - run now / trigger immediately
  - enter detail/edit
  - route state display
  - open route editor
  - delete
- Keep template list actions compact; do not use large cards or overflow menus
  for first-version template actions.

### Trigger Editing

- Trigger belongs to workflow.
- Workflow itself does not have enable/disable state.
- `enable` in the UI means trigger automatic execution enablement only.
- Manual run is always available regardless of trigger state.
- First version allows zero or one trigger condition on a workflow.
- First-version workflow trigger configuration must preserve existing user-facing
  trigger capability instead of silently dropping it during migration.
- Workflow trigger types supported in the first-version frontend are:
  - none
  - schedule
  - notification
- Trigger time editing must use user-friendly date/time and repeat controls, not
  timestamp text input.
- Trigger enable/disable is controlled from the workflow list, not duplicated in
  the workflow edit page.
- Saving workflow edits must not automatically enable the trigger.
- Saving workflow edits and enabling a trigger are separate actions with
  separate validation.
- If a one-shot trigger time is already in the past, saving the workflow should
  still succeed; the invalidity should be surfaced when the user tries to enable
  the trigger.
- Saving must never fail silently and must never discard user edits without an
  explicit error message.
- Trigger-type switching happens directly in the workflow edit page through a
  visible type selector rather than a second navigation layer.

### Inherit Existing Schedule/Notification Form Design

- This migration must reuse proven existing controls and interaction patterns
  where they still match the new template/workflow ownership model.
- Do not copy the old schedule and notification forms wholesale into workflow
  editing.
- Old task/action fields move to `TaskTemplate`; workflow trigger forms only
  edit trigger conditions.
- Existing schedule trigger controls to preserve:
  - date picker
  - time picker
  - repeat mode selector
  - weekday picker for weekly repeat
- The old schedule "open app" field is represented by the selected workflow
  step templates' target apps, not by a separate schedule-trigger app field.
- Existing notification editor structure to preserve in the workflow trigger
  section:
  - trigger conditions
- Existing notification capabilities that must remain editable after migration:
  - package/app targeting
  - title/body matching
  - cooldown
  - active time window
  - LLM condition settings
- Notification triggered action task description / app / playbook and
  recording/route-related execution preferences belong to referenced templates,
  not the notification trigger form.
- Workflow migration must not reduce existing editable schedule/notification
  capability merely because the surrounding container changes from schedule/rule
  to workflow.
- Workflow trigger app fields must use the installed-app snapshot picker:
  - notification listening app
- Workflow trigger app fields must not be raw package-name text inputs in the
  first-version UI.
- If the installed-app snapshot is empty, opening an app picker should refresh
  the snapshot using the existing `refreshInstalledAppSnapshotOnDevice()` flow.

### Workflow Editing Scope

- First-version workflow edit page stays intentionally minimal:
  - name
  - step orchestration
  - trigger configuration
- Do not overload the first-version workflow edit page with nonessential fields.
- `Add step` belongs inside the step orchestration section, not in a generic
  page-level action area.
- `Run now` is not an edit action. It belongs on the workflow list item and/or
  detail surface, not as a primary workflow-edit-page action.
- Workflow list items open directly into the working edit page.
- Do not add a separate read-only workflow detail layer in the first version.
- Workflow editing is a single-page experience rather than a wizard or
  multi-page flow.
- The page should be fully editable in place so users can edit whichever core
  section they want without changing modes or entering a separate subflow.
- First-version workflow edit page top-to-bottom structure is:
  1. name
  2. step orchestration
  3. trigger type selector
  4. matching trigger configuration form
  5. save action
- Step orchestration appears before trigger editing because workflow first
  defines what runs, then how it is triggered automatically.

### Workflow List Actions

- Compact workflow list items should support:
  - enter detail/edit
  - run now
  - trigger enable/disable toggle
- The list toggle is the trigger toggle. It must not be presented as a workflow
  enable/disable control.

### Portable Import/Export UX

- Portable import is a global action on the Task tab home.
- The global import action accepts workflow bundle, template bundle, legacy
  schedule/notification portable files, and legacy route assets.
- Users do not choose the portable type before import; the importer detects the
  format.
- After successful import, the frontend navigates to the relevant area:
  workflows for workflow imports, templates for template or route imports.
- After successful import, the frontend opens the newly imported object's edit
  page directly:
  - workflow bundle import opens the imported workflow edit page
  - template bundle or route import opens the imported template edit page
- Import/export success and failure must produce immediate system feedback
  through a Toast/dialog-style popup. Users must not need to inspect a tab,
  status panel, or system message stream to know whether import/export
  succeeded or where the export was written.
- First-version export actions live on the corresponding edit page as secondary
  actions:
  - template edit page: export template
  - workflow edit page: export workflow
- First-version export actions do not appear as always-visible compact list row
  buttons.
- Do not introduce a new overflow/more-menu interaction pattern solely for
  portable export in the first version.
- User-visible portable copy:
  - Task home import action: `导入便携包`
  - Template edit export action: `导出模板`
  - Workflow edit export action: `导出工作流`
  - Import success: `导入成功`
  - Export success: `导出成功`
  - Import failure: `导入失败：...`
  - Export failure: `导出失败：...`
- New workflow/template portable UI copy must not use the old "Portable Task" /
  "便携任务" wording.
- The old portable-task frontend status panel and old schedule/notification task
  frontend surfaces are removed from the first-version UI.

### Installed App Picker Reuse

- Preserve the old installed-app selection UX for new template/workflow forms.
- The app reads the installed-app snapshot from core and stores it in
  `installedAppList`.
- App selection uses a searchable picker over the installed-app snapshot, not
  free-form package-name entry.
- Search matches both app label and package name.
- Selected values are still persisted as package names below the UI layer, but
  users interact with app labels and searchable app rows.

## Confirmed Decisions From Discussion

- Chinese-first user-visible copy is required for new template/workflow flows.
- Internal ids, paths, and timestamp input are forbidden in user-facing forms.
- Workflow step authoring uses `Add step -> full-screen template picker`.
- Workflow edit main area shows current steps only; it does not inline every
  template as a raw selection list.
- Template route entry should keep the current interaction unless explicitly
  changed later.
- Workflow list items stay compact and prioritize only name plus trigger state.
- Template list items stay compact and prioritize name, app, and route state.
- Workflow edit page scope is reduced to name, trigger config, and step
  orchestration only.
- Trigger toggle lives on the workflow list; workflow edit save does not imply
  trigger enablement.
- Saving with a past one-shot trigger must not silently fail or discard edits.
- Template edit page scope is reduced to name, description, target app, playbook,
  and decompose toggle only.
- Template list items prioritize compact navigation and route state, not inline
  action overload.
- Template and workflow pages follow the existing project pattern of
  `list -> directly edit`, without inserting a separate read-only detail layer.
- Workflow edit page is a single-page editor ordered as name -> steps ->
  trigger type -> trigger form -> save.

## Open Questions

- Exact list item content and sorting emphasis for the workflow list.
- Exact list item content and sorting emphasis for the template list.
- Exact first-version template create/edit field grouping and advanced-settings
  split.
- Exact workflow create/edit page structure beyond the confirmed step-picker
  flow.
- Exact trigger editor layout and schedule/date-time control details.
- Exact row-level actions on compact workflow/template list items.
- Exact empty states and onboarding hints after removing old schedule/notify
  list entry points.

## Technical Notes

- Existing `CortexFsmEngine.SubTask` already has fields for model-decomposed
  sub-tasks, but this feature should sit above the task engine.
- Existing `TaskMap.Segment` stores sub-task route metadata. New template-owned
  route persistence should avoid breaking this route-learning behavior.
- Existing schedule functionality recently added immediate schedule triggering;
  migration should preserve that user-visible capability through workflows.
- Existing task runs are route-learning artifacts and should remain focused on
  route persistence rather than workflow history.
- Existing notification config fields are defined in
  `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/notify/NotificationTriggerRule.java`.
