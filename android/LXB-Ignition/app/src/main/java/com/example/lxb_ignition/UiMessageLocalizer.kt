package com.example.lxb_ignition

object UiMessageLocalizer {
    fun localize(uiLang: String, text: String): String {
        if (uiLang != "zh") {
            return text
        }
        return when {
            text.startsWith("Invalid lxb-core port") -> "lxb-core 端口无效，请在配置页检查 TCP 端口。"
            text == "Please enter a task description before running on device." -> "设备端执行前请先输入任务描述。"
            text == "Task received, checking lxb-core server status..." -> "任务已接收，正在检查 lxb-core 服务状态..."
            text == "Server is not running, please start the service first." -> "服务未运行，请先启动服务。"
            text == "Server is running, calling Cortex FSM on device..." -> "服务已运行，正在调用设备端 Cortex FSM..."
            text == "Root is not available on this device." -> "当前设备未检测到可用 Root。"
            text.startsWith("Task id: ") -> "任务 ID: " + text.removePrefix("Task id: ")
            text == "Cancel requested for current task." -> "已请求取消当前任务。"
            text.startsWith("Failed to send cancel request: ") -> "发送取消请求失败: " + text.removePrefix("Failed to send cancel request: ")
            text == "Schedule task cannot be empty." -> "定时任务描述不能为空。"
            text == "Task description is empty." -> "任务描述不能为空。"
            text == "Package is required." -> "请选择应用。"
            text == "Please pick a valid date and time." -> "请选择有效的日期和时间。"
            text == "run_at must be in the future." -> "运行时间必须是未来时间。"
            text == "One-shot schedule in the past cannot be enabled again." -> "单次定时任务的时间已过，不能再次启用。"
            text == "Please select at least one weekday for weekly repeat." -> "每周重复模式至少需要选择一个星期几。"
            text == "schedule_id is empty." -> "schedule_id 为空。"
            text == "rule_id is empty." -> "通知规则 ID 为空。"
            text == "APP_RESOLVE: selecting the best app for this task..." -> "APP_RESOLVE：正在为任务选择最合适的应用..."
            text == "DEVICE_PREPARE: preparing device for task execution..." -> "DEVICE_PREPARE：正在准备设备执行任务..."
            text == "APP_ENTER: launching and readying the selected app..." -> "APP_ENTER：正在启动并准备目标应用..."
            text == "SCRIPT_ACT: replaying learned task-route script when available..." -> "SCRIPT_ACT：正在尝试复用已学习的任务路线脚本..."
            text == "VISION_ACT: entering vision-action loop (LLM + VLM)." -> "VISION_ACT：进入视觉执行循环（LLM + VLM）。"
            text == "Task finished successfully." -> "任务执行成功。"
            text == "Task finished with failure." -> "任务执行失败。"
            text == "Decomposing the request into sub-tasks..." -> "正在将请求拆分为子任务..."
            text.startsWith("APP_RESOLVE failed: ") -> "APP_RESOLVE 失败: " + text.removePrefix("APP_RESOLVE failed: ")
            text.startsWith("APP_ENTER failed: ") -> "APP_ENTER 失败: " + text.removePrefix("APP_ENTER failed: ")
            text.startsWith("Device preparation done") -> "设备准备完成。"
            text.startsWith("APP_ENTER done: ") -> "APP_ENTER 完成: " + text.removePrefix("APP_ENTER done: ")
            text.startsWith("SCRIPT_ACT result: ") -> "SCRIPT_ACT 结果: " + text.removePrefix("SCRIPT_ACT result: ")
            text.startsWith("SCRIPT_ACT task-map replay started") -> "SCRIPT_ACT 任务路线回放开始。"
            text.startsWith("SCRIPT_ACT task-map replay completed") -> "SCRIPT_ACT 任务路线回放完成。"
            text.startsWith("SCRIPT_ACT task-map replay fell back to VISION_ACT: ") -> "SCRIPT_ACT 回退到 VISION_ACT: " + text.removePrefix("SCRIPT_ACT task-map replay fell back to VISION_ACT: ")
            text == "Cancel requested, FSM will stop at the next safe point." -> "已请求取消，FSM 会在下一个安全点停止。"
            text == "Task cancelled by user." -> "任务已由用户取消。"
            text == "Screenshot captured, calling vision model for next action..." -> "已截图，正在调用视觉模型规划下一步..."
            text.startsWith("Calling LLM + VLM for next step planning...") -> "正在调用 LLM + VLM 规划下一步..."
            text.startsWith("Vision model responded.") -> "视觉模型已返回。"
            text.startsWith("Vision action output was invalid: ") -> "视觉动作输出无效: " + text.removePrefix("Vision action output was invalid: ")
            text == "Vision action output was invalid, stopping this task." -> "视觉动作输出无效，停止当前任务。"
            text == "Repeated ineffective actions detected, stopping to avoid loop." -> "检测到重复无效动作，为避免死循环已停止。"
            text.startsWith("Planner call failed: ") -> "规划调用失败: " + text.removePrefix("Planner call failed: ")
            text.startsWith("Map sync failed: ") -> "地图同步失败: " + text.removePrefix("Map sync failed: ")
            text.startsWith("Pull stable map failed: ") -> "拉取 Stable 地图失败: " + text.removePrefix("Pull stable map failed: ")
            text.startsWith("Pull candidate map failed: ") -> "拉取 Candidate 地图失败: " + text.removePrefix("Pull candidate map failed: ")
            text == "Route ID is empty." -> "任务路线 ID 为空。"
            text == "Task identity is empty." -> "任务标识为空。"
            text.startsWith("Schedule triggered: ") -> "定时任务已立即触发: " + text.removePrefix("Schedule triggered: ")
            text.startsWith("Trigger schedule failed: ") -> "立即触发定时任务失败: " + text.removePrefix("Trigger schedule failed: ")
            text.startsWith("Template list query failed: ") -> "任务模板列表查询失败: " + text.removePrefix("Template list query failed: ")
            text.startsWith("Template list refreshed: ") -> "任务模板列表已刷新: " + text.removePrefix("Template list refreshed: ")
            text.startsWith("Workflow list query failed: ") -> "工作流列表查询失败: " + text.removePrefix("Workflow list query failed: ")
            text.startsWith("Workflow list refreshed: ") -> "工作流列表已刷新: " + text.removePrefix("Workflow list refreshed: ")
            text == "Template description is empty." -> "任务模板描述不能为空。"
            text.startsWith("Save template failed: ") -> "保存任务模板失败: " + text.removePrefix("Save template failed: ")
            text == "Template saved." -> "任务模板已保存。"
            text.startsWith("Template saved: ") -> "任务模板已保存: " + text.removePrefix("Template saved: ")
            text == "template_id is empty." -> "任务模板 ID 为空。"
            text.startsWith("Delete template failed: ") -> "删除任务模板失败: " + text.removePrefix("Delete template failed: ")
            text == "Template deleted" -> "任务模板已删除。"
            text == "Workflow name is empty." -> "工作流名称不能为空。"
            text == "Select at least one template first." -> "请先至少选择一个任务模板。"
            text.startsWith("Save workflow failed: ") -> "保存工作流失败: " + text.removePrefix("Save workflow failed: ")
            text == "Workflow saved." -> "工作流已保存。"
            text.startsWith("Workflow saved: ") -> "工作流已保存: " + text.removePrefix("Workflow saved: ")
            text == "workflow_id is empty." -> "工作流 ID 为空。"
            text.startsWith("Delete workflow failed: ") -> "删除工作流失败: " + text.removePrefix("Delete workflow failed: ")
            text == "Workflow deleted" -> "工作流已删除。"
            text == "This workflow has no trigger." -> "这个工作流没有触发条件。"
            text.startsWith("Update workflow trigger failed: ") -> "更新工作流触发开关失败: " + text.removePrefix("Update workflow trigger failed: ")
            text.startsWith("Workflow run failed: ") -> "运行工作流失败: " + text.removePrefix("Workflow run failed: ")
            text == "Workflow submitted." -> "工作流已提交执行。"
            text.startsWith("Workflow submitted: ") -> "工作流已提交执行: " + text.removePrefix("Workflow submitted: ")
            else -> text
        }
    }
}
