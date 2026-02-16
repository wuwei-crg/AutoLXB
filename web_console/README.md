# Web Console

Flask-based control console for LXB-Framework.

## Entry

- Main shell: `http://localhost:5000/`
- The shell hosts internal pages via in-page content switching.

## Internal Pages

- `Command Studio` (`/command_studio`)
  - Protocol command debugging UI.
  - Sends low-level commands via existing `main.js` command handlers.
- `Map Builder` (`/map_builder`)
  - Node-driven map exploration UI.
  - Live logs, screenshot panel, node queue/status.
- `Map Viewer` (`/map_viewer`)
  - Navigation graph visualization and map inspection.
- `Cortex Route` (`/cortex_route`)
  - Route planning and execution (planner -> target_page -> BFS route chain).

## Shared Navigation + Connection

The shell (`index.html`) owns:

- Shared top navigation between all four pages.
- Global device connection bar (`host`, `port`, connect/disconnect, status).

Sub-pages no longer provide their own top navigation. Connection UI inside sub-pages is hidden/removed in favor of the global bar.

## Start

```bash
cd web_console
python app.py
```

## Key Backend Routes

Page routes:

- `/`
- `/command_studio`
- `/map_builder`
- `/map_viewer`
- `/cortex_route`

Core API groups:

- `/api/connect`, `/api/disconnect`, `/api/status`
- `/api/command/*` (tap/swipe/find_node/dump_actions/...)
- `/api/explore/*` (map exploration)
- `/api/maps/*` (map list/load/save)
- `/api/cortex/llm/*`
- `/api/cortex/route/run` and `/api/cortex/route_then_act/run`

## Frontend Files

- `templates/index.html`: shell container + shared nav + global connection.
- `templates/command_studio.html`: command debugger UI.
- `templates/map_builder.html`: map building UI.
- `templates/map_viewer.html`: map visualization UI.
- `templates/cortex_route.html`: route-stage debug UI.
- `static/js/main.js`: command studio runtime handlers (ID-based bindings).
