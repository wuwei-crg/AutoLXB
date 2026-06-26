# Journal - wuwei (Part 1)

> AI development session journal
> Started: 2026-05-31

---



## Session 1: Bootstrap Trellis guidelines

**Date**: 2026-06-01
**Task**: Bootstrap Trellis guidelines
**Branch**: `master`

### Summary

Filled backend and frontend Trellis specs from real repository patterns and archived the bootstrap task.

### Main Changes

- Bootstrapped Trellis project specs from the existing Android app and lxb-core daemon.
- Defined backend guidance around lxb-core module boundaries, JSON file persistence, command error handling, console/Trace logging, and protocol quality checks.
- Defined frontend guidance around Compose app structure, component/effect patterns, StateFlow/ViewModel state, parser type safety, and app/core contract testing.
- Verified .trellis/spec has no template placeholder text and checked local spec index links.
- Archived 00-bootstrap-guidelines with --no-commit because the repository already had unrelated dirty Android code and untracked Trellis initialization files.


### Git Commits

(No commits - planning session)

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 2: Finish workflow template portable UI

**Date**: 2026-06-03
**Task**: Finish workflow template portable UI
**Branch**: `master`

### Summary

Completed workflow/template portable import-export UI cleanup, removed old schedule/notification task frontend surfaces, added template immediate run action, fixed compact template row layout, and verified app/core tests.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `78f28d8` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 3: Workflow global playbook and VISION_ACT memory cleanup

**Date**: 2026-06-05
**Task**: Workflow global playbook and VISION_ACT memory cleanup
**Branch**: `master`

### Summary

Added workflow-level playbooks across backend, portable bundles, and app editing; removed VISION_ACT carry_context and memory_write from the model prompt/output contract while preserving history compatibility.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `fba10d0` | (see git log) |
| `7f9870f` | (see git log) |
| `8cdb9df` | (see git log) |
| `a514557` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 4: Support multiple LLM request APIs

**Date**: 2026-06-07
**Task**: Support multiple LLM request APIs
**Branch**: `master`

### Summary

Added selectable LLM request types for OpenAI Chat Completions, Gemini generateContent, and Anthropic Messages; updated config sync, UI, docs, tests, and provider API quality guidance.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `81f4f9b` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 5: Unify route lookup by template id

**Date**: 2026-06-23
**Task**: Unify route lookup by template id
**Branch**: `dev`

### Summary

Unified Cortex task route storage and lookup on template_id, removed source-based route inference, added legacy route migration and tests.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `2618f29` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 6: Clean deprecated Android leftovers

**Date**: 2026-06-23
**Task**: Clean deprecated Android leftovers
**Branch**: `dev`

### Summary

Removed stale Android assets, scripts, debug entrypoints, and web_console remnants; narrowed APK runtime assets and updated trace docs and frontend specs.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `cabbd94` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 7: Unify app/core logging

**Date**: 2026-06-23
**Task**: Unify app/core logging
**Branch**: `dev`

### Summary

Unified app startup logs and core trace into one Logs/export flow, removed quick-task session logging, added redaction, tests, and specs.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `d664fc0` | (see git log) |
| `20117dc` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 8: Locator semantic fallback

**Date**: 2026-06-25
**Task**: Locator semantic fallback
**Branch**: `dev`

### Summary

Removed container_probe/tap_point construction and replay fallback. TAP routing now keeps only XML locators or semantic_tap, with locator-first replay and semantic visual fallback.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `6af653a` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 9: 模型单独配置路由

**Date**: 2026-06-25
**Task**: 模型单独配置路由
**Branch**: `dev`

### Summary

Split SCRIPT_ACTION model routing into unified and separate provider-backed routes; updated core routing, app config UI/state, tests, and spec docs.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `bf193fd` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 10: Fix template route mode persistence

**Date**: 2026-06-25
**Task**: Fix template route mode persistence
**Branch**: `dev`

### Summary

Added a template-level route toggle in the Android app, persisted task_map_mode on template save, hardened backend template saves to preserve omitted fields during partial updates, and added regression coverage for route mode persistence.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `68fec0b` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 11: Fix script-act task-map visual JSON parsing

**Date**: 2026-06-25
**Task**: Fix script-act task-map visual JSON parsing
**Branch**: `dev`

### Summary

Fixed script-act task-map visual resolver JSON parsing by accepting fenced or text-wrapped JSON, added resolver and TraceLogger regression tests, and documented the backend LLM JSON parsing convention.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `f7383d1` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 12: Script act locator replay split

**Date**: 2026-06-26
**Task**: Script act locator replay split
**Branch**: `dev`

### Summary

Split SCRIPT_ACT TAP replay into explicit xml locator and semantic locator paths, preserved legacy input compatibility, updated tests, and recorded the backend locator contract.

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `1d6a123` | (see git log) |
| `1ee5f8b` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete
