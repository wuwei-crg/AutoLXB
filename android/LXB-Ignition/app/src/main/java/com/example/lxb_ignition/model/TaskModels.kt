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
    val routeId: String,
    val taskMapMode: String,
    val hasTaskMap: Boolean,
    val memoryApplied: Boolean,
    val recordEnabled: Boolean,
    val recordFile: String,
    val createdAt: Long,
    val finishedAt: Long
)

data class TaskMapDetail(
    val routeId: String,
    val mode: String,
    val source: String,
    val sourceId: String,
    val userTask: String,
    val packageName: String,
    val hasMap: Boolean,
    val hasLatestSuccessRecord: Boolean,
    val hasLatestAttemptRecord: Boolean,
    val taskMap: TaskMapSnapshot?,
    val latestSuccessRecord: TaskRouteRecordSnapshot?,
    val latestAttemptRecord: TaskRouteRecordSnapshot?
)

data class TaskMapSnapshot(
    val schema: String,
    val mode: String,
    val packageName: String,
    val packageLabel: String,
    val createdFromTaskId: String,
    val createdAtMs: Long,
    val lastReplayStatus: String,
    val finishAfterReplay: Boolean,
    val segments: List<TaskMapSegmentSnapshot>
)

data class TaskMapSegmentSnapshot(
    val segmentId: String,
    val subTaskId: String,
    val subTaskIndex: Int,
    val subTaskDescription: String,
    val successCriteria: String,
    val packageName: String,
    val packageLabel: String,
    val inputs: List<String>,
    val outputs: List<String>,
    val steps: List<TaskMapStepSnapshot>
)

data class TaskMapStepSnapshot(
    val stepId: String,
    val sourceActionId: String,
    val op: String,
    val args: List<String>,
    val fallbackPoint: String,
    val semanticNote: String,
    val expected: String,
    val locatorFields: List<TraceMetaItem>,
    val containerProbeFields: List<TraceMetaItem>,
    val semanticDescriptorFields: List<TraceMetaItem>,
    val tapPoint: String,
    val swipeFields: List<TraceMetaItem>,
    val portableKind: String,
    val adaptationStatus: String,
    val adaptationError: String,
    val materializedFromStepId: String,
    val materializedAtMs: Long
)

data class TaskRouteRecordSnapshot(
    val schema: String,
    val taskId: String,
    val rootTask: String,
    val packageName: String,
    val packageLabel: String,
    val createdAtMs: Long,
    val status: String,
    val finalState: String,
    val reason: String,
    val actions: List<TaskRouteActionSnapshot>
)

data class TaskRouteActionSnapshot(
    val actionId: String,
    val subTaskId: String,
    val turn: Int,
    val op: String,
    val args: List<String>,
    val rawCommand: String,
    val execResult: String,
    val execError: String,
    val createdPageSemantics: String,
    val locatorFields: List<TraceMetaItem>,
    val visionFields: List<TraceMetaItem>
)

data class TaskTemplateSummary(
    val templateId: String,
    val name: String,
    val description: String,
    val packageName: String,
    val startPage: String,
    val mapPath: String,
    val userPlaybook: String,
    val recordEnabled: Boolean,
    val taskMapMode: String,
    val routeId: String,
    val decomposeEnabled: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

data class WorkflowStepSummary(
    val stepId: String,
    val templateId: String,
    val name: String,
    val order: Int
)

data class WorkflowSummary(
    val workflowId: String,
    val name: String,
    val triggerType: String,
    val triggerEnabled: Boolean,
    val triggerSummary: String,
    val triggerConfigJson: String,
    val steps: List<WorkflowStepSummary>,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

data class AppPackageOption(
    val packageName: String,
    val label: String
)

data class TraceMetaItem(
    val label: String,
    val value: String
)

data class TracePage(
    val entries: List<TraceEntry>,
    val hasMoreBefore: Boolean,
    val hasMoreAfter: Boolean,
    val oldestSeq: Long,
    val newestSeq: Long
)

data class TraceEntry(
    val seq: Long,
    val rawLine: String,
    val timestamp: String,
    val event: String,
    val taskId: String,
    val summary: String,
    val detail: String,
    val isError: Boolean,
    val meta: List<TraceMetaItem>,
    val fields: List<TraceMetaItem>
)
