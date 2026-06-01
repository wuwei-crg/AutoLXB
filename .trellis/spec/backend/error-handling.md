# Backend Error Handling

Backend code runs inside an on-device daemon. The user-facing app expects
commands to return an ACK payload even when a command fails, so command
boundaries catch exceptions and translate them into compact error responses or
error ACKs.

## Command Boundaries

- `CommandDispatcher.dispatch` catches unexpected exceptions, records the
  circuit breaker exception, logs the command id, and returns an ACK with a
  failure status byte.
- `CortexFacade` command handlers catch exceptions locally and return JSON like
  `{"ok":false,"err":"..."}` through `err(...)`.
- `FrameCodec` throws checked `ProtocolException` and `CRCException`; callers
  handle them at the TCP client loop instead of crashing the daemon.
- App-side command callers convert failed payloads into UI strings in
  `CoreApiParser`.

Reference files:
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/dispatcher/CommandDispatcher.java`
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFacade.java`
- `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/protocol/FrameCodec.java`
- `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/core/CoreApiParser.kt`

## Validation

Validate payloads close to the boundary:

- Binary payloads should check length before reading `ByteBuffer`.
- JSON payloads should use `Json.parseObject` and normalize optional values
  with helper methods such as `stringOrEmpty`, `toInt`, `toLong`, and `toBool`.
- Required command fields should return `err("field is required")` instead of
  causing a null pointer or class cast failure.

Use `IllegalArgumentException` for invalid local model construction or parser
input that is covered by tests. Use `IllegalStateException` when a required
runtime condition is missing, such as an unavailable map file or failed shell
sync.

## Best-Effort Operations

Some device operations are intentionally best effort and should not break task
execution:

- Trace push in `TraceLogger.pushIfNeeded` catches and ignores socket failures.
- Persistence load failures often return empty state so the daemon can start.
- UI automation fallbacks log failures and try shell alternatives.

When swallowing an exception, keep it limited to recovery or optional
telemetry. Do not silently ignore validation errors in command handlers that
need to report failure to the app.

## Common Mistakes

- Do not let exceptions escape from `CortexFacade` command handlers.
- Do not return raw stack traces to app-facing JSON responses.
- Do not add retry loops in the dispatcher; transport retry/dedup is explicitly
  outside `CommandDispatcher`.
- Do not parse a failed app-side response as success. Tests in
  `CoreApiParserTest` expect invalid or failed responses to produce empty ids
  and user-readable failure messages.
