# LXB-Framework

<img src="resources/logo.jpg" alt="LXB Logo" width="180" />

[English](README.md) | [中文](README.zh.md)

面向 Android 的端侧自动化框架，聚焦高频日常任务自动执行。

## 技术实现

- **FSM 状态机编排**：任务在端侧按可控状态机执行，流程可追踪。
- **Route-Then-Act 架构**：先走 map 路由，再做视觉动作，提高稳定性。
- **Shizuku + `app_process` 后台运行**：通过 shell 进程维持后端运行能力，支持长时任务与定时任务。

## 功能概述

- **对话任务模式**：用户输入需求，立即执行一次。
  - 示例："现在帮我下单一杯咖啡。"
- **定时任务模式**：设定时间自动执行任务。
  - 示例："工作日早上 08:30 自动下单一杯咖啡。"

## 演示视频

- Bilibili：https://www.bilibili.com/video/BV1sCQDB2Es2

## 快速开始

1. 安装 Shizuku：https://github.com/RikkaApps/Shizuku
2. 在手机上启动 Shizuku 服务。
3. 从 Releases 安装最新 `lxb-ignition-vX.Y.Z.apk`。
4. 打开 LXB-Ignition，授权 Shizuku 后点击 `Start Service`。
5. 在 `Config` 中配置 LLM/VLM 接口。
6. 在首页执行对话任务，或在 `Tasks` 中创建定时任务。

## 使用建议

- 电池策略建议设为**无限制**（尤其是 MIUI/ColorOS/Honor 等系统）。
- 对于暂无 map 的任务，建议提前编写简短 **playbook** 提升执行稳定性。

## 相关仓库

- MapBuilder（建图与发布工具）：https://github.com/wuwei-crg/LXB-MapBuilder
- MapRepo（stable/candidate 地图仓库）：https://github.com/wuwei-crg/LXB-MapRepo

## 致谢

- 设计思路参考 Shizuku：https://github.com/RikkaApps/Shizuku
- 第三方声明见：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## 许可证

MIT，见 [LICENSE](LICENSE)。
