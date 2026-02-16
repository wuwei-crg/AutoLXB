# Cortex Route-Then-Act

This document reflects the current route-first architecture.

## 1. Core Idea

Split execution into two phases:

1. **Route phase**: planner resolves app + target page, then deterministic routing executes node chain.
2. **Act phase**: once target page is reached, VLM handles user task actions.

## 2. Pipeline

User task -> Planner -> `package_name` + `target_page` -> BFS path on map -> route execution -> VLM action stage.

## 3. Planner Design

Current practical design supports two-step reasoning:

- Step A: infer intended app from user intent,
- Step B: with selected app map context, infer `target_page`.

`target_page` must map to map `page_id` directly to allow deterministic BFS.

## 4. Route Data

Runtime map abstraction includes:

- `pages`
- `transitions` (node-triggered edges)
- optional popup/block metadata

See `src/cortex/route_then_act.py` (`RouteMap`, `RouteEdge`, `RoutePlan`).

## 5. Route Execution Principles

- Router is node-click chain based, not full page-state correction.
- Before each click, verify candidate node existence.
- If route drifts (expected node missing), apply recovery policy.

## 6. Recovery Policy

Configurable in `RouteConfig`:

- retry node existence (`node_exists_retries`, `node_exists_interval_sec`)
- route restart limit (`max_route_restarts`)
- optional temporary VLM takeover (`use_vlm_takeover`)

Current strategy trend:

- route phase keeps deterministic behavior,
- unresolved interrupts can be delegated briefly to VLM,
- severe route deviation can trigger app restart and route replay.

## 7. API + Web Console

Backend endpoints:

- `/api/cortex/llm/config`
- `/api/cortex/llm/test`
- `/api/cortex/route/run`
- `/api/cortex/route_then_act/run`

UI page:

- `/cortex_route` (inside Console Hub shell)

## 8. Operational Goal

The routing layer should optimize for:

- deterministic reachability to target page,
- traceable logs,
- bounded recovery,
- clean handoff boundary to VLM action stage.
