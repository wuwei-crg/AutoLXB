# LXB-Framework

<img src="resources/logo.jpg" alt="LXB Logo" width="180" />

[English](README.md) | [中文](README.zh.md)

Android-first mobile automation framework focused on practical daily automation.

## Technical Foundation

- **FSM orchestration**: each task is executed through a deterministic on-device state machine.
- **Route-Then-Act**: use map routing first, then run visual actions to improve reliability.
- **Shizuku + `app_process` runtime**: starts backend process in shell context for long-running/background scenarios.

## Feature Overview

- **Chat task mode**: type a one-time request and run immediately.
  - Example: "Order one coffee for me now."
- **Scheduled task mode**: set a time and let the task run automatically.
  - Example: "At 08:30 every weekday, place a coffee order."

## Demo Video

- Bilibili: https://www.bilibili.com/video/BV1sCQDB2Es2

## Quick Start

1. Install Shizuku: https://github.com/RikkaApps/Shizuku
2. Start Shizuku service on your phone.
3. Install latest `lxb-ignition-vX.Y.Z.apk` from Releases.
4. Open LXB-Ignition, grant Shizuku permission, then tap `Start Service`.
5. Configure your LLM/VLM endpoint in `Config`.
6. Run tasks from chat mode, or create schedules in `Tasks`.

## Usage Recommendations

- Set battery policy to **No restrictions** (especially on MIUI/ColorOS/Honor ROM variants).
- For tasks without map coverage, provide a short **playbook** to improve action stability.

## Related Repositories

- Map Builder (construction + publish tooling): https://github.com/wuwei-crg/LXB-MapBuilder
- Map Repository (stable/candidate map artifacts): https://github.com/wuwei-crg/LXB-MapRepo

## Acknowledgement

- Inspired by Shizuku: https://github.com/RikkaApps/Shizuku
- Third-party notice: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## License

MIT. See [LICENSE](LICENSE).
