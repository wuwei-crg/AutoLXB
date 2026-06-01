# Backend Directory Structure

In this repository, "backend" means the on-device Java runtime under
`android/LXB-Ignition/lxb-core`. It is packaged as `lxb-core.jar`, converted to
`lxb-core-dex.jar`, and loaded by the Android app as a local control daemon.

## Module Boundaries

- `com.lxb.server.Main` wires the daemon: hidden API bypass, UI automation,
  perception, execution, circuit breaker, dispatcher, and TCP server.
- `network/` owns socket I/O only. `TcpServer` reads and writes exactly one
  framed LXB-Link message at a time.
- `protocol/` owns frame encoding, command ids, CRC validation, and protocol
  compatibility. Keep `CommandIds` aligned with app-side calls.
- `dispatcher/` maps command ids to engine/facade methods. It should not keep
  session state or transport retry state.
- `execution/` owns device actions: tap, swipe, text input, app launch, system
  control, unlock, and shell-backed operations.
- `perception/` owns screen state, hierarchy dumps, node lookup, screenshots,
  and activity inspection.
- `system/` owns Android internals: `UiAutomationWrapper` and
  `HiddenApiBypass`.
- `cortex/` owns task execution, LLM calls, task memory, schedules, Trace,
  route replay, and map/locator logic.
- `cortex/taskmap/`, `cortex/notify/`, `cortex/dump/`, `cortex/fsm/`, and
  `cortex/json/` are feature subpackages, not generic utility folders.

Reference files:
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/Main.java`
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/dispatcher/CommandDispatcher.java`
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/protocol/FrameCodec.java`
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFacade.java`

## Adding Backend Features

For a new daemon command:

1. Add the byte constant to `CommandIds` in the correct command range.
2. Add app-side usage through `LocalLinkClient` if the APK needs to call it.
3. Add one dispatcher case in `CommandDispatcher`.
4. Put the implementation in the owning engine or facade, not in the
   dispatcher.
5. Return ACK payloads using the existing binary or JSON response style for
   that command family.
6. Add tests around parsing, protocol, persistence, scheduling, or taskmap
   behavior where the change can be exercised on the JVM.

For new Cortex data models, follow `TaskMap`, `TaskRouteRecord`, and
`NotificationTriggerRule`: plain Java classes with explicit normalization,
`toMap` or `fromMap` methods, and no external JSON dependency.

## Naming

- Java package names stay under `com.lxb.server`.
- Handler methods use `handleXxx` when they are command entry points.
- Protocol constants use `CMD_...`.
- Runtime trace event names use lowercase snake_case, for example
  `fsm_state_enter` and `map_set_err`.
- Persisted JSON field names use snake_case.

Avoid creating cross-cutting helper packages until there is a repeated local
pattern. Prefer colocating helpers with the feature package, as
`cortex/json/Json.java` and `cortex/taskmap/*` already do.
