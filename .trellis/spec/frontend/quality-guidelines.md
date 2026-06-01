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
