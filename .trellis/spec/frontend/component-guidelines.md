# Frontend Component Guidelines

The app uses Jetpack Compose Material 3. Components are functions in
`MainActivity.kt` today, with theme primitives in `ui/theme`.

## Component Shape

Use this local component pattern:

- Page/tab composables accept `viewModel` and a `Modifier`.
- Smaller reusable components accept values and callbacks, not the whole
  ViewModel, unless they are already app-level config cards.
- Callback parameters start with `on...`, for example `onOpenConfig`,
  `onRefreshState`, and `onStartRoot`.
- Hoist local navigation/editing state with `rememberSaveable` at the tab/page
  level.
- Use `collectAsState()` only where the UI needs to observe a `StateFlow`.

Reference files:
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainActivity.kt`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/ui/theme/Theme.kt`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/ui/theme/Color.kt`

## Styling

- Use `LXBIgnitionTheme` and `MaterialTheme.colorScheme` for app colors.
- Reuse local visual helpers such as `SurfacePanel`, `StatusNotice`,
  `StatusTag`, `SettingsSectionCard`, and `PreferenceSwitchRow` instead of
  inventing new one-off card styles.
- Keep spacing in `dp` and use `Arrangement.spacedBy(...)` for stacked groups.
- Prefer Material 3 controls already used in the app: `Button`,
  `OutlinedButton`, `TextButton`, `OutlinedTextField`, `Switch`, `Checkbox`,
  `AlertDialog`, `NavigationBar`, and `TopAppBar`.

## UI Text And Localization

Visible text in `MainActivity` is commonly wrapped with `tr("...")` through
`LocalUiI18n`. When adding user-facing text near existing translated UI, wrap
the raw English string with `tr(...)` and update the localizer if needed.

Do not expose backend error dumps directly in labels. Parser and ViewModel code
usually trims response snippets with `take(160)` or `take(220)`.

## Side Effects

Use `LaunchedEffect` for UI-triggered side effects tied to state changes, such
as snackbar display, initial update check, list scrolling, or reacting to
wireless bootstrap state. Do not start coroutines directly from the body of a
composable.

## Common Mistakes

- Do not do socket I/O, file I/O, or JSON parsing inside composables.
- Do not add raw colors when a semantic app color exists in `Color.kt`.
- Do not create a second app shell or navigation system; the current app uses
  one `Scaffold` with bottom tabs.
