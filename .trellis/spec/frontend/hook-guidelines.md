# Frontend Effect And Controller Guidelines

This is not a React project and has no custom hooks. The equivalent local
patterns are Compose state/effects, `AndroidViewModel`, and small controller
classes.

## Compose Effects

Use:

- `remember` for ephemeral values such as `SnackbarHostState`.
- `rememberSaveable` for UI navigation/editing state that should survive
  recomposition and basic recreation.
- `LaunchedEffect(key)` for work that should run when a key changes.
- `collectAsState()` for observing `StateFlow` in composables.

Reference files:
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainActivity.kt`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/core/TaskRuntimeController.kt`

## Controller Pattern

Move stateful non-UI logic out of composables and into focused helpers when it
has side effects or is testable:

- `TaskRuntimeController` manages runtime indicator state, Trace push listener,
  and foreground service actions.
- `MapOperationsController` coordinates map sync actions, result flows, config
  persistence, and user-visible messages.
- `DeviceConfigSyncer` writes config locally and optionally syncs it through
  core shell control.
- `ScheduleUseCase` validates schedule form input and builds backend payloads.

These classes receive their dependencies explicitly in constructors. Follow
that style instead of reaching into global state.

## Coroutine Rules

- Launch long-running or blocking work from `viewModelScope`.
- Use `Dispatchers.IO` for network, socket, shell, and file operations.
- Switch back to `Dispatchers.Main` before updating UI-visible flows from IO
  contexts.
- Return `Result<T>` from helpers when callers need to fold success/failure
  into UI strings.

## Common Mistakes

- Do not invent `useXxx` functions; that naming does not match this Kotlin
  codebase.
- Do not pass an Android `Context` through many UI layers when the ViewModel or
  service already owns the operation.
- Do not leave background sockets/listeners running; controllers with jobs
  should expose cleanup like `TaskRuntimeController.clear()`.
