# Frontend Type Safety

The app uses Kotlin types, data classes, and explicit parser functions. Runtime
payloads from `lxb-core` are still JSON/binary, so parser boundaries matter.

## Type Organization

- Shared UI/backend snapshots belong in `model/`.
- Request/form validation types belong near their use case, as
  `ScheduleFormInput` and `ScheduleDraft` do in `schedule/ScheduleUseCase.kt`.
- Parser result types can live next to the parser when only that parser uses
  them, as `TaskSubmitParsed`, `ScheduleTriggerParsed`, and
  `SystemControlParsed` do in `CoreApiParser.kt`.
- Nested UI-only state classes can remain inside `MainViewModel` or
  `MainActivity` when they are not shared.

Reference files:
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/core/CoreApiParser.kt`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/schedule/ScheduleUseCase.kt`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/model/TaskModels.kt`

## Runtime Validation

Use `org.json` parsing with defensive accessors:

- `runCatching { JSONObject(text) }.getOrNull()` for untrusted backend
  responses.
- `optString`, `optBoolean`, `optLong`, `optJSONArray`, and `optJSONObject`
  with defaults.
- Deduplicate lists with keyed maps when ids or package names should be unique.
- Trim and normalize user input before building payloads.

For binary responses, check minimum length and cap claimed lengths to available
bytes, following `CoreApiParser.parseInstalledApps` and
`parseSystemControl`.

## Result Handling

Use `Result<T>` for validation/build helpers when the ViewModel needs a clear
success/failure branch. Throw `IllegalArgumentException` inside `runCatching`
for invalid form input, as in `ScheduleUseCase.buildDraft`.

## Common Mistakes

- Do not use nullable maps or raw `JSONObject` values in composables when a
  data class snapshot exists.
- Do not assume backend `ok=true`; parser tests cover failed and invalid
  responses.
- Do not parse numeric form fields with `toInt()` / `toLong()` directly on
  user input. Use `toIntOrNull()` / `toLongOrNull()` and return a message.
