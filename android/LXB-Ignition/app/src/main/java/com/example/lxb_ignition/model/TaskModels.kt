package com.example.lxb_ignition.model

data class TaskSummary(
    val taskId: String,
    val userTask: String,
    val state: String,
    val finalState: String,
    val reason: String,
    val taskSummary: String,
    val packageName: String,
    val targetPage: String,
    val source: String,
    val scheduleId: String,
    val memoryApplied: Boolean,
    val recordEnabled: Boolean,
    val recordFile: String,
    val createdAt: Long,
    val finishedAt: Long
)

data class ScheduleSummary(
    val scheduleId: String,
    val name: String,
    val userTask: String,
    val packageName: String,
    val startPage: String,
    val recordEnabled: Boolean,
    val runAtMs: Long,
    val repeatMode: String,
    val repeatWeekdays: Int,
    val nextRunAt: Long,
    val lastTriggeredAt: Long,
    val triggerCount: Long,
    val enabled: Boolean,
    val createdAt: Long,
    val userPlaybook: String
)

data class NotificationTriggerRuleSummary(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val packageMode: String,
    val packageList: List<String>,
    val textMode: String,
    val titlePattern: String,
    val bodyPattern: String,
    val llmConditionEnabled: Boolean,
    val llmCondition: String,
    val llmYesToken: String,
    val llmNoToken: String,
    val llmTimeoutMs: Long,
    val taskRewriteEnabled: Boolean,
    val taskRewriteInstruction: String,
    val taskRewriteTimeoutMs: Long,
    val taskRewriteFailPolicy: String,
    val cooldownMs: Long,
    val stopAfterMatched: Boolean,
    val actionType: String,
    val actionUserTask: String,
    val actionPackage: String,
    val actionUserPlaybook: String,
    val actionUseMap: Boolean?
)

data class AppPackageOption(
    val packageName: String,
    val label: String
)
