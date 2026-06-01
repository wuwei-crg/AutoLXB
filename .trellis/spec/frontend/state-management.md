# Frontend State Management

The app uses `AndroidViewModel` plus Kotlin `StateFlow`. There is no Redux,
Room, or external app state library.

## State Categories

- `MainViewModel` owns app-wide UI state and persisted preferences.
- Private mutable flows use `_name = MutableStateFlow(...)` with public
  `StateFlow` via `.asStateFlow()` when callers should not mutate the value.
- Form fields that the UI edits directly are public `MutableStateFlow`s in
  `MainViewModel`, matching the current app style.
- Immutable UI snapshots live in `model/` as Kotlin `data class` values.
- Small local UI state, such as selected tab/page/dialog visibility, lives in
  composables with `rememberSaveable`.

Reference files:
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainViewModel.kt`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/model/TaskModels.kt`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/model/StatusModels.kt`

## Persistence

Simple settings are stored in `SharedPreferences` with keys in
`MainViewModel.Companion`. Normalize values when reading or writing:
`normalizePortString`, `normalizeUiLang`, `normalizeMapSource`,
`normalizeTouchMode`, `normalizeTaskDndMode`, and related helpers show the
local pattern.

File-backed app state goes through `AppStatePaths` and feature managers such
as `MapSyncManager`. Keep path creation centralized there.

## Backend State

Backend/server state is fetched through `LocalLinkClient` commands, parsed in
`CoreApiParser`, and then copied into ViewModel flows. Do not expose raw
backend JSON directly to composables.

## Common Mistakes

- Do not mutate a list stored in a `StateFlow` in place; assign a new list.
- Do not store long-lived app state only in a composable.
- Do not duplicate backend state models in UI components; extend the shared
  `model/` snapshots and parser instead.
- Do not update UI flows from an IO coroutine without switching to the main
  dispatcher when the surrounding code already follows that convention.
