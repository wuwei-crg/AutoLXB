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
