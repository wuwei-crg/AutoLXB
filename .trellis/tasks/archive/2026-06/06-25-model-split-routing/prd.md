# 模型单独配置路由

## Goal

让 SCRIPT_ACTION 阶段可以为 semantic locator 和 vision_act 分别配置模型路由，同时保留当前统一路由的简单模式。配置持久化仍以 provider 为单位，阶段配置只引用 provider，从而解耦模型使用场景与 provider 配置。

## What I already know

* SCRIPT_ACTION 阶段包含 semantic locator 与 vision_act 两类模型使用路径。
* 配置页面需要支持“统一路由”和“单独路由”两种模式。
* 统一路由模式只配置一个路由。
* 单独路由模式下 semantic locator 与 vision_act 各自独立配置。
* 单独路由模式下两个路由的保存、测试、同步等操作互不影响。
* 现有配置文件仍应以 provider 为单位保存。
* 两种模型场景的配置文件可以指向 provider，避免把 provider 配置复制到场景配置里。

## Assumptions (temporary)

* 为兼容旧配置，缺少单独路由配置时默认按统一路由处理。
* “同步”沿用现有配置页面里的同步语义，不新增新的外部服务协议。
* semantic locator 与 vision_act 都属于 SCRIPT_ACTION 阶段内部路由，不改变其他阶段默认模型行为。

## Open Questions

* 无阻塞问题。默认保留统一/单独路由各自的 provider 引用；运行时只按当前 routing mode 生效，避免用户切换模式时丢配置。

## Backend Behavior (evolving)

* 当前设备端配置由 APK 的 `DeviceConfigSyncer` 写入 `/data/local/tmp/lxb-llm-config.json`，后端 `LlmConfig.loadDefault()` 读取单组 `api_base_url/api_key/model/request_type`。
* 当前 APK 保存的 `LlmProfile` 本质上是 provider preset，应继续作为 provider 维度保存。
* 新设备端配置需要保留旧的单 provider 顶层字段作为 fallback，同时新增 `providers` 列表和 `model_routing` 路由引用。
* `model_routing.mode=unified` 时 semantic locator 与 vision_act 都解析到统一 provider；`mode=split` 时分别解析 `semantic_locator_provider_id` 与 `vision_act_provider_id`。
* 旧配置缺少 `providers/model_routing` 时应继续作为统一路由加载。
* SCRIPT_ACT 的 semantic visual resolver 和 semantic adaptation materializer 使用 semantic locator 路由。
* VISION_ACT planner 使用 vision_act 路由。
* 任务拆分、APP_RESOLVE、通知 LLM 条件等其他 LLM 调用暂不改变，继续使用默认/统一配置。

## Frontend/User-Facing Interface (evolving)

* 配置页保留 provider 编辑区：Base URL、API Key、Model、Request Type、保存 provider preset。
* 配置页新增 SCRIPT_ACTION 路由区：统一路由/单独路由切换。
* 统一路由展示一条 provider 引用和保存、测试并同步、仅同步操作。
* 单独路由展示 semantic locator 与 vision_act 两条 provider 引用，每条都有独立保存、测试并同步、仅同步结果。
* 路由区只保存 provider id 引用，不复制 provider 内容；provider 内容仍由 saved local configs 管理。
* 切换路由模式不清空另一种模式的已选 provider。

## Decision Log

* 用户初始需求 → 支持 SCRIPT_ACTION 内 semantic locator 与 vision_act 分别路由，provider 配置保持解耦 → 形成本任务目标和初始验收范围。
* 代码检查 → 现有 provider preset 已由 `LlmProfile` 保存，本任务复用它作为 provider 列表 → 避免新增重复 provider 存储。
* 兼容性决策 → 设备端同步 JSON 保留顶层 legacy provider 字段，并新增 `providers/model_routing` → 旧配置、旧读取逻辑、无路由配置场景仍可工作。
* 路由范围决策 → 只改 SCRIPT_ACT semantic locator/materializer 与 VISION_ACT planner → 其他 LLM 调用保持默认路由，降低行为面。

## Requirements (evolving)

* 支持 SCRIPT_ACTION 的统一路由模式。
* 支持 SCRIPT_ACTION 的 semantic locator 与 vision_act 单独路由模式。
* 单独路由模式下两类路由的保存、测试、同步独立。
* provider 配置文件仍以 provider 为单位保存。
* 阶段/场景配置引用 provider，而不是复制 provider 内容。
* 缺少新字段的旧设备端配置继续按统一路由运行。
* 切换统一/单独路由模式不删除已保存 provider 或另一模式的路由选择。

## Acceptance Criteria (evolving)

* [x] 旧配置能继续加载并等价于统一路由。
* [x] 配置页可切换统一路由/单独路由。
* [x] 单独路由下 semantic locator 和 vision_act 可分别保存、测试、同步。
* [x] SCRIPT_ACTION 执行 semantic locator 时使用 semantic locator 路由。
* [x] SCRIPT_ACTION 执行 vision_act 时使用 vision_act 路由。
* [x] provider 配置持久化仍按 provider 维度保存。
* [x] 设备端同步 JSON 同时包含 legacy 顶层 provider 和新 provider/route 引用。
* [x] 删除 provider 后相关路由引用不会继续指向不存在的 provider。

## Definition of Done (team quality bar)

* Tests added/updated where appropriate.
* Lint / typecheck / CI-equivalent checks pass or any gaps are documented.
* Compatibility and rollback behavior considered.
* Specs updated if new project conventions emerge.

## Out of Scope (explicit)

* 不重构非 SCRIPT_ACTION 阶段的模型路由。
* 不改变 provider 的凭据格式或外部 API 协议，除非现有代码要求最小兼容补丁。
* 不新增后端命令协议；本任务沿用现有文件同步路径。

## Technical Notes

* Relevant backend files:
  * `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/LlmConfig.java`
  * `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFsmEngine.java`
  * `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/SemanticVisionStepResolver.java`
  * `android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/SemanticStepMaterializer.java`
* Relevant frontend files:
  * `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainViewModel.kt`
  * `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainActivity.kt`
  * `android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/core/DeviceConfigSyncer.kt`
* Relevant tests:
  * `android/LXB-Ignition/lxb-core/src/test/java/com/lxb/server/cortex/LlmConfigTest.java`
  * `android/LXB-Ignition/app/src/test/java/com/example/lxb_ignition/core/DeviceConfigSyncerTest.kt`
