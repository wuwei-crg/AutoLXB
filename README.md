# LXB-Framework

<img src="resources/logo.jpg" alt="LXB Logo" width="180" />

[English](README.md) | [中文](README.zh.md)

Android-first mobile automation framework focused on **Route-Then-Act** execution.

## What It Does

- Runs FSM fully on-device (no PC dependency for normal usage)
- Uses Shizuku + `app_process` to keep backend runtime stable
- Supports task queue and scheduled automation
- Uses map-guided routing before vision actions to improve repeatability

## Core Runtime

1. `INIT`
2. `TASK_DECOMPOSE`
3. `APP_RESOLVE`
4. `ROUTE_PLAN`
5. `ROUTING`
6. `VISION_ACT`

## Quick Start

1. Install Shizuku: https://github.com/RikkaApps/Shizuku
2. Start Shizuku service on your phone
3. Install latest `lxb-ignition-vX.Y.Z.apk` from Releases
4. Open app -> grant Shizuku permission -> tap `Start Service`
5. Configure LLM/VLM endpoint in Config
6. Submit a task from Home chat

## Notes

- Recommended battery policy: `No restrictions` (especially on MIUI)
- Map repository and map publishing tool are maintained in separate repositories

## Related Repositories

- Map Builder (construction + publish tooling): https://github.com/wuwei-crg/LXB-MapBuilder
- Map Repository (stable/candidate map artifacts): https://github.com/wuwei-crg/LXB-MapRepo

## Build

```bash
cd android/LXB-Ignition
./gradlew :app:installDebug
```

## License

MIT. See [LICENSE](LICENSE).
