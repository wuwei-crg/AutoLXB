# Backend Development Guidelines

Backend guidance covers the on-device Java daemon in
`android/LXB-Ignition/lxb-core`.

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | Daemon module boundaries and where new backend code belongs | Complete |
| [Persistence Guidelines](./database-guidelines.md) | File-backed JSON state, schemas, compatibility, and atomic writes | Complete |
| [Error Handling](./error-handling.md) | Command-boundary failures, validation, and recovery patterns | Complete |
| [Logging Guidelines](./logging-guidelines.md) | Console tags and structured Cortex Trace events | Complete |
| [Quality Guidelines](./quality-guidelines.md) | Protocol compatibility, dependency limits, and backend tests | Complete |

Load these specs for work touching `android/LXB-Ignition/lxb-core/**`,
backend protocol contracts, task routes/maps, Cortex scheduling, notification
triggers, or Trace events.
