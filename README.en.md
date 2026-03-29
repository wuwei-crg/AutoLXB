# LXB-Framework

<img src="resources/logo.jpg" alt="LXB Logo" width="180" />

[English](README.en.md) | [中文](README.md)

An experimental Android automation framework designed for repetitive, linear daily tasks.
Instead of letting the model roam freely, LXB-Framework uses a **Route-Then-Act** pipeline: a pre-built navigation map handles deterministic page routing, then a VLM takes over to handle the actual on-screen work.

## Software Preview & Features

![Software preview](resources/software_en.png)

- **Chat task mode**: type a one-time natural language request and execute immediately.
  - Example: `Help me order one large oat latte from the coffee app.`
- **Scheduled task mode**: set a trigger time (one-shot / daily / weekly) and let the daemon execute automatically, even when the screen is off or the app is killed.
  - Example: `Every weekday at 08:30, place my usual coffee order.`
- **Playbook fallback**: for apps without a navigation map, write a step-by-step playbook and the pipeline will follow it instead.

## How It Works

- **Route-Then-Act pipeline**: tasks are split into a deterministic routing phase (map-based, no vision) and a vision-based action phase (VLM handles dynamic UI).
- **FSM orchestration**: a state machine (INIT → TASK_DECOMPOSE → APP_RESOLVE → ROUTE_PLAN → ROUTING → VISION_ACT → FINISH/FAIL) keeps execution structured and traceable.
- **`app_process` daemon**: the backend runs as a shell-level process independent of the Android app lifecycle, enabling reliable background and scheduled execution without relying on Android's fragile service keep-alive mechanisms.

![Overall architecture](resources/architecture_overall.png)

![Framework internal architecture](resources/architecture_LXB-Framework.png)

## Requirements

- Android **11 (API 30)** or higher (real device recommended; emulators may trigger app detection)
- **Developer Options** and **Wireless Debugging** enabled on the device (no root, no extra apps required)
- An **OpenAI-compatible** LLM/VLM endpoint (`/v1/chat/completions` format); any model provider works

## Quick Start

1. **Enable Wireless Debugging** on the device: go to `Settings → Developer Options → Wireless Debugging`.
2. **Install the APK**: download the latest `lxb-ignition-vX.Y.Z.apk` from [Releases](https://github.com/wuwei-crg/LXB-Framework/releases) and install it.
3. **Pair the device**: open LXB-Ignition and follow the in-app pairing guide. The device screen will display a 6-digit pairing code — enter it when prompted. Subsequent launches reconnect automatically.
4. **Start the daemon**: after pairing succeeds, the app automatically pushes the backend DEX to the device and starts the daemon via `app_process`. The status indicator will change to **Running**.
5. **Configure LLM**: go to the `Config` tab and fill in:
   - **API Base URL** — your model endpoint (OpenAI-compatible)
   - **API Key** — the corresponding key
   - **Model** — model name, e.g. `gpt-4o-mini`, `qwen-plus`
6. **(Optional) Sync maps**: in `Config`, set the MapRepo URL to enable automatic stable map downloads. Without maps, the framework falls back to pure vision mode.

## Running Your First Task

Once set up, go to the home screen and type your request in the chat box, for example:

```
Open Bilibili and post a moment with content "test" and title "test"
Open WeChat and send "hello" to File Transfer
```

The interface will display the current FSM state in real time as the task executes (ROUTE_PLAN → ROUTING → VISION_ACT).

## Scheduled Tasks

Open the `Tasks` tab to create a scheduled task:
- Set a trigger time (one-shot, daily, or weekly)
- Specify the target app package name
- Write the task instruction
- Optionally attach a **Playbook** for apps without a map

The daemon's `app_process` design ensures scheduled tasks fire on time even when the screen is locked or the app has been killed by the system.

## Building Maps for New Apps

Navigation maps are built with [LXB-MapBuilder](https://github.com/wuwei-crg/LXB-MapBuilder) and distributed via [LXB-MapRepo](https://github.com/wuwei-crg/LXB-MapRepo). See the MapBuilder README for the full build workflow. Pre-built stable maps are available in MapRepo and can be synced directly from the `Config` tab.

## Usage Tips

- Set the battery policy for LXB-Ignition to **Unrestricted** (especially on MIUI / ColorOS / HyperOS / Honor ROM variants).
- If an app has no map, write a short **playbook** describing the steps — this significantly improves action stability compared to pure vision.

## Related Repositories

- [LXB-MapBuilder](https://github.com/wuwei-crg/LXB-MapBuilder) — map construction and publishing tool
- [LXB-MapRepo](https://github.com/wuwei-crg/LXB-MapRepo) — stable/candidate navigation map artifacts

## Acknowledgement

The `app_process` daemon design is inspired by [Shizuku](https://github.com/RikkaApps/Shizuku). LXB-Framework implements its own Wireless ADB pairing and connection and does not depend on Shizuku at runtime.
This project is also shared with and supported by the [LINUX DO community](https://linux.do/).

Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## License

MIT. See [LICENSE](LICENSE).

## Star Trend

[![Star History Chart](https://api.star-history.com/svg?repos=wuwei-crg/LXB-Framework&type=Date)](https://star-history.com/#wuwei-crg/LXB-Framework&Date)

