# LXB-Framework

LXB-Framework is an Android automation framework focused on two workflows:

1. Build app navigation maps from real device interaction.
2. Route user intent to a target page, then hand off to VLM for task execution.

## Main Modules

- `src/lxb_link`: binary protocol client for device communication (`tap`, `dump_actions`, `find_node`, `screenshot`, etc.).
- `src/auto_map_builder`: map exploration engines (`NodeMapBuilder` as current default).
- `src/cortex`: route-then-act runtime (`RouteThenActCortex`).
- `web_console`: Flask console with shell navigation and four internal pages.

## Current Product Shape

- Web shell page: `/` (Console Hub)
- Internal sub-pages:
  - `Command Studio` (`/command_studio`)
  - `Map Builder` (`/map_builder`)
  - `Map Viewer` (`/map_viewer`)
  - `Cortex Route` (`/cortex_route`)
- Global device connect/disconnect is handled in the shared top navigation bar.

## Quick Start

### 1. Install

```bash
pip install -r requirements.txt
```

If no `requirements.txt` is available in your environment, install at least:

```bash
pip install flask flask-cors pillow openai
```

### 2. Run web console

```bash
cd web_console
python app.py
```

Open `http://localhost:5000`.

## Documentation

- `docs/AUTO_MAP_BUILDER.md`: map-building architecture and current strategy.
- `docs/CORTEX_ROUTE_THEN_ACT.md`: route-stage and act-stage design.
- `docs/LXB-Link.md`: protocol and command layer notes (legacy large spec).
- `docs/LXB-Server.md`: Android-side server architecture notes (legacy large spec).
- `web_console/README.md`: frontend pages and API usage.
- `tests/README.md`: test layout and execution.

## Notes

- Current map strategy accepts controlled redundancy for weak-feature nodes.
- Strong-feature routing remains the primary path for stability.
- VLM is used as runtime capability, not as a guaranteed static XML identity source.
