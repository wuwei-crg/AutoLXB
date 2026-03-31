package com.example.lxb_ignition.core

import com.example.lxb_ignition.model.AppPackageOption
import com.example.lxb_ignition.model.NotificationTriggerRuleSummary
import com.example.lxb_ignition.model.ScheduleSummary
import com.example.lxb_ignition.model.TaskSummary
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

    fun parseScheduleList(
        payload: ByteArray,
        repeatDaily: String,
        repeatOnce: String
    ): Pair<String, List<ScheduleSummary>> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Invalid schedule list response: ${text.take(160)}", emptyList())
        if (!obj.optBoolean("ok", false)) {
            return Pair("Schedule list query failed: ${text.take(160)}", emptyList())
        }
        val arr = obj.optJSONArray("schedules") ?: JSONArray()
        val dedup = linkedMapOf<String, ScheduleSummary>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val id = s.optString("schedule_id", "")
            if (id.isEmpty()) continue
            val summary = ScheduleSummary(
                scheduleId = id,
                name = s.optString("name", ""),
                userTask = s.optString("user_task", ""),
                packageName = s.optString("package", ""),
                startPage = s.optString("start_page", ""),
                recordEnabled = s.optBoolean("record_enabled", false),
                runAtMs = s.optLong("run_at", 0L),
                repeatMode = s.optString(
                    "repeat_mode",
                    if (s.optBoolean("repeat_daily", false)) repeatDaily else repeatOnce
                ),
                repeatWeekdays = s.optInt("repeat_weekdays", 0),
                nextRunAt = s.optLong("next_run_at", 0L),
                lastTriggeredAt = s.optLong("last_triggered_at", 0L),
                triggerCount = s.optLong("trigger_count", 0L),
                enabled = s.optBoolean("enabled", true),
                createdAt = s.optLong("created_at", 0L),
                userPlaybook = s.optString("user_playbook", "")
            )
            dedup[id] = summary
        }
        val items = dedup.values.sortedByDescending { it.nextRunAt }
        return Pair("Schedule list refreshed: ${items.size} items.", items)
    }

    fun parseScheduleAdd(payload: ByteArray): String {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj == null || !obj.optBoolean("ok", false)) {
            return "Add schedule failed: ${text.take(220)}"
        }
        val scheduleObj = obj.optJSONObject("schedule")
        val sid = scheduleObj?.optString("schedule_id", "") ?: ""
        return if (sid.isNotEmpty()) "Schedule added: $sid" else "Schedule added."
    }

    fun parseScheduleUpdate(payload: ByteArray, scheduleId: String): String {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        return if (obj == null || !obj.optBoolean("ok", false)) {
            "Update schedule failed: ${text.take(220)}"
        } else {
            "Schedule updated: $scheduleId"
        }
    }

    fun parseScheduleRemove(payload: ByteArray, scheduleId: String): String {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        return if (obj == null || !obj.optBoolean("ok", false)) {
            "Remove schedule failed: ${text.take(220)}"
        } else if (obj.optBoolean("removed", false)) {
            "Schedule removed: $scheduleId"
        } else {
            "Schedule not found: $scheduleId"
        }
    }

    fun parseNotifyRuleList(payload: ByteArray): Pair<String, List<NotificationTriggerRuleSummary>> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Invalid notify rule list response: ${text.take(160)}", emptyList())
        if (!obj.optBoolean("ok", false)) {
            return Pair("Notify rule list query failed: ${text.take(160)}", emptyList())
        }
        val arr = obj.optJSONArray("rules") ?: JSONArray()
        val dedup = linkedMapOf<String, NotificationTriggerRuleSummary>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val id = r.optString("id", "")
            if (id.isBlank()) continue
            val action = r.optJSONObject("action") ?: JSONObject()
            val summary = NotificationTriggerRuleSummary(
                id = id,
                name = r.optString("name", ""),
                enabled = r.optBoolean("enabled", true),
                priority = r.optInt("priority", 100),
                packageMode = r.optString("package_mode", "any"),
                packageList = jsonArrayToStringList(r.optJSONArray("package_list")),
                textMode = r.optString("text_mode", "contains"),
                titlePattern = r.optString("title_pattern", ""),
                bodyPattern = r.optString("body_pattern", ""),
                llmConditionEnabled = r.optBoolean("llm_condition_enabled", false),
                llmCondition = r.optString("llm_condition", ""),
                llmYesToken = r.optString("llm_yes_token", "yes"),
                llmNoToken = r.optString("llm_no_token", "no"),
                llmTimeoutMs = r.optLong("llm_timeout_ms", 60000L),
                taskRewriteEnabled = r.optBoolean("task_rewrite_enabled", false),
                taskRewriteInstruction = r.optString("task_rewrite_instruction", ""),
                taskRewriteTimeoutMs = r.optLong("task_rewrite_timeout_ms", 60000L),
                taskRewriteFailPolicy = r.optString("task_rewrite_fail_policy", "fallback_raw_task"),
                cooldownMs = r.optLong("cooldown_ms", 60_000L),
                stopAfterMatched = r.optBoolean("stop_after_matched", true),
                actionType = action.optString("type", "run_task"),
                actionUserTask = action.optString("user_task", ""),
                actionPackage = action.optString("package", ""),
                actionUserPlaybook = action.optString("user_playbook", ""),
                actionUseMap = if (action.has("use_map")) action.optBoolean("use_map", true) else null
            )
            dedup[id] = summary
        }
        val items = dedup.values.sortedWith(
            compareByDescending<NotificationTriggerRuleSummary> { it.priority }
                .thenBy { it.id }
        )
        return Pair("Notify rules refreshed: ${items.size} items.", items)
    }

    fun parseNotifyRuleUpsert(payload: ByteArray): Pair<String, String> {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return Pair("Upsert notify rule failed: ${text.take(220)}", "")
        if (!obj.optBoolean("ok", false)) {
            return Pair("Upsert notify rule failed: ${text.take(220)}", "")
        }
        val updated = obj.optBoolean("updated", false)
        val ruleObj = obj.optJSONObject("rule")
        val id = ruleObj?.optString("id", "").orEmpty()
        val msg = if (updated) {
            if (id.isNotBlank()) "Notify rule updated: $id" else "Notify rule updated."
        } else {
            if (id.isNotBlank()) "Notify rule added: $id" else "Notify rule added."
        }
        return Pair(msg, id)
    }

    fun parseNotifyRuleRemove(payload: ByteArray, ruleId: String): String {
        val text = payload.toString(Charsets.UTF_8)
        val obj = runCatching { JSONObject(text) }.getOrNull()
        return if (obj == null || !obj.optBoolean("ok", false)) {
            "Remove notify rule failed: ${text.take(220)}"
        } else if (obj.optBoolean("removed", false)) {
            "Notify rule removed: $ruleId"
        } else {
            "Notify rule not found: $ruleId"
        }
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
