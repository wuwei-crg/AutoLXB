# 清理废弃残留资产和旧入口

## Goal

清理 AutoLXB Android runtime 中已经脱离当前 APK-first 主路径的历史资产、旧脚本、旧入口和文档残留，降低 APK 体积、构建噪音和后续重构时的误判成本。

## What I Already Know

* 当前主运行路径是 `android/LXB-Ignition/app` 启动设备端 `lxb-core`，不是旧的 PC web_console / 手工 app_process 路径。
* `WirelessAdbBootstrapService` 只部署 `lxb-core-dex.jar` 和 `lxb-starter-*` assets。
* `:app:mergeDebugAssets` 当前会把 `classes.dex`、普通 `lxb-core.jar`、`lxb-core-dex.jar`、`lxb-starter-*`、`lxb-core-1.0.0.jar`、`lxb-core-manual.jar` 全部合入 APK assets。
* `DebugMain.java` / `TestMain.java` 没有当前入口引用，但仍位于 `src/main`，会被编进 core jar/dex。
* `deploy_and_run.sh` 调用不存在的 `:lxb-core:buildServerJar` 任务。
* `run.bat` 仍走旧 `lxb-server.zip` 手工启动路径，文案还提 Web Console。
* `MainViewModel` 仍保留 PC `web_console` 配置和发送函数，但 `MainActivity` 已无可见入口引用。
* `docs/trace/*.md` 和 `docs/en/trace/*.md` 中仍使用 `route_id: "template:daily-checkin"` 示例；当前 canonical route id 已改为直接 template id。

## Assumptions

* 本任务优先做无行为风险的清理，不删除运行时兼容迁移逻辑。
* 对旧用户数据的兼容迁移逻辑继续保留，除非后续明确决定断兼容。
* `sample_tasks/sample_task.zip` 是有意保留的 portable sample archive，本任务默认不删除。

## Cleanup Checklist

### High Priority

* [x] 修正 APK assets 打包面：不要把整个 `../lxb-core/build/libs` 作为 assets 输入，只打包运行需要的 `lxb-core-dex.jar` 和 `lxb-starter-*`。
* [x] 删除或迁移 `android/LXB-Ignition/app/src/main/assets/lxb-core-1.0.0.jar`。
* [x] 删除或迁移 `android/LXB-Ignition/app/src/main/assets/lxb-core-manual.jar`。
* [x] 删除已跟踪但无引用的 `android/LXB-Ignition/lxb-core.zip`。
* [x] 确认 release/debug APK assets 不再包含 `classes.dex`、普通 `lxb-core.jar`、旧 jar。

### Medium Priority

* [x] 处理 `DebugMain.java` 和 `TestMain.java`：删除，或移到测试/开发工具目录并确保不进入 runtime jar。
* [x] 删除或重写 `deploy_and_run.sh`，因为它调用不存在的 Gradle 任务。
* [x] 处理 `run.bat`：删除，或改名为显式 legacy/manual debug script 并更新文案，避免把旧 Web Console 路径当当前推荐路径。
* [x] 清理 `MainViewModel` 中不可达的 PC `web_console` 配置、发送函数、SharedPreferences key 保存逻辑和本地化文案。
* [x] 删除 Android 默认脚手架测试 `ExampleUnitTest.kt` / `ExampleInstrumentedTest.kt`，或替换成真实 smoke test。

### Documentation / Consistency

* [x] 更新 `docs/trace/task-flow.md` 和 `docs/en/trace/task-flow.md` 中的旧 `template:<id>` route 示例。
* [x] 更新 `docs/trace/routes.md` 和 `docs/en/trace/routes.md` 中的旧 `template:<id>` route 示例。
* [ ] README 中继续标注 `sample_tasks/*.json` 是 legacy task examples；不要误删 importer 兼容说明。

### Keep For Compatibility

* [ ] 保留 `legacyKind` / `legacyId` 和 schedule / notification 到 workflow/template 的迁移逻辑。
* [ ] 保留 `template:` / `schedule:` / `notify:` route key 迁移逻辑。
* [ ] 保留 `FrameCodec` v1 解码兼容，除非另开任务设计协议断兼容。
* [ ] 保留旧 payload 字段兼容解析，例如 old field name / old extractor fallback。
* [ ] 保留 `sample_tasks/sample_task.zip`，除非后续确认不再作为 portable bundle 示例使用。

## Backend Behavior

* 构建产物清理不能改变 core 启动协议：APK 仍应部署 `lxb-core-dex.jar` 到 `/data/local/tmp/lxb-core.jar`，并通过 starter 启动 `com.lxb.server.Main`。
* 清理旧 main classes 后，runtime jar/dex 只应暴露当前 `Main` 入口。
* 删除旧脚本不得影响 Gradle 标准构建：`:app:assembleDebug`、`:app:mergeDebugAssets`、`:lxb-core:buildDex` 仍应可用。
* 兼容迁移逻辑继续运行，避免已有用户配置、旧 route map、旧 workflow/schedule/notification 数据失效。

## Frontend/User-Facing Interface

* 不新增用户界面。
* 如果移除 PC `web_console` 发送路径，应同时移除不可见配置状态、保存项和本地化提示，避免未来误恢复不可达入口。
* 用户手册只修正文档示例，不改变产品概念说明。

## Acceptance Criteria

* [x] `:app:mergeDebugAssets` 输出中仅包含 `lxb-core-dex.jar` 和 `lxb-starter-*` 这类运行必需资产。
* [x] `:app:assembleDebug` 成功。
* [x] `:lxb-core:test` 成功，或记录无法运行原因。
* [x] App unit tests 成功，或记录无法运行原因。
* [x] `rg` 搜索确认旧 jar 文件名、PC web_console 不可达入口、`DebugMain` / `TestMain` 残留已按决策处理。
* [x] 文档中 route id 示例与当前直接 template id 规则一致。
* [x] `git status` 只包含本任务预期文件变更。

## Definition of Done

* 代码和构建脚本清理完成。
* 文档示例修正完成。
* 运行相关 Gradle 验证。
* 没有删除明确需要保留的运行时兼容逻辑。
* 如发现新的项目约定或构建约束，更新 `.trellis/spec/`。

## Out of Scope

* 不重构 task route / workflow 业务模型。
* 不移除旧用户数据迁移逻辑。
* 不变更 `sample_tasks/*.json` legacy importer 行为。
* 不重新设计 APK 启动流程、ADB pairing、starter 协议。
* 不处理未被 Git 跟踪的本地目录，例如 `site/`、`release_artifacts/`、`community/`、`paper/`。

## Technical Notes

* Main assets 配置：`android/LXB-Ignition/app/build.gradle.kts`
* Core dex 构建：`android/LXB-Ignition/lxb-core/build.gradle.kts`
* Runtime asset 部署：`android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/service/WirelessAdbBootstrapService.kt`
* PC web_console 残留：`android/LXB-Ignition/app/src/main/java/com/example/lxb_ignition/MainViewModel.kt`、`UiMessageLocalizer.kt`
* Debug/test main classes：`android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/DebugMain.java`、`TestMain.java`
* Route docs：`docs/trace/*.md`、`docs/en/trace/*.md`

## Decision Log

* Initial cleanup scope -> prioritize assets/build cleanup first -> reduces APK bloat and avoids shipping stale core jars.
* Compatibility boundary -> keep runtime migration and protocol compatibility by default -> avoid breaking existing user data.
* Documentation boundary -> update route id examples only -> avoid broader docs rewrite in this cleanup task.
* Legacy manual scripts -> user chose deletion -> remove both the broken shell script and the old batch app_process path instead of preserving a renamed legacy path.
