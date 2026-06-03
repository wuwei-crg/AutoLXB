package com.example.lxb_ignition.core

import com.example.lxb_ignition.model.AppPackageOption
import com.example.lxb_ignition.model.TaskMapDetail
import com.example.lxb_ignition.model.TaskMapSegmentSnapshot
import com.example.lxb_ignition.model.TaskMapSnapshot
import com.example.lxb_ignition.model.TaskMapStepSnapshot
import com.example.lxb_ignition.model.TaskRouteActionSnapshot
import com.example.lxb_ignition.model.TaskRouteRecordSnapshot
import com.example.lxb_ignition.model.TaskSummary
import com.example.lxb_ignition.model.TaskTemplateSummary
import com.example.lxb_ignition.model.TraceEntry
import com.example.lxb_ignition.model.TraceMetaItem
import com.example.lxb_ignition.model.TracePage
import com.example.lxb_ignition.model.WorkflowStepSummary
import com.example.lxb_ignition.model.WorkflowSummary
import org.json.JSONArray
import org.json.JSONObject

data class TaskSubmitParsed(
    val message: String,
    val taskId: String
)

data class SystemControlParsed(
    val ok: Boolean,
    val detail: String
)

data class WorkflowRunParsed(
    val message: String,
    val workflowRunId: String
)

data class PortableExportParsed(
    val message: String,
    val bundleJson: String
)

data class PortableImportParsed(
    val message: String,
    val importedType: String,
    val workflowId: String,
    val templateId: String
)

object CoreApiParser {

