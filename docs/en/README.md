# Docs Index (English)

## Current Docs (Maintained)

1. [On-Device Architecture](on_device_architecture.md)
2. [Project Quick Start (root README)](../../README.md)

## Runtime Source of Truth

1. Android app UI: `android/LXB-Ignition/app`
2. Java backend service: `android/LXB-Ignition/lxb-core`
3. FSM engine: `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFsmEngine.java`
4. Task/schedule manager: `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexTaskManager.java`

## External Repositories

1. LXB-MapBuilder: <https://github.com/wuwei-crg/LXB-MapBuilder>
2. LXB-Maps: map data distribution repository

## Legacy Docs

Legacy Python/WebConsole docs were removed from this repository to avoid confusion.
Historical details should be referenced from commit history when needed.

## Maintenance Policy

1. If behavior conflicts, code wins.
2. Keep docs aligned to Android on-device runtime first.
3. New features should update `on_device_architecture.md` first.
