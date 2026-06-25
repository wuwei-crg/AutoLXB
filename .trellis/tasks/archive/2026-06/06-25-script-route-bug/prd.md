# brainstorm: SCRIPT_Route 路线开关与读取问题

## Goal

定位 `SCRIPT_Route` 路线相关实现中“前端缺少打开路线开关”和“路线读取在流程中被跳过”的原因，先完成代码级分析，再基于结论一起收敛出可落地的修复方案。

## What I already know

* 用户反馈 `SCRIPT_Route` 路线部分有 bug。
* 前端似乎没有“打开路线”的 template/开关。
* 路线读取要么因为默认关闭而没进到逻辑，要么读取机制本身有问题，导致到路线步骤直接跳过。
* 代码里 `task_map_mode` 是真实存在的模板/任务字段，后端会用它决定是否读取路线。
* 模板编辑页当前只保存 `name/description/package/user_playbook/decompose_enabled`，没有保存 `task_map_mode`。
* 前端也没有调用后端 `setTaskMapMode` 的入口，所以模板级路线模式大概率长期落在默认 `off`。
* 现有模板如果再次通过当前表单保存，也会因为没传 `task_map_mode` 而被后端规整回 `off`。
* 后端在 `CortexFsmEngine.buildExecutionPlanInInit()` 里只要看到 `taskMapMode == "off"` 就直接跳过路线查找。
* 任务列表和详情页能显示“按路线执行”，但那是展示，不是可编辑开关。

## Assumptions (temporary)

* 这是一个同时涉及前端模板配置和流程执行链路的问题。
* 需要先从代码和现有规范里找出 `SCRIPT_Route` 的定义、入口和读取条件。

## Open Questions

* 修复应采用模板级显式开关，还是保存时默认开启路线？
* 是否需要保留 `manual/ai/off` 三档，还是 UI 上先收束为“开/关”？
* 是否要同时补历史模板的兼容迁移，避免旧模板一直维持 `off`？

## Backend Behavior (evolving)

* 路线模式字段是 `task_map_mode`，合法值为 `off/manual/ai`。
* `TaskTemplate.normalizeTaskMapMode()` 和 `CortexTaskManager.normalizeTaskMapMode()` 都会把未知值压成 `off`。
* `TaskTemplate.fromMap()` 对缺失的 `task_map_mode` 也会回落到 `off`，所以保存时不能依赖后端自动保留旧值。
* `CortexFsmEngine.buildExecutionPlanInInit()` 在初始化阶段先做路线查找；当 `taskMapMode == "off"` 时，直接跳过路线查找并进入后续流程。
* `CortexTaskManager.setTaskMapMode()` 已经提供了后端写入口，可更新模板或任务实例，但前端没有接上。
* 模板保存接口 `CMD_CORTEX_TEMPLATE_SAVE` 接收 `task_map_mode`，但当前 Android 模板表单没有传这个字段。
* 路线编辑页只处理路线内容本身，不负责切换模板的路线模式。

## Frontend/User-Facing Interface (evolving)

* 模板表单当前有“允许模型分解”开关，没有“按路线执行”开关。
* 模板列表/详情页只显示路线状态，不提供编辑入口。
* 路线编辑页位于模板详情入口内，但它只编辑保存路线内容，不控制模板是否启用路线。
* UI 侧的最佳修复点大概率是模板表单，和“允许模型分解”并列放一个“按路线执行”开关，保存时把 `task_map_mode` 一起写回。

## Decision Log

* 分析 `SCRIPT_Route` 读取链路与前端开关缺失问题 → 发现模板保存未写 `task_map_mode`，后端遇到 `off` 直接跳过路线 → 修复应先补模板级可编辑开关
* 是否保留 `manual/ai/off` 三档 → 待定 → 需要用户确认 UI 收敛方式

## Requirements (evolving)

* 需要找出路线步骤被跳过的真实原因。
* 需要确认前端是否应提供显式的路线开关。
* 需要产出可执行的 fix 方案供后续实施。
* 需要让模板保存链路把路线模式真正写进去，而不是只展示。

## Acceptance Criteria (evolving)

* [ ] 能定位 `SCRIPT_Route` 的配置与消费链路。
* [ ] 能解释路线步骤为什么会被跳过。
* [ ] 能形成至少一个可实施的修复方案。
* [ ] 模板表单可显式切换路线模式，并保存到后端。
* [ ] 保存后的模板列表/详情能正确反映路线模式状态。

## Definition of Done (team quality bar)

* Tests added/updated (unit/integration where appropriate)
* Lint / typecheck / CI green
* Docs/notes updated if behavior changes
* Rollout/rollback considered if risky

## Out of Scope (explicit)

* 本轮不直接落最终代码修复，先完成分析和方案收敛。
* 本轮不重构整个路线回放引擎，只修复模板开关与保存链路。

## Technical Notes

* Task dir: `.trellis/tasks/06-25-script-route-bug`
* Need to inspect repo for `SCRIPT_Route`, template/rendering, and execution flow.
* Key files: `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainActivity.kt`, `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainViewModel.kt`, `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFsmEngine.java`, `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexTaskManager.java`, `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/workflow/TaskTemplate.java`
* Relevant behavior observed: template form save omits `task_map_mode`; task lookup skips route when mode is `off`.

## Technical Approach

### Feasible approaches

**Approach A: Template switch + preserve backend modes** (Recommended)

* Add a template-level "按路线执行" switch in the template form.
* Load/save `task_map_mode` through template form state.
* Map UI on/off to `manual/off`; keep `ai/manual/off` semantics in backend unchanged.
* Pros: smallest change, matches current backend contract, fixes the missing toggle and skipped lookup.
* Cons: old templates stay off until the user edits them.

**Approach B: Template switch + legacy auto-enable**

* Same as A, but if a template already has a saved route and `task_map_mode` is missing/blank, treat it as enabled on load or migrate it once.
* Pros: older templates with saved routes start working without manual cleanup.
* Cons: more surprising, needs careful migration rules to avoid enabling templates that were intentionally off.

**Approach C: Expose explicit route-mode selector**

* Replace the binary switch with a visible `off/manual/ai` selector.
* Pros: exposes the true backend semantics.
* Cons: more UI noise and more user cognitive load than the current product needs.