    fun parseTaskSubmit(payload: ByteArray): TaskSubmitParsed {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null) {
            return TaskSubmitParsed("Invalid response: ${text.take(160)}", "")
        }
        val ok = obj.optBoolean("ok", false)
        val status = obj.optString("status", "")
        val taskId = obj.optString("task_id", "")
        return if (ok && status == "submitted" && taskId.isNotEmpty()) {
            TaskSubmitParsed("Task submitted: $taskId", taskId)
        } else {
            TaskSubmitParsed("Task submission failed: ${text.take(200)}", "")
        }
    }

    fun parseInstalledApps(payload: ByteArray): Pair<String, List<AppPackageOption>> {
        if (payload.isEmpty() || payload.size < 3) {
            return Pair("Installed app snapshot failed: empty/short response.", emptyList())
        }
        val status = payload[0].toInt() and 0xFF
        val jsonLen = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        val available = payload.size - 3
        val safeLen = when {
            jsonLen <= 0 -> available
            jsonLen > available -> available
            else -> jsonLen
        }
        val text = String(payload, 3, safeLen, Charsets.UTF_8)
        if (status != 1) {
            return Pair("Installed app snapshot failed: status=$status.", emptyList())
        }
        val arr = runCatching { JSONArray(text) }.getOrNull()
            ?: return Pair("Installed app snapshot failed: ${text.take(160)}", emptyList())
        val dedup = linkedMapOf<String, AppPackageOption>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val pkg = row.optString("package", "").trim()
            if (pkg.isEmpty()) continue
            val label = row.optString("label", row.optString("name", "")).trim()
            val prev = dedup[pkg]
            if (prev == null || (prev.label.isBlank() && label.isNotBlank())) {
                dedup[pkg] = AppPackageOption(packageName = pkg, label = label)
            }
        }
        val items = dedup.values.sortedWith(
            compareBy<AppPackageOption>({ if (it.label.isBlank()) 1 else 0 }, { it.label.lowercase() }, { it.packageName })
        )
        return Pair("Installed app snapshot refreshed: ${items.size} items.", items)
    }

    fun parseTaskList(payload: ByteArray): Pair<String, List<TaskSummary>> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Invalid task list response: ${text.take(160)}", emptyList())
        if (!obj.optBoolean("ok", false)) {
            return Pair("Task list query failed: ${text.take(160)}", emptyList())
        }
        val arr = obj.optJSONArray("tasks") ?: JSONArray()
        val dedup = linkedMapOf<String, TaskSummary>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val id = t.optString("task_id", "")
            if (id.isEmpty()) continue
            val summary = TaskSummary(
                taskId = id,
                userTask = t.optString("user_task", ""),
                state = t.optString("state", ""),
                finalState = t.optString("final_state", ""),
                reason = t.optString("reason", ""),
                taskSummary = t.optString("task_summary", ""),
                packageName = t.optString("package_name", ""),
                targetPage = t.optString("target_page", ""),
                source = t.optString("source", ""),
                scheduleId = t.optString("schedule_id", ""),
                routeId = t.optString("route_id", ""),
                taskMapMode = t.optString("task_map_mode", ""),
                hasTaskMap = t.optBoolean("has_task_map", false),
                memoryApplied = t.optBoolean("memory_applied", false),
                recordEnabled = t.optBoolean("record_enabled", false),
                recordFile = t.optString("record_file", ""),
                createdAt = t.optLong("created_at", 0L),
                finishedAt = t.optLong("finished_at", 0L)
            )
            dedup[id] = summary
        }
        val items = dedup.values
            .sortedByDescending { if (it.createdAt > 0L) it.createdAt else it.finishedAt }
        return Pair("Task list refreshed: ${items.size} items.", items)
    }

    fun parseTemplateList(payload: ByteArray): Pair<String, List<TaskTemplateSummary>> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Invalid template list response: ${text.take(160)}", emptyList())
        if (!obj.optBoolean("ok", false)) {
            return Pair("Template list query failed: ${text.take(160)}", emptyList())
        }
        val arr = obj.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val id = t.optString("template_id", "")
                if (id.isBlank()) continue
                add(
                    TaskTemplateSummary(
                        templateId = id,
                        name = t.optString("name", ""),
                        description = t.optString("description", ""),
                        packageName = t.optString("package_name", ""),
                        startPage = t.optString("start_page", ""),
                        mapPath = t.optString("map_path", ""),
                        userPlaybook = t.optString("user_playbook", ""),
                        recordEnabled = t.optBoolean("record_enabled", false),
                        taskMapMode = t.optString("task_map_mode", "off"),
                        routeId = t.optString("route_id", ""),
                        decomposeEnabled = t.optBoolean("decompose_enabled", false),
                        createdAtMs = t.optLong("created_at_ms", 0L),
                        updatedAtMs = t.optLong("updated_at_ms", 0L)
                    )
                )
            }
        }.sortedByDescending { it.updatedAtMs }
        return Pair("Template list refreshed: ${items.size} items.", items)
    }

    fun parseTemplateGet(payload: ByteArray): Pair<String, TaskTemplateSummary?> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Invalid template response: ${text.take(160)}", null)
        if (!obj.optBoolean("ok", false)) {
            return Pair("Template query failed: ${text.take(220)}", null)
        }
        val template = parseTemplateObject(obj.optJSONObject("template"))
        return if (template == null) {
            Pair("Template query failed: template missing.", null)
        } else {
            Pair("Template loaded: ${template.templateId}", template)
        }
    }

    fun parseWorkflowList(payload: ByteArray): Pair<String, List<WorkflowSummary>> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Invalid workflow list response: ${text.take(160)}", emptyList())
        if (!obj.optBoolean("ok", false)) {
            return Pair("Workflow list query failed: ${text.take(160)}", emptyList())
        }
        val arr = obj.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until arr.length()) {
                val w = arr.optJSONObject(i) ?: continue
                val id = w.optString("workflow_id", "")
                if (id.isBlank()) continue
                val stepsArr = w.optJSONArray("steps") ?: JSONArray()
                val steps = buildList {
                    for (j in 0 until stepsArr.length()) {
                        val s = stepsArr.optJSONObject(j) ?: continue
                        add(
                            WorkflowStepSummary(
                                stepId = s.optString("step_id", ""),
                                templateId = s.optString("template_id", ""),
                                name = s.optString("name", ""),
                                order = s.optInt("order", j)
                            )
                        )
                    }
                }
                add(
                    WorkflowSummary(
                        workflowId = id,
                        name = w.optString("name", ""),
                        triggerType = w.optString("trigger_type", "none"),
                        triggerEnabled = w.optBoolean("trigger_enabled", false),
                        triggerSummary = w.optString("trigger_summary", ""),
                        triggerConfigJson = w.optJSONObject("trigger_config")?.toString() ?: "",
                        steps = steps,
                        createdAtMs = w.optLong("created_at_ms", 0L),
                        updatedAtMs = w.optLong("updated_at_ms", 0L)
                    )
                )
            }
        }.sortedByDescending { it.updatedAtMs }
        return Pair("Workflow list refreshed: ${items.size} items.", items)
    }

    fun parseWorkflowGet(payload: ByteArray): Pair<String, WorkflowSummary?> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Invalid workflow response: ${text.take(160)}", null)
        if (!obj.optBoolean("ok", false)) {
            return Pair("Workflow query failed: ${text.take(220)}", null)
        }
        val workflow = parseWorkflowObject(obj.optJSONObject("workflow"))
        return if (workflow == null) {
            Pair("Workflow query failed: workflow missing.", null)
        } else {
            Pair("Workflow loaded: ${workflow.workflowId}", workflow)
        }
    }

    fun parseTemplateSave(payload: ByteArray): String {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null || !obj.optBoolean("ok", false)) {
            return "Save template failed: ${text.take(220)}"
        }
        val id = obj.optJSONObject("template")?.optString("template_id", "").orEmpty()
        return if (id.isBlank()) "Template saved." else "Template saved: $id"
    }

    fun parseWorkflowSave(payload: ByteArray): String {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null || !obj.optBoolean("ok", false)) {
            return "Save workflow failed: ${text.take(220)}"
        }
        val id = obj.optJSONObject("workflow")?.optString("workflow_id", "").orEmpty()
        return if (id.isBlank()) "Workflow saved." else "Workflow saved: $id"
    }

    fun parseWorkflowRun(payload: ByteArray): WorkflowRunParsed {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null || !obj.optBoolean("ok", false)) {
            return WorkflowRunParsed("Workflow run failed: ${text.take(220)}", "")
        }
        val runId = obj.optString("workflow_run_id", "")
        return WorkflowRunParsed(
            message = if (runId.isBlank()) "Workflow submitted." else "Workflow submitted: $runId",
            workflowRunId = runId
        )
    }

    fun parsePortableExport(payload: ByteArray): PortableExportParsed {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null || !obj.optBoolean("ok", false)) {
            return PortableExportParsed("导出失败：${text.take(220)}", "")
        }
        val bundleJson = obj.optString("bundle_json", "")
        return if (bundleJson.isBlank()) {
            PortableExportParsed("导出失败：导出内容为空", "")
        } else {
            PortableExportParsed("导出成功", bundleJson)
        }
    }

    fun parsePortableImport(payload: ByteArray): PortableImportParsed {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null || !obj.optBoolean("ok", false)) {
            return PortableImportParsed("导入失败：${text.take(220)}", "", "", "")
        }
        val importedType = obj.optString("imported_type", "")
        val workflowId = obj.optString("workflow_id", "")
        val templateId = obj.optString("template_id", "")
        val importedId = if (importedType == "workflow") workflowId else templateId
        return if (importedType.isBlank() || importedId.isBlank()) {
            PortableImportParsed("导入失败：导入结果不完整", "", "", "")
        } else {
            PortableImportParsed("导入成功", importedType, workflowId, templateId)
        }
    }

    fun parseSimpleOkMessage(payload: ByteArray, successPrefix: String): String {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null || !obj.optBoolean("ok", false)) {
            return "${successPrefix.replace("deleted", "failed").replace("Deleted", "Failed")}: ${text.take(220)}"
        }
        return obj.optString("message", "").takeIf { it.isNotBlank() } ?: successPrefix
    }

    private fun parseTemplateObject(t: JSONObject?): TaskTemplateSummary? {
        t ?: return null
        val id = t.optString("template_id", "")
        if (id.isBlank()) return null
        return TaskTemplateSummary(
            templateId = id,
            name = t.optString("name", ""),
            description = t.optString("description", ""),
            packageName = t.optString("package_name", ""),
            startPage = t.optString("start_page", ""),
            mapPath = t.optString("map_path", ""),
            userPlaybook = t.optString("user_playbook", ""),
            recordEnabled = t.optBoolean("record_enabled", false),
            taskMapMode = t.optString("task_map_mode", "off"),
            routeId = t.optString("route_id", ""),
            decomposeEnabled = t.optBoolean("decompose_enabled", false),
            createdAtMs = t.optLong("created_at_ms", 0L),
            updatedAtMs = t.optLong("updated_at_ms", 0L)
        )
    }

    private fun parseWorkflowObject(w: JSONObject?): WorkflowSummary? {
        w ?: return null
        val id = w.optString("workflow_id", "")
        if (id.isBlank()) return null
        val stepsArr = w.optJSONArray("steps") ?: JSONArray()
        val steps = buildList {
            for (j in 0 until stepsArr.length()) {
                val s = stepsArr.optJSONObject(j) ?: continue
                add(
                    WorkflowStepSummary(
                        stepId = s.optString("step_id", ""),
                        templateId = s.optString("template_id", ""),
                        name = s.optString("name", ""),
                        order = s.optInt("order", j)
                    )
                )
            }
        }
        return WorkflowSummary(
            workflowId = id,
            name = w.optString("name", ""),
            triggerType = w.optString("trigger_type", "none"),
            triggerEnabled = w.optBoolean("trigger_enabled", false),
            triggerSummary = w.optString("trigger_summary", ""),
            triggerConfigJson = w.optJSONObject("trigger_config")?.toString() ?: "",
            steps = steps,
            createdAtMs = w.optLong("created_at_ms", 0L),
            updatedAtMs = w.optLong("updated_at_ms", 0L)
        )
    }

    fun parseTraceLines(payload: ByteArray): Pair<String, TracePage> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj != null && obj.has("ok")) {
            if (!obj.optBoolean("ok", false)) {
                return Pair("Trace pull failed: ${obj.optString("err", text).take(220)}", emptyTracePage())
            }
            return parseTracePageObject(obj)
        }
        val items = text
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .mapIndexed { index, line -> parseTraceEntry(line, (index + 1).toLong()) }
            .toList()
        val oldestSeq = items.firstOrNull()?.seq ?: 0L
        val newestSeq = items.lastOrNull()?.seq ?: 0L
        return Pair(
            "Trace refreshed: ${items.size} lines.",
            TracePage(
                entries = items,
                hasMoreBefore = false,
                hasMoreAfter = false,
                oldestSeq = oldestSeq,
                newestSeq = newestSeq
            )
        )
    }

    fun parseTaskMapDetail(payload: ByteArray): Pair<String, TaskMapDetail?> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Invalid task route detail response: ${text.take(180)}", null)
        if (!obj.optBoolean("ok", false)) {
            return Pair("Task route detail query failed: ${text.take(220)}", null)
        }
        val detail = TaskMapDetail(
            routeId = obj.optString("route_id", ""),
            mode = obj.optString("mode", ""),
            source = obj.optString("source", ""),
            sourceId = obj.optString("source_id", ""),
            userTask = obj.optString("user_task", ""),
            packageName = obj.optString("package_name", ""),
            hasMap = obj.optBoolean("has_map", false),
            hasLatestSuccessRecord = obj.optBoolean("has_latest_success_record", false),
            hasLatestAttemptRecord = obj.optBoolean("has_latest_attempt_record", false),
            taskMap = obj.optJSONObject("task_map")?.let(::parseTaskMapSnapshot),
            latestSuccessRecord = obj.optJSONObject("latest_success_record")?.let(::parseTaskRouteRecordSnapshot),
            latestAttemptRecord = obj.optJSONObject("latest_attempt_record")?.let(::parseTaskRouteRecordSnapshot)
        )
        return Pair("Task route details loaded.", detail)
    }

    private fun parseTracePageObject(obj: JSONObject): Pair<String, TracePage> {
        val arr = obj.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<TraceEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val seq = row.optLong("seq", 0L)
            val line = row.optString("line", "").trim()
            if (line.isEmpty()) continue
            items.add(parseTraceEntry(line, seq))
        }
        val page = TracePage(
            entries = items,
            hasMoreBefore = obj.optBoolean("has_more_before", false),
            hasMoreAfter = obj.optBoolean("has_more_after", false),
            oldestSeq = obj.optLong("oldest_seq", items.firstOrNull()?.seq ?: 0L),
            newestSeq = obj.optLong("newest_seq", items.lastOrNull()?.seq ?: 0L)
        )
        return Pair("Trace refreshed: ${items.size} lines.", page)
    }

    private fun emptyTracePage(): TracePage {
        return TracePage(
            entries = emptyList(),
            hasMoreBefore = false,
            hasMoreAfter = false,
            oldestSeq = 0L,
            newestSeq = 0L
        )
    }

    private fun parseTaskMapSnapshot(obj: JSONObject): TaskMapSnapshot {
        val segments = mutableListOf<TaskMapSegmentSnapshot>()
        val arr = obj.optJSONArray("segments") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val segObj = arr.optJSONObject(i) ?: continue
            val steps = mutableListOf<TaskMapStepSnapshot>()
            val stepArr = segObj.optJSONArray("steps") ?: JSONArray()
            for (j in 0 until stepArr.length()) {
                val stepObj = stepArr.optJSONObject(j) ?: continue
                steps += TaskMapStepSnapshot(
                    stepId = stepObj.optString("step_id", ""),
                    sourceActionId = stepObj.optString("source_action_id", ""),
                    op = stepObj.optString("op", ""),
                    args = jsonArrayToStringList(stepObj.optJSONArray("args")),
                    fallbackPoint = normalizeText(stepObj.opt("fallback_point")?.toString().orEmpty(), 240),
                    semanticNote = stepObj.optString("semantic_note", ""),
                    expected = stepObj.optString("expected", ""),
                    locatorFields = jsonObjectToMetaItems(stepObj.optJSONObject("locator")),
                    containerProbeFields = jsonObjectToMetaItems(stepObj.optJSONObject("container_probe")),
                    semanticDescriptorFields = jsonObjectToMetaItems(stepObj.optJSONObject("semantic_descriptor")),
                    tapPoint = normalizeText(stepObj.opt("tap_point")?.toString().orEmpty(), 240),
                    swipeFields = jsonObjectToMetaItems(stepObj.optJSONObject("swipe")),
                    portableKind = stepObj.optString("portable_kind", ""),
                    adaptationStatus = stepObj.optString("adaptation_status", ""),
                    adaptationError = stepObj.optString("adaptation_error", ""),
                    materializedFromStepId = stepObj.optString("materialized_from_step_id", ""),
                    materializedAtMs = stepObj.optLong("materialized_at_ms", 0L)
                )
            }
            segments += TaskMapSegmentSnapshot(
                segmentId = segObj.optString("segment_id", ""),
                subTaskId = segObj.optString("sub_task_id", ""),
                subTaskIndex = segObj.optInt("sub_task_index", 0),
                subTaskDescription = segObj.optString("sub_task_description", ""),
                successCriteria = segObj.optString("success_criteria", ""),
                packageName = segObj.optString("package_name", ""),
                packageLabel = segObj.optString("package_label", ""),
                inputs = jsonArrayToStringList(segObj.optJSONArray("inputs")),
                outputs = jsonArrayToStringList(segObj.optJSONArray("outputs")),
                steps = steps
            )
        }
        return TaskMapSnapshot(
            schema = obj.optString("schema", ""),
            mode = obj.optString("mode", ""),
            packageName = obj.optString("package_name", ""),
            packageLabel = obj.optString("package_label", ""),
            createdFromTaskId = obj.optString("created_from_task_id", ""),
            createdAtMs = obj.optLong("created_at_ms", 0L),
            lastReplayStatus = obj.optString("last_replay_status", ""),
            finishAfterReplay = obj.optBoolean("finish_after_replay", false),
            segments = segments
        )
    }

    private fun parseTaskRouteRecordSnapshot(obj: JSONObject): TaskRouteRecordSnapshot {
        val actions = mutableListOf<TaskRouteActionSnapshot>()
        val arr = obj.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val actionObj = arr.optJSONObject(i) ?: continue
            actions += TaskRouteActionSnapshot(
                actionId = actionObj.optString("action_id", ""),
                subTaskId = actionObj.optString("sub_task_id", ""),
                turn = actionObj.optInt("turn", 0),
                op = actionObj.optString("op", ""),
                args = jsonArrayToStringList(actionObj.optJSONArray("args")),
                rawCommand = actionObj.optString("raw_command", ""),
                execResult = actionObj.optString("exec_result", ""),
                execError = actionObj.optString("exec_error", ""),
                createdPageSemantics = actionObj.optString("created_page_semantics", ""),
                locatorFields = jsonObjectToMetaItems(actionObj.optJSONObject("locator")),
                visionFields = jsonObjectToMetaItems(actionObj.optJSONObject("vision"))
            )
        }
        return TaskRouteRecordSnapshot(
            schema = obj.optString("schema", ""),
            taskId = obj.optString("task_id", ""),
            rootTask = obj.optString("root_task", ""),
            packageName = obj.optString("package_name", ""),
            packageLabel = obj.optString("package_label", ""),
            createdAtMs = obj.optLong("created_at_ms", 0L),
            status = obj.optString("status", ""),
            finalState = obj.optString("final_state", ""),
            reason = obj.optString("reason", ""),
            actions = actions
        )
    }

    private fun jsonObjectToMetaItems(obj: JSONObject?): List<TraceMetaItem> {
        if (obj == null) return emptyList()
        val out = mutableListOf<TraceMetaItem>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key) ?: continue
            val rendered = when (value) {
                is JSONObject -> normalizeText(value.toString(), 320)
                is JSONArray -> normalizeText(value.toString(), 320)
                else -> normalizeText(value.toString(), 320)
            }
            if (rendered.isNotBlank()) {
                out += TraceMetaItem(label = key, value = rendered)
            }
        }
        return out
    }

    private fun parseTraceEntry(line: String, seq: Long): TraceEntry {
        val obj = runCatching { JSONObject(line) }.getOrNull()
        if (obj == null) {
            return TraceEntry(
                seq = seq,
                rawLine = line,
                timestamp = "",
                event = "raw",
                taskId = "",
                summary = line.take(120),
                detail = "",
                isError = false,
                meta = emptyList(),
                fields = emptyList()
            )
        }
        val event = obj.optString("event", "").trim().ifBlank { "unknown" }
        val ts = obj.optString("ts", "").trim()
        val taskId = obj.optString("task_id", "").trim()
        val summary = buildTraceSummary(obj, event)
        val meta = buildTraceMeta(obj, event)
        val fields = buildTraceFields(obj)
        val detail = buildTraceDetail(fields)
        val isError = event.contains("fail", ignoreCase = true) ||
            event.contains("error", ignoreCase = true) ||
            event.contains("invalid", ignoreCase = true) ||
            obj.has("error")
        return TraceEntry(
            seq = seq,
            rawLine = line,
            timestamp = ts,
            event = event,
            taskId = taskId,
            summary = summary,
            detail = detail,
            isError = isError,
            meta = meta,
            fields = fields
        )
    }

    private fun buildTraceSummary(obj: JSONObject, event: String): String {
        val phase = optText(obj, "phase")
        val state = optText(obj, "state")
        val action = optText(obj, "action")
        val pkg = optText(obj, "package")
        val reason = optText(obj, "reason")
        val error = optText(obj, "error")
        val finalTask = optText(obj, "final_task")
        val title = optText(obj, "title")
        val userTask = optText(obj, "user_task")
        val raw = optText(obj, "raw")
        val result = optText(obj, "result")
        return when {
            event == "fsm_state_enter" -> firstNonBlank(state, userTask, event)
            event == "notify_trigger" -> firstNonBlank(error, optText(obj, "rule_name"), title, pkg, event)
            event.startsWith("exec_tap_") -> buildActionTitle("Tap", obj, event)
            event.startsWith("exec_swipe_") -> buildSwipeTitle(obj, event)
            event.startsWith("exec_input_") -> firstNonBlank(error, "Input", event)
            event.startsWith("exec_wait_") -> firstNonBlank(optText(obj, "ms").let { if (it.isBlank()) "" else "Wait ${it}ms" }, event)
            event.startsWith("exec_back_") -> "Back"
            event.contains("unlock", ignoreCase = true) -> firstNonBlank(error, result, reason, state, event)
            event.startsWith("llm_prompt_") -> firstNonBlank(phase, state, action, userTask, event)
            event.startsWith("llm_response_") -> firstNonBlank(error, finalTask, raw, reason, event)
            event.startsWith("vision_") || event.startsWith("planner_") ->
                firstNonBlank(error, reason, raw, finalTask, event)
            event.startsWith("fsm_script_act_") ->
                firstNonBlank(error, reason, result, buildScriptActSummary(obj), event)
            event.startsWith("fsm_app_enter") || event.startsWith("fsm_app_resolve") || event.startsWith("resolve_") ->
                firstNonBlank(error, reason, optText(obj, "resolved_package"), optText(obj, "package"), event)
            event.startsWith("cortex_") ->
                firstNonBlank(error, finalTask, reason, userTask, event)
            event == "trace_truncated" ->
                firstNonBlank(reason, "Trace truncated", event)
            else -> firstNonBlank(error, finalTask, reason, phase, state, action, if (pkg.isNotBlank() && title.isNotBlank()) "$pkg | $title" else pkg, raw, event)
        }
            .take(160)
    }

    private fun buildTraceFields(obj: JSONObject): List<TraceMetaItem> {
        val parts = ArrayList<TraceMetaItem>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "event" || key == "ts") continue
            val text = optText(obj, key, 320)
            if (text.isNotEmpty()) {
                parts.add(TraceMetaItem(label = key, value = text))
            }
        }
        return parts
    }

    private fun buildTraceDetail(fields: List<TraceMetaItem>): String {
        return fields.joinToString("\n") { "${it.label}=${it.value}" }
    }

    private fun buildTraceMeta(obj: JSONObject, event: String): List<TraceMetaItem> {
        val out = ArrayList<TraceMetaItem>(4)
        when {
            event == "fsm_state_enter" -> {
                addMeta(out, "State", optText(obj, "state"))
                addMeta(out, "Task", optText(obj, "user_task"), 80)
                addMeta(out, "Package", optText(obj, "package"))
            }
            event == "notify_trigger" -> {
                addMeta(out, "Rule", optText(obj, "rule_name"))
                addMeta(out, "Package", optText(obj, "package"))
                addMeta(out, "Title", optText(obj, "title"), 80)
                addMeta(out, "Text", optText(obj, "text"), 96)
                addMeta(out, "Error", optText(obj, "error"), 96)
            }
            event.startsWith("exec_tap_") -> {
                addMeta(out, "Point", joinNonBlank(", ", optText(obj, "x"), optText(obj, "y")))
            }
            event.startsWith("exec_swipe_") -> {
                addMeta(out, "From", joinNonBlank(", ", optText(obj, "x1"), optText(obj, "y1")))
                addMeta(out, "To", joinNonBlank(", ", optText(obj, "x2"), optText(obj, "y2")))
                addMeta(out, "Duration", optText(obj, "duration").let { if (it.isBlank()) "" else "${it}ms" })
                addMeta(out, "Wait", optText(obj, "wait_ms").let { if (it.isBlank()) "" else "${it}ms" })
            }
            event.startsWith("exec_input_") -> {
                addMeta(out, "Text", optText(obj, "text"), 80)
                addMeta(out, "Method", joinNonBlank(" -> ", optText(obj, "chosen_method"), optText(obj, "actual_method")))
                addMeta(out, "Status", optText(obj, "status"))
            }
            event.startsWith("exec_wait_") -> {
                addMeta(out, "Duration", optText(obj, "ms").let { if (it.isBlank()) "" else "${it}ms" })
            }
            event.startsWith("exec_back_") -> {
                addMeta(out, "Action", "Back")
            }
            event.contains("unlock", ignoreCase = true) -> {
                addMeta(out, "Result", optText(obj, "result"))
                addMeta(out, "Attempt", buildAttemptText(obj))
                addMeta(out, "Package", firstNonBlank(optText(obj, "final_package"), optText(obj, "package")))
                addMeta(out, "Screen", joinNonBlank(" / ", optText(obj, "screen_state_final"), optText(obj, "lock_hint_final")))
                addMeta(out, "Error", optText(obj, "err"), 96)
            }
            event.startsWith("fsm_script_act_") -> {
                addMeta(out, "Package", optText(obj, "package"))
                addMeta(out, "Segment", optText(obj, "segment_id"))
                addMeta(out, "Step", firstNonBlank(optText(obj, "step_id"), optText(obj, "step"), optText(obj, "step_index"), optText(obj, "index")))
                addMeta(out, "Reason", firstNonBlank(optText(obj, "reason"), optText(obj, "error")), 96)
            }
            event.startsWith("fsm_app_enter") || event.startsWith("fsm_app_resolve") || event.startsWith("resolve_") -> {
                addMeta(out, "Package", firstNonBlank(optText(obj, "resolved_package"), optText(obj, "package")))
                addMeta(out, "Stage", firstNonBlank(optText(obj, "stage"), optText(obj, "result")))
                addMeta(out, "Reason", firstNonBlank(optText(obj, "reason"), optText(obj, "error")), 96)
            }
            event.startsWith("llm_prompt_") || event.startsWith("llm_response_") -> {
                addMeta(out, "Phase", optText(obj, "phase"))
                addMeta(out, "State", optText(obj, "state"))
                addMeta(out, "Attempt", buildAttemptText(obj))
                addMeta(out, "Task", firstNonBlank(optText(obj, "final_task"), optText(obj, "user_task")), 80)
                addMeta(out, "Raw", optText(obj, "raw"), 96)
                addMeta(out, "Error", optText(obj, "error"), 96)
            }
            event.startsWith("vision_") || event.startsWith("planner_") -> {
                addMeta(out, "Phase", optText(obj, "phase"))
                addMeta(out, "Attempt", buildAttemptText(obj))
                addMeta(out, "Error", firstNonBlank(optText(obj, "error"), optText(obj, "reason")), 96)
                addMeta(out, "Raw", optText(obj, "raw"), 96)
            }
            event.startsWith("cortex_") -> {
                addMeta(out, "Task", firstNonBlank(optText(obj, "final_task"), optText(obj, "user_task")), 80)
                addMeta(out, "Package", optText(obj, "package"))
                addMeta(out, "Reason", firstNonBlank(optText(obj, "reason"), optText(obj, "error")), 96)
            }
            event == "trace_truncated" -> {
                addMeta(out, "Dropped", optText(obj, "drop_head_lines"))
                addMeta(out, "Kept", optText(obj, "kept_tail_lines"))
                addMeta(out, "Reason", optText(obj, "reason"))
            }
            else -> {
                addMeta(out, "Package", optText(obj, "package"))
                addMeta(out, "Phase", optText(obj, "phase"))
                addMeta(out, "State", optText(obj, "state"))
                addMeta(out, "Reason", firstNonBlank(optText(obj, "reason"), optText(obj, "error")), 96)
                addMeta(out, "Task", firstNonBlank(optText(obj, "final_task"), optText(obj, "user_task")), 80)
            }
        }
        if (out.none { it.label == "Task" } && obj.optString("task_id", "").isNotBlank()) {
            addMeta(out, "TaskId", obj.optString("task_id", "").trim(), 24)
        }
        return out.take(4)
    }

    private fun buildActionTitle(label: String, obj: JSONObject, event: String): String {
        val x = optText(obj, "x")
        val y = optText(obj, "y")
        val stage = when {
            event.endsWith("_start") -> "start"
            event.endsWith("_done") -> "done"
            else -> ""
        }
        val point = joinNonBlank(", ", x, y)
        return joinNonBlank(" ", label, if (point.isBlank()) "" else "($point)", stage).ifBlank { label }
    }

    private fun buildSwipeTitle(obj: JSONObject, event: String): String {
        val from = joinNonBlank(", ", optText(obj, "x1"), optText(obj, "y1"))
        val to = joinNonBlank(", ", optText(obj, "x2"), optText(obj, "y2"))
        val stage = when {
            event.endsWith("_start") -> "start"
            event.endsWith("_done") -> "done"
            event.endsWith("_post_wait") -> "wait"
            else -> ""
        }
        return joinNonBlank(" ", "Swipe", if (from.isNotBlank() || to.isNotBlank()) "($from -> $to)" else "", stage)
            .ifBlank { "Swipe" }
    }

    private fun buildScriptActSummary(obj: JSONObject): String {
        return joinNonBlank(
            " | ",
            optText(obj, "package"),
            optText(obj, "segment_id"),
            firstNonBlank(optText(obj, "step_id"), optText(obj, "step"), optText(obj, "step_index"), optText(obj, "index"))
        )
    }

    private fun buildAttemptText(obj: JSONObject): String {
        val attempt = firstNonBlank(optText(obj, "attempt"), optText(obj, "route_attempt"), optText(obj, "swipe_attempt"))
        val total = firstNonBlank(optText(obj, "max_attempts"), optText(obj, "swipe_total"))
        return when {
            attempt.isNotBlank() && total.isNotBlank() -> "$attempt/$total"
            else -> attempt
        }
    }

    private fun addMeta(
        out: MutableList<TraceMetaItem>,
        label: String,
        value: String,
        maxLen: Int = 72
    ) {
        val clean = normalizeText(value, maxLen)
        if (clean.isNotBlank() && out.none { it.label == label && it.value == clean }) {
            out.add(TraceMetaItem(label = label, value = clean))
        }
    }

    private fun optText(obj: JSONObject, key: String, maxLen: Int = 180): String {
        if (!obj.has(key)) return ""
        val value = obj.opt(key) ?: return ""
        return normalizeText(value.toString(), maxLen)
    }

    private fun normalizeText(value: String, maxLen: Int = 180): String {
        val normalized = value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length <= maxLen) {
            return normalized
        }
        return normalized.take(maxLen - 3).trimEnd() + "..."
    }

    private fun firstNonBlank(vararg values: String): String {
        for (value in values) {
            if (value.isNotBlank()) {
                return value
            }
        }
        return ""
    }

    private fun joinNonBlank(separator: String, vararg values: String): String {
        return values.filter { it.isNotBlank() }.joinToString(separator)
    }

    private fun jsonArrayToStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) {
                out.add(s)
            }
        }
        return out
    }

    fun parseSystemControl(payload: ByteArray): SystemControlParsed {
        if (payload.isEmpty()) {
            return SystemControlParsed(false, "empty_response")
        }
        if (payload.size < 3) {
            return SystemControlParsed(false, "short_response(${payload.size})")
        }
        val status = payload[0].toInt() and 0xFF
        val jsonLen = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        val available = payload.size - 3
        val safeLen = when {
            jsonLen <= 0 -> available
            jsonLen > available -> available
            else -> jsonLen
        }
        val text = String(payload, 3, safeLen, Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null) {
            return SystemControlParsed(status == 1, "invalid_json:${text.take(180)}")
        }
        val ok = (status == 1) && obj.optBoolean("ok", false)
        val detail = buildString {
            val stdout = obj.optString("stdout", "").trim()
            val stderr = obj.optString("stderr", "").trim()
            val err = obj.optString("error", "").trim()
            if (stdout.isNotEmpty()) append("stdout=").append(stdout.take(200))
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append(" | ")
                append("stderr=").append(stderr.take(200))
            }
            if (err.isNotEmpty()) {
                if (isNotEmpty()) append(" | ")
                append("error=").append(err.take(200))
            }
            if (isEmpty()) {
                append("ok=").append(ok)
            }
        }
        return SystemControlParsed(ok, detail)
    }
}
