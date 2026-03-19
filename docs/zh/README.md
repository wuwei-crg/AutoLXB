# 文档索引（中文）

## 当前维护文档

1. [端侧架构总览](on_device_architecture.md)
2. [项目快速开始（根 README）](../../README.zh.md)

## 代码权威来源

1. Android 前端：`android/LXB-Ignition/app`
2. Java 后端服务：`android/LXB-Ignition/lxb-core`
3. FSM 引擎：`android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexFsmEngine.java`
4. 任务/调度管理：`android/LXB-Ignition/lxb-core/src/main/java/com/lxb/server/cortex/CortexTaskManager.java`

## 外部仓库

1. LXB-MapBuilder：<https://github.com/wuwei-crg/LXB-MapBuilder>
2. LXB-Maps：地图分发数据仓库

## Legacy 文档

为避免误导，历史 Python/WebConsole 文档已从本仓库移除。  
如需追溯历史实现，请直接查看 Git 历史版本。

## 维护规则

1. 文档与代码冲突时，以代码为准。
2. 文档优先对齐 Android 端侧运行时。
3. 新功能优先更新 `on_device_architecture.md`。
