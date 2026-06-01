# Frontend Directory Structure

In this repository, "frontend" means the Android APK module under
`android/LXB-Ignition/app`. The UI is Jetpack Compose with an
`AndroidViewModel`, local services, app-side parsers, and a TCP client that
talks to `lxb-core`.

## Module Layout

- `MainActivity.kt` contains the Compose app shell, tabs, dialogs, cards, and
  page-level composables.
- `MainViewModel.kt` owns app state, preferences, command orchestration, and
  high-level user actions.
- `core/` contains app-side business helpers: backend response parsing, device
  config sync, map operations orchestration, trace event mapping, task runtime
  controller, and app update checks.
- `service/` contains Android services and low-level communication helpers:
  `LocalLinkClient`, `CoreClientGateway`, `TaskRuntimeService`, wireless ADB
  bootstrap, and connection management.
- `model/` contains immutable UI snapshot data classes.
- `schedule/` contains schedule form validation and payload building.
- `map/` contains cloud map registry/cache/apply logic.
- `storage/` centralizes app state paths.
- `ui/theme/` contains Material 3 color, type, and theme definitions.
- `src/main/assets/` contains the packaged core jar/assets produced from
  `lxb-core`.

Reference files:
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainActivity.kt`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainViewModel.kt`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/core/CoreApiParser.kt`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/service/LocalLinkClient.kt`

## Adding Frontend Features

- Put pure validation or payload construction outside `MainActivity`; follow
  `ScheduleUseCase`.
- Put backend payload parsing in `CoreApiParser` and cover it with unit tests.
- Put long-running app/runtime coordination in a controller or service, not in
  composables.
- Add model data classes under `model/` when data is shared by ViewModel,
  parser, and UI.
- Keep `MainActivity` focused on presentation and user events. Existing file
  size is large, so new complex behavior should move into a smaller helper
  before it grows.

## Naming

- Kotlin files use PascalCase matching the primary class/object.
- Composables use PascalCase nouns, for example `ControlTab`,
  `SettingsSectionCard`, and `PackagePickerDialog`.
- State holder classes end in `UiState`, `Summary`, `Snapshot`, or `Status`
  when that matches existing model language.
- Backend command parsing functions use `parseXxx`.
- ViewModel action methods use imperative names such as `refreshTaskList`,
  `startServerWithNative`, and `syncDeviceConfigOnly`.
