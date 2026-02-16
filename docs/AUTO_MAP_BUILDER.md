# Auto Map Builder (Current)

This document describes the current practical map-building strategy used in LXB-Framework.

## 1. Positioning

The map system is not a perfect XML identity solver.

The practical target is:

- keep strong-feature routes stable,
- tolerate weak-feature ambiguity with controlled redundancy,
- keep maps usable for routing.

## 2. Current Builder Baseline

Primary builder: `NodeMapBuilder` (`src/auto_map_builder/node_explorer.py`).

Exploration is node-driven:

- task queue stores nodes to explore,
- each exploration path restarts app and replays path,
- transitions are recorded as actionable edges.

## 3. Binding Reality

Weak nodes often have no stable `resource_id/text/content_desc` (icon-only, dynamic text).

Therefore, strict unique XML binding is not always possible.

Current approach:

- prioritize strong semantic locators when available,
- reject ambiguous weak bindings instead of random clicks,
- allow controlled map redundancy where needed.

## 4. Locator Data Model

`NodeLocator` commonly uses:

- `resource_id`
- `text`
- `content_desc`
- `class_name`
- `parent_resource_id`
- `bounds` (hint)

Recent experiments may add weak-node relation fields (e.g., anchors/parent-chain), but strong-node path must stay regression-safe.

## 5. De-dup and Queue Strategy

De-dup key should prefer stable runtime context:

- `activity + locator_key`

Avoid depending on volatile VLM page fingerprint in dynamic pages.

## 6. Popup/Interrupt Handling Principle

Route/map exploration should treat popup/ads as interrupt context, not main-map pages.

When interrupts are detected:

- try known close actions,
- if unresolved, handoff to temporary VLM takeover,
- return to main route exploration.

## 7. Logging Requirement

Logs should be debug-first and compact:

- clear `find_node` lifecycle
- clear `tap` execution points
- explicit bind failure reason
- optional category filtering in UI

## 8. Recommended Engineering Direction

For robust production routing:

1. keep strong-node route backbone deterministic,
2. classify weak nodes as optional/uncertain edges,
3. use runtime verification and repeated success to promote weak candidates,
4. avoid letting weak-node noise pollute core route graph.
