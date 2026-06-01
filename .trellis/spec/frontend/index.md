# Frontend Development Guidelines

Frontend guidance covers the Android APK module in
`android/LXB-Ignition/app`.

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | APK module layout and where new app code belongs | Complete |
| [Component Guidelines](./component-guidelines.md) | Compose component shape, styling, localization, and side effects | Complete |
| [Effect And Controller Guidelines](./hook-guidelines.md) | Compose effects, controller classes, and coroutine rules | Complete |
| [State Management](./state-management.md) | ViewModel, StateFlow, SharedPreferences, and backend snapshots | Complete |
| [Type Safety](./type-safety.md) | Kotlin data classes, parser boundaries, and validation | Complete |
| [Quality Guidelines](./quality-guidelines.md) | App/core contract checks, tests, and forbidden patterns | Complete |

Load these specs for work touching `android/LXB-Ignition/app/**`, Compose UI,
ViewModel state, app services, local TCP client code, app-side parsers, map
sync, schedules, or foreground runtime status.
