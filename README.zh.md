# LXB-Framework

<img src="resources/logo.jpg" alt="LXB Logo" width="180" />

[English](README.md) | [中文](README.zh.md)

面向 Android 的端侧自动化框架，核心是 **Route-Then-Act**。

## 功能概览

- FSM 全流程在手机端运行（正常使用不依赖 PC）
- 基于 Shizuku + `app_process` 启动并维持后端运行
- 支持任务队列与定时任务
- 先 map 路由再视觉执行，提升可复现性

## 核心状态机

1. `INIT`
2. `TASK_DECOMPOSE`
3. `APP_RESOLVE`
4. `ROUTE_PLAN`
5. `ROUTING`
6. `VISION_ACT`

## 快速开始

1. 安装 Shizuku：https://github.com/RikkaApps/Shizuku
2. 在手机上启动 Shizuku 服务
3. 从 Releases 安装最新 `lxb-ignition-vX.Y.Z.apk`
4. 打开应用并授权 Shizuku，点击 `Start Service`
5. 在 Config 中配置 LLM/VLM 接口
6. 在首页对话输入任务并执行

## 使用建议

- 建议将电池策略设为“无限制”（特别是 MIUI）
- Map 仓库与 Map 发布工具已拆分到独立仓库维护

## 相关仓库

- MapBuilder（建图与发布工具）：https://github.com/wuwei-crg/LXB-MapBuilder
- MapRepo（stable/candidate 地图仓库）：https://github.com/wuwei-crg/LXB-MapRepo

## 开发构建

```bash
cd android/LXB-Ignition
./gradlew :app:installDebug
```

## 许可证

MIT，见 [LICENSE](LICENSE)。
