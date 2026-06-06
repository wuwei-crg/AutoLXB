<div align="center">

<img src="resources/logo.jpg" alt="AutoLXB Logo" width="160" />

# AutoLXB

**Experimental Android automation framework for repetitive, linear daily tasks**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-34A853?logo=android&logoColor=white)]()
[![Latest Release](https://img.shields.io/github/v/release/wuwei-crg/AutoLXB?label=Release)](https://github.com/wuwei-crg/AutoLXB/releases)

**English** | [中文](README.zh.md)

</div>

AutoLXB is an on-device automation framework for real Android phones. It is designed for repetitive, linear, triggerable phone tasks such as scheduled check-ins, notification-based replies, and fixed-page information lookup or submission.

The project uses a **Route-Then-Act** design: a task first reuses its own saved route for deterministic navigation, and a vision model handles the dynamic UI interactions that the route cannot cover.

---

## Documentation & Demo

- **User manual**: [AutoLXB Docs](https://wuwei-crg.github.io/AutoLXB/)
- **Demo video**: [Bilibili BV114RbBfEou](https://www.bilibili.com/video/BV114RbBfEou)

The user manual provides complete tutorials for quick tasks, task templates, workflows, route editing, portable bundle import/export, configuration, and Trace troubleshooting.

## Software Preview

![Software preview](resources/software_en.png)

## Quick Start

### 1. Install and prepare your phone

1. Install the APK from [Releases](https://github.com/wuwei-crg/AutoLXB/releases).
2. Enable Developer Options and make sure these settings are enabled:
   - `USB debugging`
   - **USB debugging must stay enabled, otherwise process keepalive may fail**
   - non-root devices also need `Wireless debugging`
3. Some Chinese Android ROMs need extra adjustments:

   | ROM | Action |
   |-----|--------|
   | MIUI / HyperOS (Xiaomi, POCO) | enable `USB debugging (Security settings)` |
   | ColorOS (OPPO / OnePlus) | disable `Permission monitoring` |
   | Flyme (Meizu) | disable `Flyme payment protection` |

4. Set the battery policy of `AutoLXB` to **Unrestricted** to prevent background tasks from being killed by the system.

### 2. Start AutoLXB Core

- **Rooted device**: tap **Root startup** on the home page and confirm that `su` permission can be granted.
- **Non-root device**: tap **ADB startup** and complete Wireless ADB pairing once.
- After pairing, later startups usually only require Wireless debugging to stay enabled.

### 3. Configure the model

Open `Config -> Device-side LLM Config`, then fill in:

- `API Base URL`
- `API Key`
- `Model`

After saving, run the test to make sure the model can process images and return a valid result.

### 4. Create automation

AutoLXB works best for repeatable, linear, triggerable tasks. The Tasks page is organized around:

- **Quick Tasks**: run once immediately to trial a task description and device setup.
- **Task Templates**: save reusable tasks with target app, user playbook, TASK_DECOMPOSE switch, and one primary route.
- **Workflows**: sequence one or more templates and configure an optional trigger: none, schedule, or notification.

Write task descriptions concretely, for example:

```text
Open an app, enter the check-in page, and complete the check-in
Open WeChat, enter a specific group chat, and reply to the person who just sent a message
Open a delivery app, enter the order page, and check the rider location
```

If you are not sure whether a task description is stable, run it once as a **Quick Task** first. After it works, create a **Task Template**. For scheduled or notification-based automation, add the template to a **Workflow** and configure the trigger there.

### 5. Optional: save a task route

For repeated task templates, enable task routes. After a run, open the route editor, keep useful steps, delete unrelated actions, and tap **Save route manually**. Future runs prefer this template route before using the vision model, reducing model calls and uncertainty.

## Sample Tasks

The [`sample_tasks`](sample_tasks/) directory contains legacy task JSON examples that can be imported from the AutoLXB Tasks page. The current importer migrates them into task templates and workflows:

- `baidu-tieba-one-click-sign-in.json`: Baidu Tieba one-click sign-in example.
- `bilibili-text-post.json`: Bilibili text post creation example.
- `luckin-coconut-latte-order.json`: Luckin Coffee Coconut Latte order route example.

## Requirements

Before starting, make sure:

- you are using a real Android device on **Android 11 (API 30)** or above
- for **Wireless ADB startup**: Developer Options, USB debugging, and Wireless debugging are enabled
- for **Root startup**: the device is rooted and can grant `su`
- a text-and-image LLM / VLM endpoint is configured with the matching request type
- the selected model supports image understanding

## More Documentation

- [Getting started](https://wuwei-crg.github.io/AutoLXB/en/getting-started/install/)
- [Task tutorial](https://wuwei-crg.github.io/AutoLXB/en/tasks/overview/)
- [Configuration](https://wuwei-crg.github.io/AutoLXB/en/config/overview/)
- [Trace logs](https://wuwei-crg.github.io/AutoLXB/en/trace/overview/)

## Architecture & Workflow

AutoLXB is split into the Android app, the `lxb-core` background process, device control, model calls, task-route storage, and structured Trace logs.

![Overall architecture](resources/架构_en.png)

At runtime, a task enters the state machine, tries to hit the current template's saved route first, and falls back to visual execution when no usable route is available or when route replay cannot finish the task.

![Task workflow](resources/FSM_en.png)

Task routes are generated from real executions. The system combines screenshots, model actions, and XML / accessibility structure, then saves useful navigation actions as reusable route steps.

![Task route generation](resources/task_map_generation_en.png)

## Acknowledgements

The `app_process` daemon design is inspired by [Shizuku](https://github.com/RikkaApps/Shizuku).

AutoLXB implements its own Wireless ADB pairing, connection, and startup flow and does not depend on Shizuku at runtime. The project is also shared in the [LINUX DO community](https://linux.do/).

Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## License

MIT. See [LICENSE](LICENSE).

## Star Trend

[![Star History Chart](https://api.star-history.com/svg?repos=wuwei-crg/AutoLXB&type=Date)](https://star-history.com/#wuwei-crg/AutoLXB&Date)
