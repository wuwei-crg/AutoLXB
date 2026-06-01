# Backend Persistence Guidelines

This project does not use a database, ORM, or migrations. Backend state is
stored as small JSON files on the Android device, plus task-route/map artifacts.
Do not introduce a database unless the product requirement explicitly needs
querying or transactional behavior that the current file model cannot support.

## Persistence Locations

- `CortexTaskManager` resolves task memory, schedules, and task run paths from
  system properties first, then defaults under device-local state.
- `CortexTaskPersistence` loads and saves JSON roots for task memory,
  schedules, and task runs.
- `TaskMapStore` owns task route records and task maps.
- The app-side `MapSyncManager` keeps cloud map registry/cache/active metadata
  under the app state directory from `AppStatePaths`.

Reference files:
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexTaskPersistence.java`
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexTaskManager.java`
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/taskmap/TaskMapStore.java`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/map/MapSyncManager.kt`

## JSON Shape

Persisted backend JSON uses:

- `schema_version` for root file formats, for example `schedules.v1` and
  `task_runs.v1`.
- `updated_at` or `created_at` timestamps as epoch millis in Java backend
  files.
- snake_case field names.
- `LinkedHashMap` when stable field order helps inspection or tests.

Model classes should normalize old or missing fields while reading. Examples:
`TaskMap.fromObject` accepts missing schema as `task_map.v1`, and
`CortexTaskManager` preserves backward compatibility with old schedule fields
such as `repeat_daily`.

## Atomic Writes And Recovery

For files that represent authoritative runtime state, use the existing
`CortexTaskPersistence.writeJsonAtomically` pattern: write `.tmp`, rotate the
old target to `.bak`, then rename the temp file. Load should try the primary
file first and then `.bak`.

For lightweight app cache files, `MapSyncManager.saveJson` writes formatted
JSON directly and recreates an empty root when parsing fails. Keep that behavior
for cache/registry files, but do not use it for critical daemon task state.

## Common Mistakes

- Do not add Room, SQLite, or ORM code for Cortex state without a separate
  design decision.
- Do not parse JSON with ad hoc string splitting. Use `Json` in `lxb-core` and
  `org.json` in the Android app.
- Do not remove backward-compatible fields from persisted output unless all
  readers have been migrated. `ScheduleUseCase.buildUpsertPayload` still emits
  `repeat_daily` for compatibility.
