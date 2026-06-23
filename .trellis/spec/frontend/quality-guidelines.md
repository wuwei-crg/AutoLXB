# Frontend Quality Guidelines

Frontend changes must preserve the app/core contract and keep blocking work
out of Compose.

## Required Patterns

- Use `viewModelScope` for user actions that call the daemon, network, or file
  system.
- Use `CoreClientGateway.withClient` / `LocalLinkClient` for LXB-Link commands.
- Keep parser behavior covered by JVM unit tests when response shapes change.
- Sync command ids with `com.lxb.server.protocol.CommandIds`; the app imports
  the same core constants.
- Keep foreground task status in `TaskRuntimeController` and
  `TaskRuntimeService` rather than scattering notification/wakelock logic.
- Keep model config writes in `DeviceConfigSyncer` so local app config and
  core config stay consistent.
- Keep APK runtime assets curated through `syncLxbRuntimeAssets`; do not add
  the whole `lxb-core/build/libs` directory as an asset source. The APK should
  package only `lxb-core-dex.jar` and `lxb-starter-*` runtime files.

Reference files:
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/service/LocalLinkClient.kt`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/core/DeviceConfigSyncer.kt`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/core/TaskRuntimeController.kt`
- `android/LXB-Ignition/app/src/test/java/com/example/lxb_ignition/core/CoreApiParserTest.kt`

## Forbidden Patterns

- Do not perform socket, HTTP, or file I/O in composables.
- Do not add hardcoded duplicate command ids in the app.
- Do not bypass `CoreApiParser` with ad hoc JSON parsing inside UI code.
- Do not log or display API keys, unlock PINs, or full sensitive task content.
- Do not block the main thread with `Thread.sleep`, socket reads, or Gradle-like
  process work.

## Testing

Use local JVM tests for:

- Parser success/failure cases in `CoreApiParser`.
- Trace event mapping in `TraceEventMapper`.
- Schedule validation and payload construction in `ScheduleUseCase`.
- Map sync normalization and path-safe behavior where practical.

Useful command from the Android project root:

```powershell
cd android/LXB-Ignition
./gradlew.bat :app:testDebugUnitTest
```

When a UI change only affects Compose layout, at minimum run the app unit tests
and inspect the changed screen manually on a device/emulator if available.

## Scenario: Workflow And Template Portable UI

### 1. Scope / Trigger

- Trigger: adding or changing Task tab import/export for workflows, task
  templates, or legacy portable route compatibility.
- Applies to `MainActivity` Task tab surfaces, `MainViewModel` portable actions,
  `CoreApiParser`, and app-side calls to `CMD_CORTEX_PORTABLE`.

### 2. Signatures

- Global import action: Task tab home button labeled through i18n as
  `Import Portable Bundle`.
- Template export action: template edit page secondary action
  `Export Template`.
- Workflow export action: workflow edit page secondary action
  `Export Workflow`.
- ViewModel import callback returns imported object type plus id so the UI can
  navigate to the correct edit page.
- App calls `CMD_CORTEX_PORTABLE` with JSON actions `import`,
  `export_template`, and `export_workflow`; it does not parse these responses
  inside Compose.

### 3. Contracts

- Import accepts workflow bundles, template bundles, legacy schedule/notification
  portable files, and route-only assets without asking the user to choose a
  type.
- Successful import opens the imported object's edit page directly:
  workflow imports open workflow editing, template/route imports open template
  editing.
- New workflow/template portable copy uses Chinese-first wording and must not
  show the old "Portable Task" wording in the new primary flows.
- Template target app and notification trigger listening app use the installed
  app snapshot picker; users must not type raw package names in normal
  authoring flows.
- Schedule trigger editing uses date/time/repeat controls. Epoch millis may be
  internal ViewModel state but must not be exposed as a text field.
- Workflow notification trigger forms edit match conditions only; action app,
  action playbook, recording, and route ownership belong to templates.

### 4. Validation & Error Matrix

- Invalid core port -> user-visible `导入失败` or `导出失败` message.
- Portable export response without `bundle_json` -> parser returns an export
  failure and no file is written.
- Portable import response without imported object id/type -> parser returns an
  import failure and the UI stays on the current page.
- Empty installed-app snapshot when opening a picker -> refresh installed app
  snapshot before showing selection.

### 5. Good/Base/Bad Cases

- Good: user imports a workflow bundle, sees `导入成功`, and lands on the
  workflow edit page with steps loaded.
- Base: user exports a template from the template edit page and receives a JSON
  file under the app's portable export location.
- Bad: a workflow editor displays every template inline as selectable raw rows,
  asks for `template_id`, or asks the user to type `com.example.app`.

### 6. Tests Required

- Parser tests cover portable import success and export failure without
  `bundle_json`.
- App JVM tests must compile Task tab call sites after any portable callback or
  parser shape change.
- Cross-layer changes must keep `:app:testDebugUnitTest` passing.

### 7. Wrong vs Correct

#### Wrong

Add a workflow import text field that asks users to paste schema JSON, package
names, or object ids.

#### Correct

Use Android document import for the bundle, send the raw JSON to
`CMD_CORTEX_PORTABLE`, parse the typed response in `CoreApiParser`, then update
ViewModel state and navigate to the imported edit page.
