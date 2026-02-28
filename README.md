<div align="center">

# LXB-Framework

### An Android Automation Framework with Visual-Language Model Integration

**Route-Then-Act**: Build navigation maps, route to target pages, then execute tasks with VLM guidance.

[![Python](https://img.shields.io/badge/Python-3.9+-blue.svg)](https://www.python.org/downloads/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![GitHub Stars](https://img.shields.io/github/stars/wuwei-crg/LXB-Framework.svg?style=social)](https://github.com/wuwei-crg/LXB-Framework)
[![Documentation](https://img.shields.io/badge/docs-latest-brightgreen.svg)](docs/en)

---

[English](README.md) | [中文文档](README.zh.md)

</div>

---

> **A note on current scope and limitations**
>
> The framework is already reasonably effective at building navigation maps for mainstream apps with relatively complete XML accessibility structures (e.g., Bilibili, Xiaohongshu, and, in some scenarios, Taobao), but it still has clear limitations: map building is weak for apps with limited usable XML structure (e.g., WeChat), popup handling is not yet robust enough (we can mainly record popups observed during mapping, while reliable closure via recorded locators still needs continuous validation), and locator construction is not yet resilient to dynamic text (e.g., rotating search hints or status-dependent strings such as "Messages, 2 unread", which can later cause matching failures). These are known shortcomings of the current version, and we will continue improving them in subsequent iterations.

---

## Demo

### Building a Navigation Map

LXB-MapBuilder explores the app autonomously and constructs a reusable navigation graph.

<img src="resources/map_building (speed x 5).gif" alt="Map Building (5x speed)" width="700">

The resulting graph can be visualized and inspected:

<img src="resources/map_visualization.gif" alt="Map Visualization" width="700">

### Route-Then-Act in Action

Once the map is built, LXB-Cortex handles tasks in three phases:

**Phase 1 — Init & Planning**: LLM generates a route plan from app state (text-only, no screenshot needed).

<img src="resources/Route-then-Act-Init-and-Planning (speed x 2).gif" alt="Init and Planning (2x speed)" width="700">

**Phase 2 — Routing**: Deterministic BFS navigation to the target page. Zero VLM calls.

<img src="resources/Route-then_act_routing(real time).gif" alt="Routing (real time)" width="700">

**Phase 3 — Acting**: VLM-guided task execution on the target page.

<img src="resources/Route-then-act-acting(speed x 5)).gif" alt="Acting (5x speed)" width="700">

---

## Overview

LXB-Framework is an engineering system for Android automation with two core goals:

1. **Build reusable navigation maps** of Android apps automatically (LXB-MapBuilder)
2. **Route to target pages first, then execute tasks** using VLM guidance (LXB-Cortex)

### Key Features

- **Map-Driven Automation**: Build app navigation maps once, reuse for multiple tasks
- **Route-Then-Act Pattern**: Navigate deterministically, then execute with AI guidance
- **VLM-XML Fusion**: Combine vision-language model understanding with XML hierarchy for reliable element location
- **Retrieval-First Positioning**: Use resource_id/text over hardcoded coordinates
- **Web Console**: Unified interface for debugging, mapping, and task execution

## Architecture

![LXB-Framework Architecture](resources/architecture.svg)

## Modules

| Module | Description | Code Path |
|--------|-------------|-----------|
| **LXB-Link** | Device communication client with reliable UDP protocol | `src/lxb_link/` |
| **LXB-Server** | Android-side service for input injection and UI perception | `android/LXB-Ignition/` |
| **LXB-MapBuilder** | Automatic app navigation map builder using VLM + XML | `src/auto_map_builder/` |
| **LXB-Cortex** | Route-Then-Act automation engine with FSM runtime | `src/cortex/` |
| **LXB-WebConsole** | Web interface for debugging and task execution | `web_console/` |

## Quick Start

### Prerequisites

- Python 3.9+
- Android device with Shizuku installed
- VLM API key (any OpenAI-compatible endpoint)

### Installation

```bash
# Clone the repository
git clone https://github.com/wuwei-crg/LXB-Framework.git
cd LXB-Framework

# Install dependencies
pip install -r requirements.txt
```

### Launch Web Console

```bash
cd web_console
python app.py
```

Then open `http://localhost:5000/` in your browser.

## Design Philosophy

### Route-Then-Act

Instead of using VLM for every action, LXB-Framework separates navigation from execution:

1. **Build a map** of the app's navigation structure
2. **Route deterministically** to the target page using BFS — zero VLM calls
3. **Execute tasks** on the target page with VLM guidance

![LXB-Cortex State Machine](resources/cortex_state_machine.svg)

This approach reduces VLM API calls, increases reliability, and enables task reproducibility.

### VLM-XML Fusion

- **VLM** provides semantic understanding (what is this element?)
- **XML** provides precise positioning (resource_id, bounds)
- **Fusion** aligns VLM detections to XML nodes via point-containment matching

![VLM-XML Fusion Engine](resources/fusion_engine.svg)

### Retrieval-First Positioning

Elements are located using stable semantic attributes (resource_id, content description) rather than hardcoded coordinates, ensuring reliability across different devices and screen sizes.

## Project Structure

```text
LXB-Framework/
├── android/LXB-Ignition/    # Android service (Shizuku)
├── docs/
│   ├── zh/                  # Chinese documentation
│   └── en/                  # English documentation
├── examples/                # Usage examples
├── resources/               # Architecture diagrams and demo GIFs
├── src/
│   ├── cortex/              # Route-Then-Act engine
│   ├── auto_map_builder/    # Map building engine
│   └── lxb_link/            # Device communication
└── web_console/             # Web interface
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

<div align="center">

**[Documentation](docs/en)** | **[Examples](examples/)** | **[Issues](https://github.com/wuwei-crg/LXB-Framework/issues)**

</div>
